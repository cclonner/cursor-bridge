#!/usr/bin/env node
'use strict';

const https = require('https');
const fs = require('fs');
const net = require('net');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');
const { WebSocketServer } = require('ws');
const { Bonjour } = require('bonjour-service');
const qrcode = require('qrcode-terminal');
const { ensureTls } = require('./certs');

const CONFIG_PATH = path.join(__dirname, 'config.json');
if (!fs.existsSync(CONFIG_PATH)) {
  fs.copyFileSync(path.join(__dirname, 'config.example.json'), CONFIG_PATH);
  console.log('  Создан config.json из config.example.json — отредактируйте его при необходимости');
}
const CONFIG = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
try { fs.chmodSync(CONFIG_PATH, 0o600); } catch {}
const WEB_DIR = path.join(__dirname, 'web');
const VENDOR = {
  '/vendor/xterm.js': require.resolve('@xterm/xterm/lib/xterm.js'),
  '/vendor/xterm.css': require.resolve('@xterm/xterm/css/xterm.css'),
  '/vendor/addon-fit.js': require.resolve('@xterm/addon-fit/lib/addon-fit.js'),
};
const SEC_HEADERS = {
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; connect-src 'self' ws://127.0.0.1:* wss://127.0.0.1:*; " +
    "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'X-Frame-Options': 'DENY',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
};
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
};

// ---------------------------------------------------------------- tls + auth

const CERT_DIR = path.join(__dirname, 'certs');
let TLS = null;
let FINGERPRINT = '';

function pemToDer(pem) {
  const b64 = pem.toString().replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  return Buffer.from(b64, 'base64');
}

/** Resolve Cursor CLI agent binary for node-pty (Windows .cmd / PATH). */
function resolveAgentCommand() {
  if (CONFIG.agentCommand && CONFIG.agentCommand !== 'agent') return CONFIG.agentCommand;
  if (process.platform === 'win32') {
    const candidates = [
      path.join(process.env.LOCALAPPDATA || '', 'cursor-agent', 'agent.cmd'),
      path.join(process.env.LOCALAPPDATA || '', 'cursor-agent', 'cursor-agent.cmd'),
    ];
    for (const c of candidates) if (c && fs.existsSync(c)) return c;
  }
  return CONFIG.agentCommand || 'agent';
}

// Доверенные устройства. Код сопряжения новый на каждый запуск.
const PAIR_FILE = path.join(__dirname, 'pairings.json');
let pairings = { devices: [] };
try { pairings = JSON.parse(fs.readFileSync(PAIR_FILE, 'utf8')); } catch { /* первый запуск */ }
// pairings.json содержит токены устройств открытым текстом — файл только для
// владельца (0600). Чиним права и на уже существующем файле (мог быть 0644).
try { if (fs.existsSync(PAIR_FILE)) fs.chmodSync(PAIR_FILE, 0o600); } catch {}
// Код сопряжения живёт 10 минут (новый — при старте или по cursor-mobile --qr).
// Защита от перебора: 5 неверных попыток -> блокировка /pair на 15 минут.
const PAIR_TTL_MS = 10 * 60 * 1000;
let pairState = { code: '', expiresAt: 0 };
// Блокировка перебора кода — ПОИСТОЧНИКУ (по IP), а не глобально: иначе 5
// неверных попыток с одного адреса заблокировали бы сопряжение для всех.
const pairGuard = new Map(); // ip -> { fails, blockedUntil }
// Глобальный (по всем адресам) счётчик неверных попыток для ТЕКУЩЕГО кода.
// Закрывает обход per-IP-throttle через много источников (в т.ч. 127.x у
// процесса другого UID): даже с новых адресов перебор упирается в общий предел.
// Сбрасывается только при генерации нового кода (F11/F14).
let pairFailTotal = 0;
const PAIR_FAIL_TOTAL_MAX = 50;
function regenPairCode() {
  pairFailTotal = 0;
  pairState = {
    // 5 байт = 40 бит энтропии (было 24): вместе с общим лимитом попыток
    // делает перебор за 10-минутное окно неосуществимым (F14)
    code: crypto.randomBytes(5).toString('hex').toUpperCase(),
    expiresAt: Date.now() + PAIR_TTL_MS,
  };
  return pairState;
}
regenPairCode();

// Публичный id устройства — хэш токена: по нему можно ссылаться, не раскрывая токен
function deviceIdOf(token) {
  return crypto.createHash('sha256').update(token).digest('hex').slice(0, 12);
}
for (const d of pairings.devices) if (!d.id) d.id = deviceIdOf(d.token);

/** Один телефон часто переспаривают → 5× «Nothing A063». Оставляем самый новый на nodeId/имя. */
function pruneDuplicateDevices() {
  const best = new Map();
  for (const d of pairings.devices) {
    const key = d.nodeId ? `node:${d.nodeId}` : `name:${(d.name || '').toLowerCase()}`;
    const prev = best.get(key);
    if (!prev || String(d.addedAt || '') >= String(prev.addedAt || '')) best.set(key, d);
  }
  const keepIds = new Set([...best.values()].map((d) => d.id));
  const before = pairings.devices.length;
  if (keepIds.size >= before) return 0;
  pairings.devices = pairings.devices.filter((d) => keepIds.has(d.id));
  savePairings();
  writeAllowFile();
  console.log(`  Дубликаты сопряжений: ${before} → ${pairings.devices.length}`);
  return before - pairings.devices.length;
}
pruneDuplicateDevices();

function savePairings() {
  // 0600: токены устройств не должны быть доступны другим пользователям машины
  fs.writeFileSync(PAIR_FILE, JSON.stringify(pairings, null, 2), { mode: 0o600 });
  try { fs.chmodSync(PAIR_FILE, 0o600); } catch {}
}

// UID процесса-владельца моста. «Локальным» доверяем только соединениям того
// же пользователя, а не любому процессу на loopback (иначе другой UID / песочный
// сервис мог бы без токена получить права 'local' и выполнить произвольную команду).
const SELF_UID = typeof process.getuid === 'function' ? process.getuid() : -1;

// "0100007F" (порядок байтов ядра, little-endian) -> "127.0.0.1"
function procIpv4(hex) {
  if (!/^[0-9A-Fa-f]{8}$/.test(hex)) return null;
  return [hex.slice(6, 8), hex.slice(4, 6), hex.slice(2, 4), hex.slice(0, 2)]
    .map((h) => parseInt(h, 16)).join('.');
}
// нормализуем адрес сокета к IPv4-виду (снимаем префикс v4-mapped ::ffff:)
function norm4(ip) {
  if (!ip) return null;
  if (ip.startsWith('::ffff:')) ip = ip.slice(7);
  return /^\d+\.\d+\.\d+\.\d+$/.test(ip) ? ip : null;
}
/**
 * UID процесса на другом конце loopback-соединения (Linux, /proc/net/tcp).
 * Сравниваем ПОЛНЫЙ 4-кортеж (оба IP и оба порта) и требуем состояние
 * ESTABLISHED (01). Матч только по портам был бы неоднозначен: процесс другого
 * UID мог бы открыть 127.0.0.1:E -> 127.0.0.2:8790 (сервер слушает 0.0.0.0) с
 * тем же эфемерным портом E, что и соединение владельца к 127.0.0.1:8790, и
 * подсунуть чужой UID из первой попавшейся строки. Полное сравнение адресов
 * изолирует именно наш сокет; ESTABLISHED отсекает TIME_WAIT-призраки.
 * Возвращает -1, если определить не удалось (isLocal тогда fail-closed).
 */
function peerUid(socket) {
  try {
    const rip = norm4(socket.remoteAddress); // локальный адрес клиента = наш remote
    const lip = norm4(socket.localAddress);  // адрес назначения клиента = наш local
    const rport = socket.remotePort, lport = socket.localPort;
    // только IPv4 (сервер слушает 0.0.0.0); для IPv6-bind нужен /proc/net/tcp6
    if (!rip || !lip || !(rport > 0) || !(lport > 0)) return -1;
    let data;
    try { data = fs.readFileSync('/proc/net/tcp', 'utf8'); } catch { return -1; }
    const lines = data.split('\n');
    for (let i = 1; i < lines.length; i++) {
      const p = lines[i].trim().split(/\s+/);
      if (p.length < 8 || p[3] !== '01') continue; // только ESTABLISHED
      const lc = p[1].split(':'), rc = p[2].split(':');
      if (procIpv4(lc[0]) === rip && parseInt(lc[1], 16) === rport &&
          procIpv4(rc[0]) === lip && parseInt(rc[1], 16) === lport) {
        const uid = parseInt(p[7], 10);
        if (Number.isInteger(uid)) return uid;
      }
    }
  } catch {}
  return -1;
}

function isLocal(req) {
  const a = req.socket.remoteAddress;
  const loopback = a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
  // трафик из интернет-туннеля тоже приходит с loopback, но на отдельный порт:
  // локальное доверие — только для основного порта (cursor-mobile, hooks)
  if (!loopback || req.socket.localPort !== CONFIG.port) return false;
  // Windows / нет getuid: loopback на основном порту = локальный клиент.
  // Linux: только процессы того же UID (через /proc/net/tcp).
  if (process.platform === 'win32' || SELF_UID < 0) return true;
  return peerUid(req.socket) === SELF_UID;
}
function requestToken(req, url) {
  const q = url.searchParams.get('token');
  if (q) return q;
  const m = (req.headers.cookie || '').match(/(?:^|;\s*)bridgeToken=([a-f0-9]+)/);
  return m ? m[1] : null;
}
// Сравнение секретов постоянного времени (защита от тайминг-атак по токену/коду).
function safeEqStr(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}
/** Найти устройство по токену за постоянное время (без ранней утечки по '==='). */
function deviceByToken(t) {
  if (!t) return null;
  for (const d of pairings.devices) if (safeEqStr(d.token, t)) return d;
  return null;
}
function authed(req, url) {
  if (isLocal(req)) return true; // сам ПК всегда доверен
  return !!deviceByToken(requestToken(req, url));
}
/** Отозвать устройство и немедленно разорвать его соединения. */
function revokeDeviceById(id) {
  const idx = pairings.devices.findIndex((d) => d.id === id);
  if (idx < 0) return false;
  const name = pairings.devices[idx].name;
  pairings.devices.splice(idx, 1);
  savePairings();
  writeAllowFile(); // отозванный NodeId больше не в allowlist туннеля
  console.log(`\n  Устройство отозвано: ${name}`);
  for (const c of allClients) {
    if (c.deviceId === id) { try { c.close(4401, 'revoked'); } catch { } }
  }
  // Живое iroh-соединение отозванного узла не рвётся автоматически (allowlist
  // проверяется при установке): перезапускаем туннель, чтобы сбросить все p2p-
  // соединения — легитимные устройства тут же переподключатся уже по новому
  // allowlist. Плюс сайдкар перепроверяет allowlist на каждый новый поток.
  if (tunnel && inet.enabled) { stopTunnel(); startTunnel(); }
  return true;
}

// ---------------------------------------------------------------- sessions
// Каждая сессия живёт в отдельном процессе-хранителе (keeper.js) и общается
// с мостом через unix-сокет: перезапуск моста не убивает Cursor Agent.

const SESS_DIR = path.join(os.homedir(), '.cursor-bridge', 'sessions');
fs.mkdirSync(SESS_DIR, { recursive: true, mode: 0o700 });
// mkdir с mode подвержен umask, а на уже существующем каталоге mode не меняет:
// сокеты хранителей — единственная защита от захвата чужой сессии тем же
// пользователем, поэтому жёстко чиним 0700 (доступ только владельцу).
try { fs.chmodSync(SESS_DIR, 0o700); } catch {}

const sessions = new Map(); // id -> session
let seq = 0;

// Защита от исчерпания ресурсов: потолок живых сессий и частоты их создания
// (каждая сессия — отдельный процесс-хранитель + PTY-ребёнок).
const MAX_SESSIONS = CONFIG.maxSessions || 24;
const createTimes = new Map(); // deviceId -> [timestamps]
function rateAllowCreate(deviceId) {
  const now = Date.now();
  const key = deviceId || 'anon';
  const arr = (createTimes.get(key) || []).filter((t) => now - t < 60000);
  createTimes.set(key, arr);
  if (arr.length >= 10) return false; // не более 10 создания сессий в минуту
  arr.push(now);
  return true;
}

function makeSession(meta) {
  return {
    id: meta.id,
    title: meta.title,
    cwd: meta.cwd,
    command: meta.cmd,
    pid: null,
    createdAt: meta.createdAt,
    sock: null,
    ipcPort: meta.ipcPort || null,
    buffer: [],
    bufferBytes: 0,
    clients: new Set(),
    alive: true,
    lastNotify: null,
    status: 'idle',
    cols: meta.cols,
    rows: meta.rows,
    lastInput: null,
  };
}

function allocIpcPort() {
  return new Promise((resolve, reject) => {
    const tmp = net.createServer();
    tmp.once('error', reject);
    tmp.listen(0, '127.0.0.1', () => {
      const { port } = tmp.address();
      tmp.close((err) => (err ? reject(err) : resolve(port)));
    });
  });
}

function keeperSend(session, obj) {
  if (session.sock) { try { session.sock.write(JSON.stringify(obj) + '\n'); } catch {} }
}

function dropSession(session, exitCode) {
  if (!sessions.has(session.id)) return;
  session.alive = false;
  broadcast({ type: 'exit', id: session.id, exitCode: exitCode == null ? -1 : exitCode });
  sessions.delete(session.id);
  broadcastSessions();
}

function connectKeeper(session, retries) {
  if (!session.ipcPort) {
    dropSession(session, -1);
    return;
  }
  const sock = net.connect({ host: '127.0.0.1', port: session.ipcPort });
  let acc = '';
  sock.on('connect', () => { session.sock = sock; });
  sock.on('data', (chunk) => {
    acc += chunk.toString('utf8');
    let i;
    while ((i = acc.indexOf('\n')) >= 0) {
      const line = acc.slice(0, i);
      acc = acc.slice(i + 1);
      if (!line.trim()) continue;
      let m;
      try { m = JSON.parse(line); } catch { continue; }
      if (m.t === 'hello') {
        session.pid = m.pid;
        session.cols = m.cols;
        session.rows = m.rows;
        session.buffer = [m.buffer];
        session.bufferBytes = Buffer.byteLength(m.buffer);
        if (m.buffer) {
          const shot = JSON.stringify({
            type: 'scrollback', id: session.id, data: m.buffer,
            alive: session.alive, cols: session.cols, rows: session.rows,
          });
          for (const ws of session.clients) if (ws.readyState === 1) ws.send(shot);
        }
        broadcastSessions();
      } else if (m.t === 'out') {
        session.buffer.push(m.d);
        session.bufferBytes += Buffer.byteLength(m.d);
        while (session.bufferBytes > (CONFIG.scrollbackBytes || 262144) && session.buffer.length > 1) {
          session.bufferBytes -= Buffer.byteLength(session.buffer.shift());
        }
        const msg = JSON.stringify({ type: 'output', id: session.id, data: m.d });
        for (const ws of session.clients) if (ws.readyState === 1) ws.send(msg);
      } else if (m.t === 'exit') {
        dropSession(session, m.code);
      }
    }
  });
  sock.on('error', () => {
    if (!session.sock && retries > 0) {
      setTimeout(() => connectKeeper(session, retries - 1), 300);
    } else if (!session.sock) {
      try { fs.unlinkSync(path.join(SESS_DIR, session.id + '.json')); } catch {}
      try { fs.unlinkSync(path.join(SESS_DIR, session.id + '.ready')); } catch {}
      console.log(`  Сессия ${session.id}: хранитель не отвечает — файлы убраны`);
      dropSession(session, -1);
    }
  });
  sock.on('close', () => {
    if (session.sock === sock) { session.sock = null; dropSession(session, -1); }
  });
}

// Рабочий каталог удалённой (телефонной) сессии обязан лежать внутри
// разрешённых проектов — иначе телефон мог бы запустить claude в произвольном
// месте ФС. Локальные клиенты (cursor-mobile) не ограничены.
function isUnderAllowedRoot(dir) {
  const resolved = path.resolve(dir);
  return (CONFIG.projects || []).some((r) => {
    const rr = path.resolve(r);
    return resolved === rr || resolved.startsWith(rr + path.sep);
  });
}
function remoteCwd(cwd) {
  const fallback = CONFIG.defaultCwd || os.homedir();
  if (!cwd) return fallback;
  // realpath снимает симлинки: иначе ссылка ВНУТРИ разрешённого проекта
  // (напр. /home/user/projects/link -> /etc) прошла бы лексическую проверку и
  // claude стартовал бы вне проектов. Несуществующий путь -> fallback.
  let real;
  try { real = fs.realpathSync(cwd); } catch { return fallback; }
  return isUnderAllowedRoot(real) ? real : fallback;
}

async function createSession({ cwd, command, args, title, local }) {
  const id = 's' + (++seq) + '-' + crypto.randomBytes(3).toString('hex');
  const cmd = (local && command) ? command : resolveAgentCommand();
  const cmdArgs = (args && args.length) ? args : (CONFIG.agentArgs || []);
  const dir = local ? (cwd || CONFIG.defaultCwd || os.homedir()) : remoteCwd(cwd);
  const ipcPort = await allocIpcPort();

  const meta = {
    id,
    cmd,
    args: cmdArgs,
    cwd: dir,
    title: title || `${path.basename(dir)} · agent`,
    createdAt: Date.now(),
    cols: 100,
    rows: 30,
    scrollbackBytes: CONFIG.scrollbackBytes || 262144,
    ipcPort,
  };
  const childEnv = {
    ...process.env,
    ...CONFIG.env,
    CURSOR_BRIDGE_SESSION: id,
    CURSOR_BRIDGE_PORT: String(CONFIG.port),
    FORCE_COLOR: '3',
  };
  const metaPath = path.join(SESS_DIR, id + '.json');
  fs.writeFileSync(metaPath, JSON.stringify(meta));

  const child = spawn(process.execPath, [path.join(__dirname, 'keeper.js'), metaPath], {
    detached: true,
    stdio: 'ignore',
    env: childEnv,
    windowsHide: true,
  });
  child.unref();

  const session = makeSession(meta);
  sessions.set(id, session);
  connectKeeper(session, 20);
  broadcastSessions();
  return session;
}

function restoreSessions() {
  let restored = 0;
  for (const f of fs.readdirSync(SESS_DIR)) {
    if (!f.endsWith('.json')) continue;
    const metaPath = path.join(SESS_DIR, f);
    try {
      const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
      if (!meta.ipcPort) {
        fs.unlinkSync(metaPath);
        continue;
      }
      const n = parseInt(String(meta.id).slice(1), 10);
      if (n > seq) seq = n;
      const session = makeSession(meta);
      sessions.set(meta.id, session);
      connectKeeper(session, 3);
      restored++;
    } catch { /* битый meta-файл — пропускаем */ }
  }
  if (restored) console.log(`  Восстановлено живых сессий: ${restored}`);
}

// Политика размера PTY: «размер следует за тем, кто печатает».
// Подключение зрителя размер не меняет.
function resizePty(session, cols, rows) {
  if (!session.alive || !(cols > 0) || !(rows > 0)) return;
  if (session.cols === cols && session.rows === rows) return;
  keeperSend(session, { t: 'resize', cols, rows });
  session.cols = cols;
  session.rows = rows;
  const msg = JSON.stringify({ type: 'resized', id: session.id, cols, rows });
  for (const ws of session.clients) if (ws.readyState === 1) ws.send(msg);
}

function sessionList() {
  return [...sessions.values()].map((s) => ({
    id: s.id,
    title: s.title,
    cwd: s.cwd,
    pid: s.pid,
    alive: s.alive,
    status: s.status,
    createdAt: s.createdAt,
    lastNotify: s.lastNotify,
  }));
}

// ---------------------------------------------------------------- stt
// Постоянный python-воркер с faster-whisper: модель грузится один раз.

let sttProc = null;
let sttQueue = [];

function ensureStt() {
  if (sttProc) return;
  sttProc = spawn(CONFIG.sttPython || (process.platform === 'win32' ? 'python' : 'python3'), [path.join(__dirname, 'stt.py')], {
    env: {
      ...process.env,
      STT_MODEL: CONFIG.sttModel || 'large-v3',
      ...(CONFIG.sttPrompt ? { STT_PROMPT: CONFIG.sttPrompt } : {}),
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let acc = '';
  sttProc.stdout.on('data', (d) => {
    acc += d.toString('utf8');
    let i;
    while ((i = acc.indexOf('\n')) >= 0) {
      const line = acc.slice(0, i);
      acc = acc.slice(i + 1);
      if (!line.trim()) continue;
      let m;
      try { m = JSON.parse(line); } catch { continue; }
      if (m.ready) { console.log('  STT-модель загружена'); continue; }
      const job = sttQueue.shift();
      if (job) (m.error ? job.reject(new Error(m.error)) : job.resolve(m.text || ''));
    }
  });
  sttProc.stderr.on('data', (d) => process.stderr.write('[stt] ' + d));
  sttProc.on('exit', () => {
    sttProc = null;
    for (const j of sttQueue) j.reject(new Error('STT-воркер завершился'));
    sttQueue = [];
  });
}

function transcribe(file) {
  ensureStt();
  return new Promise((resolve, reject) => {
    sttQueue.push({ resolve, reject });
    sttProc.stdin.write(JSON.stringify({ file, language: CONFIG.sttLanguage || 'ru' }) + '\n');
  });
}

// ---------------------------------------------------------------- internet (p2p iroh)
// Опциональный доступ через интернет: сайдкар cursor-tunnel (Rust + iroh)
// принимает p2p-соединения и пробрасывает их на локальный порт туннеля.
// Включается только явно: cursor-mobile --device -> «i».

const INET_FILE = path.join(__dirname, 'internet.json');
const TUNNEL_BIN = (() => {
  const base = path.join(__dirname, 'bin', 'cursor-tunnel');
  if (process.platform === 'win32') {
    const exe = base + '.exe';
    if (fs.existsSync(exe)) return exe;
  }
  return base;
})();
const TUNNEL_PORT = CONFIG.port + 2; // локальный порт для трафика из туннеля
// allowlist iroh-узлов: телефон регистрирует свой NodeId (по LAN, через
// защищённый WS), сюда пишутся id всех сопряжённых устройств. Туннель
// перечитывает файл на каждое соединение и пускает только известные узлы.
const ALLOW_FILE = path.join(CERT_DIR, 'tunnel-allow.txt');

/** PIDs, которые держат локальный порт (TCP LISTENING / UDP). Текущий процесс пропускаем. */
function pidsOnPort(port) {
  const mine = process.pid;
  const pids = new Set();
  if (process.platform === 'win32') {
    const out = spawnSync('netstat', ['-ano'], { encoding: 'utf8', windowsHide: true });
    for (const line of (out.stdout || '').split(/\r?\n/)) {
      const parts = line.trim().split(/\s+/);
      if (parts.length < 4) continue;
      const proto = (parts[0] || '').toUpperCase();
      if (proto !== 'TCP' && proto !== 'UDP') continue;
      const local = parts[1] || '';
      if (!local.endsWith(':' + port)) continue;
      if (proto === 'TCP') {
        // TCP local foreign state pid
        if (parts.length < 5 || parts[parts.length - 2] !== 'LISTENING') continue;
        const pid = parseInt(parts[parts.length - 1], 10);
        if (pid > 0 && pid !== mine) pids.add(pid);
      } else {
        // UDP local *:* pid
        const pid = parseInt(parts[parts.length - 1], 10);
        if (pid > 0 && pid !== mine) pids.add(pid);
      }
    }
  } else {
    const out = spawnSync('lsof', ['-t', `-i:${port}`, '-sTCP:LISTEN'], {
      encoding: 'utf8',
    });
    for (const tok of (out.stdout || '').trim().split(/\s+/)) {
      const pid = parseInt(tok, 10);
      if (pid > 0 && pid !== mine) pids.add(pid);
    }
    // UDP beacon
    const udp = spawnSync('lsof', ['-t', `-iUDP:${port}`], { encoding: 'utf8' });
    for (const tok of (udp.stdout || '').trim().split(/\s+/)) {
      const pid = parseInt(tok, 10);
      if (pid > 0 && pid !== mine) pids.add(pid);
    }
  }
  return [...pids];
}

/** Перед bind: убить прошлый мост на тех же портах (иначе EADDRINUSE). */
function freeBridgePorts() {
  const ports = [CONFIG.port, CONFIG.port + 1, TUNNEL_PORT];
  const killed = new Set();
  for (const port of ports) {
    for (const pid of pidsOnPort(port)) {
      if (killed.has(pid)) continue;
      try {
        if (process.platform === 'win32') {
          spawnSync('taskkill', ['/F', '/PID', String(pid)], { stdio: 'ignore', windowsHide: true });
        } else {
          process.kill(pid, 'SIGTERM');
        }
        killed.add(pid);
        console.log(`  Освободил порт: убит PID ${pid} (прошлый мост)`);
      } catch (e) {
        console.error(`  Не удалось убить PID ${pid}:`, e.message);
      }
    }
  }
  if (killed.size) {
    // дать ОС отпустить сокеты
    try { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500); } catch {}
  }
}

function writeAllowFile() {
  const ids = pairings.devices
    .map((d) => (d.nodeId || '').trim().toLowerCase())
    .filter((x) => /^[0-9a-f]{64}$/.test(x));
  try { fs.writeFileSync(ALLOW_FILE, ids.join('\n') + (ids.length ? '\n' : ''), { mode: 0o600 }); } catch {}
}

let inet = { enabled: false };
try { inet = { ...inet, ...JSON.parse(fs.readFileSync(INET_FILE, 'utf8')) }; } catch { /* дефолт */ }

let tunnel = null; // процесс cursor-tunnel
let tunnelInfo = { ticket: null, nodeId: null };
const tunnelPeers = new Map(); // id соединения -> { remote, path, addr, rttMs }
let tunnelRestartTimer = null;

function internetMsg() {
  return {
    type: 'internet',
    enabled: inet.enabled,
    available: fs.existsSync(TUNNEL_BIN),
    ticket: tunnelInfo.ticket,
    nodeId: tunnelInfo.nodeId,
    endpoints: bridgeEndpoints(),
    peers: [...tunnelPeers.values()],
  };
}
function broadcastInternet() { broadcast(internetMsg()); }

function startTunnel() {
  if (tunnel || !inet.enabled) return;
  if (!fs.existsSync(TUNNEL_BIN)) {
    console.error('  Интернет-доступ включён, но bridge/bin/cursor-tunnel не собран');
    return;
  }
  // бесхозные туннели прошлых запусков (у них тот же ключ/NodeId) — убрать
  try {
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/F', '/IM', 'cursor-tunnel.exe'], { stdio: 'ignore', windowsHide: true });
    } else {
      spawnSync('pkill', ['-f', TUNNEL_BIN + ' listen'], { stdio: 'ignore' });
    }
  } catch { /* нет прав/нет процессов */ }
  writeAllowFile(); // актуальный allowlist к моменту старта туннеля
  const child = spawn(TUNNEL_BIN, [
    'listen',
    '--target', '127.0.0.1:' + TUNNEL_PORT,
    '--secret-file', path.join(CERT_DIR, 'iroh.key'),
    '--allow-file', ALLOW_FILE,
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  tunnel = child;
  let acc = '';
  child.stdout.on('data', (d) => {
    acc += d.toString('utf8');
    let i;
    while ((i = acc.indexOf('\n')) >= 0) {
      const line = acc.slice(0, i);
      acc = acc.slice(i + 1);
      if (!line.trim()) continue;
      let m;
      try { m = JSON.parse(line); } catch { continue; }
      if (m.event === 'ready') {
        tunnelInfo = { ticket: m.ticket, nodeId: m.node_id };
        console.log('  Интернет-доступ (iroh) активен, node: ' + (m.node_id || '').slice(0, 12) + '…');
      } else if (m.event === 'conn') {
        tunnelPeers.set(m.id, { remote: m.remote, path: null, addr: null, rttMs: null });
      } else if (m.event === 'path') {
        const peer = tunnelPeers.get(m.id) || {};
        tunnelPeers.set(m.id, { ...peer, path: m.path, addr: m.addr, rttMs: m.rtt_ms });
      } else if (m.event === 'close') {
        tunnelPeers.delete(m.id);
      }
      broadcastInternet();
    }
  });
  child.stderr.on('data', () => { /* отладка сайдкара не нужна в логе моста */ });
  child.on('exit', () => {
    if (tunnel !== child) return;
    tunnel = null;
    tunnelInfo = { ticket: null, nodeId: null };
    tunnelPeers.clear();
    broadcastInternet();
    if (inet.enabled) tunnelRestartTimer = setTimeout(startTunnel, 3000);
  });
}

function stopTunnel() {
  clearTimeout(tunnelRestartTimer);
  if (tunnel) { try { tunnel.kill(); } catch { } tunnel = null; }
  tunnelInfo = { ticket: null, nodeId: null };
  tunnelPeers.clear();
  broadcastInternet();
}

function setInternet(enabled) {
  inet.enabled = !!enabled;
  fs.writeFileSync(INET_FILE, JSON.stringify(inet, null, 2));
  console.log('\n  Доступ через интернет: ' + (inet.enabled ? 'ВКЛЮЧЁН' : 'выключен'));
  if (inet.enabled) startTunnel(); else stopTunnel();
}

// ---------------------------------------------------------------- http

function httpHandler(req, res) {
  const url = new URL(req.url, 'http://x');

  // Сопряжение нового устройства: одноразовый код -> постоянный токен
  if (req.method === 'POST' && url.pathname === '/pair') {
    // LAN или туннель (LTE-first): код всё ещё секрет; без кода /pair мёртв.
    const localPort = req.socket.localPort;
    if (localPort !== CONFIG.port && localPort !== TUNNEL_PORT) {
      res.writeHead(404); res.end(); return;
    }
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', () => {
      let p = {};
      try { p = JSON.parse(body || '{}'); } catch {}
      const ip = req.socket.remoteAddress || 'unknown';
      // Бэкстоп роста карты: удаляем ТОЛЬКО адреса без активной блокировки —
      // иначе атакующий сбрасывал бы свою блокировку, раздув карту (F11).
      if (pairGuard.size > 4096) {
        const now = Date.now();
        for (const [k, v] of pairGuard) if (v.blockedUntil < now) pairGuard.delete(k);
      }
      // Общий предел неверных попыток для текущего кода (перебор с многих
      // адресов упирается сюда; сбрасывается только новым кодом) (F11/F14).
      if (pairFailTotal >= PAIR_FAIL_TOTAL_MAX) {
        res.writeHead(429, { 'Content-Type': 'application/json' });
        res.end('{"error":"слишком много неверных попыток — обновите код: cursor-mobile --qr"}');
        return;
      }
      const g = pairGuard.get(ip) || { fails: 0, blockedUntil: 0 };
      if (Date.now() < g.blockedUntil) {
        res.writeHead(429, { 'Content-Type': 'application/json' });
        res.end('{"error":"слишком много попыток — сопряжение с этого адреса заблокировано на 15 минут"}');
        return;
      }
      if (Date.now() > pairState.expiresAt) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end('{"error":"код истёк — выполните cursor-mobile --qr на ПК"}');
        return;
      }
      if (!safeEqStr((p.code || '').toUpperCase(), pairState.code)) {
        g.fails++;
        pairFailTotal++; // общий счётчик для текущего кода (F11)
        if (g.fails >= 5) {
          g.blockedUntil = Date.now() + 15 * 60 * 1000;
          g.fails = 0;
          console.log('\n  Сопряжение с ' + ip + ' заблокировано на 15 минут (перебор кода)');
        }
        pairGuard.set(ip, g);
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end('{"error":"неверный код сопряжения"}');
        return;
      }
      pairGuard.delete(ip);
      // Код одноразовый: гасим его сразу, чтобы одним подсмотренным кодом нельзя
      // было сопрячь несколько устройств (и повторно — после отзыва) за 10 минут.
      pairState.expiresAt = 0;
      const token = crypto.randomBytes(24).toString('hex');
      // Имя устройства приходит от клиента: вырезаем управляющие символы
      // (ANSI/escape), иначе они попали бы и в консоль оператора (подмена вывода,
      // очистка экрана), и в UI телефона (F13). Одно значение — для хранения и лога.
      const safeName = (String(p.name || '')
        .replace(/[\x00-\x1f\x7f-\x9f]/g, '')
        .slice(0, 64)) || 'устройство';
      pairings.devices.push({
        id: deviceIdOf(token),
        token,
        name: safeName,
        addedAt: new Date().toISOString(),
      });
      savePairings();
      console.log(`\n  Сопряжено новое устройство: ${safeName}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        token, fingerprint: FINGERPRINT, port: CONFIG.port, hostname: os.hostname(),
        nodeTicket: tunnelInfo.ticket, internet: inet.enabled,
        endpoints: bridgeEndpoints(),
      }));
    });
    return;
  }

  // Диагностика с телефона → файл на ПК (читает оператор / агент)
  if (req.method === 'POST' && url.pathname === '/diag') {
    if (!authed(req, url)) { res.writeHead(401); res.end(); return; }
    const chunks = [];
    let size = 0;
    let overflow = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > 512e3 && !overflow) {
        overflow = true;
        try { res.writeHead(413); res.end(); } catch {}
        req.destroy();
        return;
      }
      if (!overflow) chunks.push(c);
    });
    req.on('error', () => {});
    req.on('end', () => {
      if (overflow) return;
      const raw = Buffer.concat(chunks).toString('utf8');
      let payload = {};
      try { payload = JSON.parse(raw || '{}'); } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end('{"error":"bad json"}');
        return;
      }
      const diagDir = path.join(__dirname, 'logs');
      try { fs.mkdirSync(diagDir, { recursive: true, mode: 0o700 }); } catch {}
      const line = JSON.stringify({
        at: new Date().toISOString(),
        from: req.socket.remoteAddress || null,
        device: (payload.deviceName || payload.device || '').toString().slice(0, 80),
        reason: (payload.reason || 'manual').toString().slice(0, 120),
        payload,
      }) + '\n';
      const file = path.join(diagDir, 'phone-diag.jsonl');
      try {
        fs.appendFileSync(file, line, { mode: 0o600 });
        // укороченный хвост для быстрого взгляда
        fs.writeFileSync(path.join(diagDir, 'phone-diag-latest.json'), JSON.stringify(payload, null, 2), { mode: 0o600 });
      } catch (e) {
        console.error('[diag] write failed:', e.message);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'write failed' }));
        return;
      }
      console.log(`\n  [diag] от телефона: ${payload.reason || 'manual'} → bridge/logs/phone-diag.jsonl`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    });
    return;
  }

  // Распознавание надиктованного аудио (телефон шлёт m4a, отвечаем текстом)
  if (req.method === 'POST' && url.pathname === '/stt') {
    if (!authed(req, url)) { res.writeHead(401); res.end(); return; }
    // Ограничение очереди: один воркер обрабатывает записи по одной — не даём
    // авторизованному клиенту забить память/очередь потоком загрузок.
    if (sttQueue.length >= (CONFIG.sttMaxQueue || 4)) {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end('{"error":"очередь распознавания переполнена, повторите позже"}');
      return;
    }
    const tmp = path.join(os.tmpdir(), 'cb-stt-' + crypto.randomBytes(6).toString('hex') + '.m4a');
    const out = fs.createWriteStream(tmp, { mode: 0o600 });
    const cleanup = () => { try { fs.unlinkSync(tmp); } catch {} };
    let size = 0;
    let overflow = false;
    // Пишем загрузку сразу на диск, а не в память: 20 МБ × N параллельных
    // запросов в RAM — вектор исчерпания памяти.
    req.on('data', (c) => {
      size += c.length;
      if (size > 20e6 && !overflow) {
        overflow = true;
        try { res.writeHead(413); res.end(); } catch {}
        req.destroy(); out.destroy(); cleanup();
      }
    });
    // обрыв загрузки клиентом: transcribe не запустится ('finish' не наступит),
    // поэтому временный файл надо удалить здесь, иначе он утечёт на диск
    req.on('error', () => { try { out.destroy(); } catch {} cleanup(); });
    out.on('error', () => { cleanup(); try { res.writeHead(500); res.end(); } catch {} });
    req.pipe(out);
    out.on('finish', async () => {
      if (overflow) return; // ответ уже отправлен, файл удалён
      try {
        const text = await Promise.race([
          transcribe(tmp),
          new Promise((_, rej) => setTimeout(() => rej(new Error('таймаут распознавания')), 180000)),
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ text }));
      } catch (e) {
        // деталь — только в лог ПК; клиенту общий текст (без внутренних путей)
        console.error('[stt] ошибка распознавания:', e.message);
        try { res.writeHead(500, { 'Content-Type': 'application/json' }); } catch {}
        res.end(JSON.stringify({ error: 'не удалось распознать запись' }));
      } finally {
        cleanup();
      }
    });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/hook') {
    if (!isLocal(req)) { res.writeHead(403); res.end(); return; }
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 1e6) req.destroy(); });
    req.on('end', () => {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch { /* keep {} */ }
      const sid = req.headers['x-bridge-session'] || payload.bridge_session || '';
      const event = payload.hook_event_name || 'Notification';
      const message = payload.message || payload.notification || (event === 'Stop' ? 'Agent завершил ответ' : 'Событие Cursor Agent');
      const s = sessions.get(sid);
      // статус сессии для вкладок: работает / ждёт ответа / свободна.
      const status = {
        UserPromptSubmit: 'working',
        PostToolUse: 'working',
        Notification: 'waiting',
        Stop: 'idle',
        // Cursor hook names (если хук не смапил)
        beforeSubmitPrompt: 'working',
        afterAgentResponse: 'working',
        stop: 'idle',
        sessionEnd: 'idle',
        beforeShellExecution: 'waiting',
        beforeMCPExecution: 'waiting',
      }[event];
      // отдельное событие для телефона: по 'working' он гасит устаревшие
      // уведомления — в том числе для сессий, которых уже нет в списке
      if (status && sid && (!s || s.status !== status)) {
        broadcast({ type: 'status', id: sid, status });
      }
      if (s && status && s.status !== status) {
        s.status = status;
        broadcastSessions();
      }
      // push-уведомления — только для событий, требующих внимания
      if (event === 'Notification' || event === 'Stop') {
        if (s) s.lastNotify = { event, message, at: Date.now() };
        broadcast({ type: 'notify', id: sid, event, message, title: s ? s.title : sid, at: Date.now() });
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    });
    return;
  }

  if (url.pathname === '/health') {
    if (!authed(req, url)) { res.writeHead(401); res.end(); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, sessions: sessions.size, host: os.hostname() }));
    return;
  }

  // Локальный запуск тактики на телефоне (LAN): POST /phone-cmd {"cmd":"snapshot"}
  if (req.method === 'POST' && url.pathname === '/phone-cmd') {
    if (!isLocal(req)) { res.writeHead(403); res.end('local only'); return; }
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 32e3) req.destroy(); });
    req.on('end', () => {
      let p = {};
      try { p = JSON.parse(body || '{}'); } catch {}
      const cmd = String(p.cmd || '').slice(0, 64);
      if (!cmd) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end('{"error":"cmd required"}');
        return;
      }
      const id = p.cmdId || crypto.randomBytes(4).toString('hex');
      const sent = dispatchPhoneCmd(cmd, id, p.args || {}, null);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, cmdId: id, ...sent }));
    });
    return;
  }

  // static: vendor libs from node_modules, everything else from web/
  if (!authed(req, url)) {
    res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('401: устройство не сопряжено с мостом');
    return;
  }
  const extraHeaders = {};
  // токен пришёл в query -> выдаём cookie, чтобы css/js/websocket прошли сами
  const qtoken = url.searchParams.get('token');
  if (qtoken) {
    extraHeaders['Set-Cookie'] =
      `bridgeToken=${qtoken}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict`;
  }
  let file;
  if (VENDOR[url.pathname]) {
    file = VENDOR[url.pathname];
  } else {
    const rel = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    file = path.join(WEB_DIR, path.normalize(rel));
    // разделитель обязателен: без него сосед вида .../web-secret прошёл бы
    // проверку startsWith(WEB_DIR) (префиксный баг)
    if (file !== WEB_DIR && !file.startsWith(WEB_DIR + path.sep)) {
      res.writeHead(403); res.end(); return;
    }
  }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      ...SEC_HEADERS,
      ...extraHeaders,
    });
    res.end(data);
  });
}

// Основной сервер (LAN + localhost) и отдельный локальный порт для
// интернет-туннеля: isLocal() различает их по localPort.
let server;
let tunnelServer;

// ---------------------------------------------------------------- websocket

const wss = new WebSocketServer({ noServer: true });
function handleUpgrade(req, sock, head) {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws') { sock.destroy(); return; }
  wss.handleUpgrade(req, sock, head, (ws) => wss.emit('connection', ws, req));
}
const allClients = new Set();
/** deviceId → { name, onlineAt, offlineTimer } — дедуп логов при 2–3 WS и thrash */
const phonePresence = new Map();

function phoneSocketsLive(deviceId) {
  for (const c of allClients) {
    if (c.deviceId === deviceId && c.readyState === 1) return true;
  }
  return false;
}

function logPhoneOnline(ws, via) {
  if (!ws.deviceId || ws.deviceId === 'local') return;
  const prev = phonePresence.get(ws.deviceId);
  if (prev && prev.offlineTimer) {
    clearTimeout(prev.offlineTimer);
    prev.offlineTimer = null;
  }
  const wasOnline = prev && prev.online;
  phonePresence.set(ws.deviceId, {
    name: ws.deviceName || ws.deviceId.slice(0, 8),
    online: true,
    offlineTimer: null,
  });
  if (!wasOnline) {
    console.log(`\n  Телефон онлайн: ${ws.deviceName} (${ws.deviceId.slice(0, 8)}…) via ${via}`);
  }
}

function logPhoneOffline(ws) {
  if (!ws.deviceId || ws.deviceId === 'local') return;
  const id = ws.deviceId;
  const name = ws.deviceName || id.slice(0, 8);
  const prev = phonePresence.get(id) || { name, online: true, offlineTimer: null };
  if (prev.offlineTimer) return; // уже ждём
  prev.online = false;
  prev.offlineTimer = setTimeout(() => {
    prev.offlineTimer = null;
    if (phoneSocketsLive(id)) {
      prev.online = true;
      phonePresence.set(id, prev);
      return;
    }
    phonePresence.delete(id);
    console.log(`\n  Телефон офлайн: ${name} (${id.slice(0, 8)}…)`);
  }, 2000);
  phonePresence.set(id, prev);
}

function broadcast(obj) {
  const msg = JSON.stringify(obj);
  for (const ws of allClients) if (ws.readyState === 1) ws.send(msg);
}
function broadcastSessions() {
  broadcast({ type: 'sessions', sessions: sessionList(), projects: CONFIG.projects || [] });
}
function devicesMsg(ws) {
  return {
    type: 'devices',
    self: ws.deviceId,
    devices: pairings.devices.map((d) => ({ id: d.id, name: d.name, addedAt: d.addedAt })),
  };
}

wss.on('connection', (ws, req) => {
  const connUrl = new URL(req.url, 'http://x');
  if (!authed(req, connUrl)) {
    ws.close(4401, 'unauthorized');
    return;
  }
  const dev = deviceByToken(requestToken(req, connUrl));
  ws.deviceId = dev ? dev.id : (isLocal(req) ? 'local' : null);
  ws.deviceName = dev ? (dev.name || 'телефон') : (ws.deviceId === 'local' ? 'local' : null);
  ws.isAlive = true;
  ws.missedPongs = 0;
  allClients.add(ws);
  ws.attached = null;
  if (ws.deviceId && ws.deviceId !== 'local') {
    const via = req.socket.localPort === TUNNEL_PORT ? 'туннель' : 'LAN';
    logPhoneOnline(ws, via);
  }
  ws.send(JSON.stringify({ type: 'sessions', sessions: sessionList(), projects: CONFIG.projects || [] }));

  ws.on('pong', () => { ws.isAlive = true; ws.missedPongs = 0; });

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    const s = m.id ? sessions.get(m.id) : null;

    switch (m.type) {
      case 'create': {
        if (sessions.size >= MAX_SESSIONS) {
          ws.send(JSON.stringify({ type: 'error', message: 'Достигнут предел числа сессий' }));
          break;
        }
        if (!rateAllowCreate(ws.deviceId)) {
          ws.send(JSON.stringify({ type: 'error', message: 'Слишком часто — подождите немного' }));
          break;
        }
        createSession({
          cwd: m.cwd, command: m.command, args: m.args, title: m.title,
          local: ws.deviceId === 'local',
        }).then((created) => {
          ws.send(JSON.stringify({ type: 'created', id: created.id }));
        }).catch((e) => {
          ws.send(JSON.stringify({ type: 'error', message: e.message || 'не удалось создать сессию' }));
        });
        break;
      }
      case 'attach': {
        if (!s) return;
        if (ws.attached) { const old = sessions.get(ws.attached); if (old) old.clients.delete(ws); }
        ws.attached = s.id;
        s.clients.add(ws);
        if (m.cols && m.rows) ws.desired = { cols: m.cols, rows: m.rows };
        // зритель размер не меняет — только единственный клиент
        if (ws.desired && s.clients.size === 1) resizePty(s, ws.desired.cols, ws.desired.rows);
        ws.send(JSON.stringify({
          type: 'scrollback', id: s.id, data: s.buffer.join(''),
          alive: s.alive, cols: s.cols, rows: s.rows,
        }));
        break;
      }
      case 'input':
        if (s && s.alive) {
          if (s.lastInput !== ws) {
            s.lastInput = ws; // клиент начал печатать — PTY подстраивается под него
            if (ws.desired) resizePty(s, ws.desired.cols, ws.desired.rows);
          }
          keeperSend(s, { t: 'in', d: m.data });
        }
        break;
      case 'scroll':
        // прокрутка — не набор текста: не трогаем «владельца размера» PTY
        if (s && s.alive) keeperSend(s, { t: 'in', d: m.data });
        break;
      case 'resize':
        if (!s) break;
        if (m.cols > 0 && m.rows > 0) ws.desired = { cols: m.cols, rows: m.rows };
        if (ws.desired && (s.clients.size === 1 || s.lastInput === ws)) {
          resizePty(s, ws.desired.cols, ws.desired.rows);
        }
        break;
      case 'kill':
        if (s && s.alive) keeperSend(s, { t: 'kill' });
        break;
      case 'remove':
        if (s && !s.alive) { sessions.delete(s.id); broadcastSessions(); }
        break;
      case 'list':
        ws.send(JSON.stringify({ type: 'sessions', sessions: sessionList(), projects: CONFIG.projects || [] }));
        break;
      case 'devices':
        // список устройств — только локальным клиентам (десктоп-браузер,
        // cursor-mobile); телефон управляет ПК через нативное меню, не этим
        if (ws.deviceId === 'local') ws.send(JSON.stringify(devicesMsg(ws)));
        break;
      case 'internet':
        // статус интернет-доступа: телефон запоминает ticket на будущее
        ws.send(JSON.stringify(internetMsg()));
        break;
      case 'registerNode': {
        // телефон сообщает свой постоянный iroh-NodeId (по LAN, через
        // защищённый WS) — добавляем в allowlist туннеля этого устройства
        const nid = (m.nodeId || '').trim().toLowerCase();
        if (/^[0-9a-f]{64}$/.test(nid) && ws.deviceId && ws.deviceId !== 'local') {
          const dev = pairings.devices.find((d) => d.id === ws.deviceId);
          if (dev && dev.nodeId !== nid) {
            dev.nodeId = nid;
            savePairings();
            writeAllowFile();
            pruneDuplicateDevices();
            console.log(`  Зарегистрирован p2p-узел устройства «${dev.name}»`);
          }
        }
        break;
      }
      case 'setInternet':
        // включение/выключение — только с самого ПК (cursor-mobile --device)
        if (ws.deviceId === 'local') {
          setInternet(m.enabled);
          broadcastInternet();
        }
        break;
      case 'phoneCmd': {
        // удалённые тесты/тактики на телефоне — только с ПК
        if (ws.deviceId !== 'local') break;
        const cmd = String(m.cmd || '').slice(0, 64);
        if (!cmd) break;
        const id = m.cmdId || crypto.randomBytes(4).toString('hex');
        dispatchPhoneCmd(cmd, id, m.args || {}, ws);
        break;
      }
      case 'phoneCmdResult': {
        // ответ телефона — пишем в лог и пересылаем локальным клиентам
        if (!ws.deviceId || ws.deviceId === 'local') break;
        const entry = {
          at: new Date().toISOString(),
          deviceId: ws.deviceId,
          cmdId: m.cmdId || null,
          cmd: m.cmd || null,
          ok: !!m.ok,
          result: m.result || m,
        };
        appendPhoneCmdLog(entry);
        console.log(`\n  [phoneCmd] result from ${ws.deviceId}: ${entry.cmd} ok=${entry.ok}`);
        for (const c of allClients) {
          if (c.deviceId === 'local' && c.readyState === 1) {
            c.send(JSON.stringify({ type: 'phoneCmdResult', ...entry }));
          }
        }
        break;
      }
      case 'pairinfo':
        // данные сопряжения — только локальным клиентам (cursor-mobile --qr);
        // каждый запрос выдаёт свежий код на 10 минут
        if (ws.deviceId === 'local') {
          const p = regenPairCode();
          ws.send(JSON.stringify({
            type: 'pairinfo',
            host: lanAddress(),
            port: CONFIG.port,
            code: p.code,
            ttlMin: Math.round(PAIR_TTL_MS / 60000),
            fp: FINGERPRINT,
            ticket: tunnelInfo.ticket || null,
            nodeId: tunnelInfo.nodeId || null,
            internet: inet.enabled,
            endpoints: bridgeEndpoints(),
          }));
        }
        break;
      case 'revokeDevice':
        // отзыв устройств — только с самого ПК (cursor-mobile --device / десктоп),
        // иначе сопряжённый телефон мог бы отозвать все остальные устройства.
        // Список устройств тоже отдаём ТОЛЬКО локальному клиенту: удалённое
        // устройство иначе прочитало бы id/имена/даты всех остальных (F12).
        if (ws.deviceId === 'local') {
          revokeDeviceById(m.id);
          ws.send(JSON.stringify(devicesMsg(ws)));
        }
        break;
    }
  });

  ws.on('close', () => {
    allClients.delete(ws);
    if (ws.attached) { const s = sessions.get(ws.attached); if (s) s.clients.delete(ws); }
    logPhoneOffline(ws);
  });
});

// WS keepalive: мёртвый half-open (WiFi off / LTE drop) иначе висит минуты без close.
// Ping каждые 5с; 2 пропущенных pong → terminate. Офлайн в лог — с debounce 2с.
const WS_PING_MS = 5000;
const wsHeartbeat = setInterval(() => {
  for (const ws of allClients) {
    if (ws.readyState !== 1) continue;
    if (ws.isAlive === false) {
      ws.missedPongs = (ws.missedPongs || 0) + 1;
      if (ws.missedPongs >= 2) {
        try { ws.terminate(); } catch { }
        continue;
      }
    } else {
      ws.missedPongs = 0;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch { try { ws.terminate(); } catch { } }
  }
}, WS_PING_MS);
wsHeartbeat.unref?.();

// ---------------------------------------------------------------- start

function lanAddress() {
  // Собираем кандидатов и скорим: реальный Wi‑Fi/Ethernet выше VPN/WSL/WARP.
  const skipName = /docker|vethernet|hyper-v|wsl|cloudflare|warp|tailscale|outline|npcap|bluetooth|loopback|virbr|vbox|vmware|radmin|zerotier/i;
  const scored = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (skipName.test(name)) continue;
    for (const a of addrs || []) {
      const fam = a.family;
      if (!(fam === 'IPv4' || fam === 4) || a.internal) continue;
      const ip = a.address;
      if (!ip || ip.startsWith('169.254.')) continue;
      let score = 0;
      if (ip.startsWith('192.168.')) score += 100;
      else if (ip.startsWith('10.')) score += 40;
      else if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(ip)) score += 20;
      else continue; // не RFC1918
      if (/wi-?fi|wlan|wireless|беспровод|wifi/i.test(name)) score += 30;
      else if (/ethernet|ethernet|локальн|ethernet/i.test(name) && !/vethernet/i.test(name)) score += 20;
      scored.push({ ip, score, name });
    }
  }
  scored.sort((a, b) => b.score - a.score);
  if (scored.length) return scored[0].ip;
  return '127.0.0.1';
}

// WSS-эндпоинты моста для телефона, в порядке приоритета:
//   lan — RFC1918 (Wi‑Fi/локальная сеть),
//   v6  — глобальный IPv6 (LTE/интернет без релея, если провайдеры дают v6),
//   ov  — overlay-интерфейсы (Tailscale/Radmin VPN/ZeroTier и пр.) + ручные из config.
// Публикуются в QR, /pair, pairinfo и internetMsg; телефон пробует
// LAN → v6 → overlay → iroh-туннель.
const OVERLAY_IFACE = /tailscale|radmin|zerotier|tun[0-9]|wg[0-9]|wireguard|amnezia|outline/i;
// Виртуальные интерфейсы, которые не дают телефонам полезных адресов:
// WSL/Hyper‑V/Docker/WARP/Bluetooth/Teredo и т.п.
const SKIP_IFACE = /docker|vethernet|hyper-v|wsl|cloudflare|warp|npcap|bluetooth|loopback|virbr|vbox|vmware|teredo|isatap/i;
function bridgeEndpoints() {
  const out = [];
  const seen = new Set();
  const push = (k, ip) => {
    if (!ip || seen.has(ip)) return;
    seen.add(ip);
    out.push({ k, u: ip + ':' + CONFIG.port });
  };
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.internal) continue;
      const fam = a.family;
      const ip = a.address;
      const overlay = OVERLAY_IFACE.test(name);
      if (fam === 'IPv4' || fam === 4) {
        if (ip.startsWith('169.254.')) continue; // APIPA
        if (overlay) { push('ov', ip); continue; }
        if (SKIP_IFACE.test(name)) continue;
        if (ip.startsWith('192.168.') || ip.startsWith('10.')
            || /^172\.(1[6-9]|2\d|3[0-1])\./.test(ip)) {
          push('lan', ip);
        }
      } else if (fam === 'IPv6' || fam === 6) {
        const l = ip.toLowerCase();
        if (l.startsWith('fe80')) continue;            // link-local бесполезен удалённо
        if (l.startsWith('fc') || l.startsWith('fd')) continue; // ULA — только внутри VPN
        if (l.startsWith('2001:') || l.startsWith('2002:')) continue; // Teredo / 6to4
        if (l.startsWith('64:ff9b:')) continue;        // NAT64 — только к IPv4-целям
        if (overlay) { push('ov', ip); continue; }
        if (SKIP_IFACE.test(name)) continue;
        const first = parseInt(l.split(':')[0] || '0', 16);
        if (first >= 0x2000 && first <= 0x3fff) push('v6', ip); // 2000::/3 = глобальный
      }
    }
  }
  for (const e of CONFIG.extraEndpoints || []) {
    if (typeof e === 'string') out.push({ k: 'ov', u: e });
    else out.push({ k: e.k || 'ov', u: e.u });
  }
  return out;
}

// UDP-обнаружение: телефон шлёт broadcast "CURSOR_BRIDGE?" на 8791,
// мост отвечает unicast'ом с адресом. Надёжнее mDNS в замусоренных сетях.
const dgram = require('dgram');

// Отвечаем только запросам из локальных сетей (RFC1918 / link-local / loopback):
// маяк не должен раскрывать hostname+отпечаток и служить рефлектором наружу.
function isPrivateAddr(ip) {
  if (!ip) return false;
  if (ip === '127.0.0.1' || ip.startsWith('::ffff:127.')) return true;
  const v4 = ip.startsWith('::ffff:') ? ip.slice(7) : ip;
  const p = v4.split('.').map(Number);
  if (p.length === 4 && p.every((n) => n >= 0 && n <= 255)) {
    if (p[0] === 10) return true;
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true;
    if (p[0] === 192 && p[1] === 168) return true;
    if (p[0] === 169 && p[1] === 254) return true; // link-local
    return false;
  }
  const l = ip.toLowerCase();
  return l === '::1' || l.startsWith('fe80:') || l.startsWith('fc') || l.startsWith('fd');
}

const beacon = dgram.createSocket({ type: 'udp4', reuseAddr: true });
beacon.on('message', (msg, rinfo) => {
  if (msg.toString().trim() === 'CURSOR_BRIDGE?' && isPrivateAddr(rinfo.address)) {
    const reply = JSON.stringify({
      cursorBridge: true, port: CONFIG.port, host: os.hostname(), fp: FINGERPRINT,
    });
    beacon.send(reply, rinfo.port, rinfo.address);
  }
});
beacon.on('error', (e) => console.error('UDP beacon error (не критично):', e.message));

// --- удалённые команды на телефон (WS + UDP LAN) ---
const PHONE_CMD_UDP_PORT = (CONFIG.port || 8790) + 3; // 8793
const DIAG_LOG_DIR = path.join(__dirname, 'logs');

function tokenHash16(token) {
  return crypto.createHash('sha256').update(String(token || '')).digest('hex').slice(0, 16);
}

function appendPhoneCmdLog(entry) {
  try {
    fs.mkdirSync(DIAG_LOG_DIR, { recursive: true, mode: 0o700 });
    fs.appendFileSync(path.join(DIAG_LOG_DIR, 'phone-cmd.jsonl'), JSON.stringify(entry) + '\n', { mode: 0o600 });
    fs.writeFileSync(path.join(DIAG_LOG_DIR, 'phone-cmd-latest.json'), JSON.stringify(entry, null, 2), { mode: 0o600 });
  } catch (e) {
    console.error('[phoneCmd] log write:', e.message);
  }
}

/** Разослать команду телефонам: WS (если online) + UDP broadcast (даже без WSS). */
function dispatchPhoneCmd(cmd, cmdId, args, replyWs) {
  const hashes = pairings.devices.map((d) => tokenHash16(d.token)).filter(Boolean);
  const payload = {
    type: 'phoneCmd',
    cmd,
    cmdId,
    args: args || {},
    tokenHashes: hashes,
    at: Date.now(),
  };
  const msg = JSON.stringify(payload);
  let wsTargets = 0;
  for (const c of allClients) {
    if (c.readyState === 1 && c.deviceId && c.deviceId !== 'local') {
      c.send(msg);
      wsTargets++;
    }
  }
  // UDP: телефон слушает 8793; auth по tokenHashes
  const udpBody = Buffer.from('CURSOR_BRIDGE_CMD' + msg, 'utf8');
  try {
    const sock = dgram.createSocket('udp4');
    sock.bind(() => {
      try { sock.setBroadcast(true); } catch {}
      sock.send(udpBody, 0, udpBody.length, PHONE_CMD_UDP_PORT, '255.255.255.255', () => {
        try { sock.close(); } catch {}
      });
    });
  } catch (e) {
    console.error('[phoneCmd] udp send:', e.message);
  }
  const info = { wsTargets, udpPort: PHONE_CMD_UDP_PORT, devices: pairings.devices.length };
  console.log(`\n  [phoneCmd] ${cmd} id=${cmdId} ws=${wsTargets} udp=: ${PHONE_CMD_UDP_PORT}`);
  if (replyWs && replyWs.readyState === 1) {
    replyWs.send(JSON.stringify({ type: 'phoneCmdDispatched', cmdId, cmd, ...info }));
  }
  appendPhoneCmdLog({ at: new Date().toISOString(), event: 'dispatch', cmd, cmdId, ...info });
  return info;
}

async function main() {
  freeBridgePorts();

  TLS = await ensureTls(CERT_DIR, 'cursor-bridge');
  FINGERPRINT = crypto.createHash('sha256').update(pemToDer(TLS.cert)).digest('hex');

  server = https.createServer(TLS, httpHandler);
  tunnelServer = https.createServer(TLS, httpHandler);
  server.on('upgrade', handleUpgrade);
  tunnelServer.on('upgrade', handleUpgrade);

  beacon.bind(CONFIG.port + 1);
  tunnelServer.listen(TUNNEL_PORT, '127.0.0.1');
  if (inet.enabled) startTunnel();

  server.listen(CONFIG.port, CONFIG.host, () => {
    const ip = lanAddress();
    try {
      const bonjour = new Bonjour();
      bonjour.publish({
        name: CONFIG.mdnsName || 'cursor-bridge',
        type: 'cursor-bridge',
        port: CONFIG.port,
        txt: { path: '/', fp: FINGERPRINT },
      });
    } catch (e) {
      console.error('mDNS publish failed (не критично):', e.message);
    }

    restoreSessions();

    const pairPayload = JSON.stringify({
      crb: 1,
      host: ip,
      port: CONFIG.port,
      code: pairState.code,
      fp: FINGERPRINT,
      // LTE-first: телефон без WiFi поднимает iroh по ticket, /pair через туннель
      ticket: (inet.enabled && tunnelInfo.ticket) ? tunnelInfo.ticket : null,
      nodeId: tunnelInfo.nodeId || null,
      internet: !!inet.enabled,
      endpoints: bridgeEndpoints(),
    });

    console.log('');
    console.log('  Cursor Bridge запущен (HTTPS/WSS, порт ' + CONFIG.port + ')');
    console.log(`  Agent: ${resolveAgentCommand()}`);
    console.log(`  Доверенных устройств: ${pairings.devices.length}` +
      (pairings.devices.length ? ' — подключатся сами' : ''));
    console.log('  Доступ через интернет: ' +
      (inet.enabled ? 'включён (p2p iroh)' : 'выключен (включить: cursor-mobile --device)'));
    console.log('');
    console.log('  Локальный UI: https://127.0.0.1:' + CONFIG.port + '/');
    console.log(`  Код сопряжения:  ${pairState.code}  (10 минут, свежий: cursor-mobile --qr)`);
    console.log('');
    qrcode.generate(pairPayload, { small: true });
    console.log(`  Отпечаток сертификата: ${FINGERPRINT.slice(0, 16)}…`);
    try {
      require('child_process').spawnSync(process.execPath, [path.join(__dirname, 'hooks', 'install-hooks.js'), '--quiet'], {
        stdio: 'inherit',
        windowsHide: true,
      });
    } catch (e) {
      console.error('  Hooks не установлены автоматически:', e.message);
      console.error('  Вручную: node hooks/install-hooks.js');
    }
  });
}

main().catch((e) => {
  console.error('Не удалось запустить мост:', e);
  process.exit(1);
});

process.on('exit', () => { if (tunnel) { try { tunnel.kill(); } catch { } } });
process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
