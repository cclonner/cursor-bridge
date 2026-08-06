#!/usr/bin/env node
'use strict';
// cursor-mobile: запустить Cursor Agent через мост и зеркалировать сессию
// в этом терминале. Телефон видит ту же сессию и может вмешиваться.
//
//   cursor-mobile [args…]    новая сессия в текущем каталоге; все аргументы
//                            передаются agent (--resume, --continue,
//                            --dangerously-skip-permissions и т.д.)
//   cursor-mobile -l         список сессий
//   cursor-mobile -a [id]    подключиться к сессии (без id — к последней живой)
//   cursor-mobile --device   доверенные устройства: просмотр и отзыв
//   cursor-mobile --qr       показать QR-код сопряжения (мост должен работать)
//
// Ctrl+\  — отсоединиться (сессия продолжает жить, телефон остаётся подключён)

const path = require('path');
const readline = require('readline');
const WebSocket = require(path.join(__dirname, 'node_modules', 'ws'));

const BRIDGE = process.env.CURSOR_BRIDGE_URL || 'wss://127.0.0.1:8790/ws';
const args = process.argv.slice(2);

const USAGE = `cursor-mobile — Cursor Agent через мост (зеркало на телефон)

  cursor-mobile [args…]     новая сессия в текущем каталоге; аргументы
                            передаются самому agent (--resume, --continue…)
  cursor-mobile -l          список сессий
  cursor-mobile -a [id]     подключиться к сессии (без id — к последней живой)
  cursor-mobile --device    доверенные устройства: просмотр и отзыв
  cursor-mobile --qr        QR-код сопряжения
  cursor-mobile -h          эта справка

Ctrl+\\ — отсоединиться: сессия продолжит жить, вернуться — cursor-mobile -a.
Завершить сессию по-настоящему: выйти из agent (Ctrl+C дважды или /exit)
или долгим нажатием на её вкладку в приложении на телефоне.`;

// Частые опечатки в НАШИХ флагах ловим до создания сессии: иначе «agent --l»
// молча запустил бы сессию, agent отверг бы флаг и умер с загадочным
// «[сессия завершена]».
const TYPOS = new Map([
  ['--l', '-l'], ['-list', '-l'],
  ['--a', '-a'], ['-attach', '-a'],
  ['-device', '--device'], ['-devices', '--device'],
  ['-qr', '--qr'], ['--h', '-h'], ['-help', '-h'],
]);

if (args[0] === '-h' || args[0] === '--help') {
  console.log(USAGE);
  process.exit(0);
}
if (TYPOS.has(args[0])) {
  console.error(`Неизвестный флаг «${args[0]}» — возможно, вы имели в виду «${TYPOS.get(args[0])}»?\n`);
  console.error(USAGE);
  process.exit(1);
}

// сертификат моста самоподписанный; для localhost это безопасно
const ws = new WebSocket(BRIDGE, { rejectUnauthorized: false });
let sid = null;
let decided = false;
let raw = false;

function die(msg, code) {
  cleanup();
  if (msg) console.error(msg);
  process.exit(code || 0);
}

function cleanup() {
  if (raw && process.stdin.isTTY) process.stdin.setRawMode(false);
}

function send(obj) { ws.send(JSON.stringify(obj)); }

function attach(id) {
  sid = id;
  send({
    type: 'attach', id,
    cols: process.stdout.columns || 100,
    rows: process.stdout.rows || 30,
  });
}

let createdAtMs = 0; // когда МЫ создали сессию — для диагностики мгновенной смерти

function doCreate() {
  createdAtMs = Date.now();
  const shown = args.length ? 'agent ' + args.join(' ') : 'claude';
  console.log(`Запускаю: ${shown}  (каталог ${process.cwd()})`);
  send({ type: 'create', cwd: process.cwd(), args });
}

/**
 * Создание сессии; перед запуском с --resume/--continue предупреждаем о живых
 * сессиях в этом же каталоге: если беседа ещё открыта в одной из них, claude
 * не сможет продолжить тот же файл и молча создаст КОПИЮ беседы (форк) —
 * так появляются дубликаты в списке resume.
 */
function maybeCreate(sessionsList) {
  const resumeLike = args.some((a) =>
    a === '--resume' || a === '-r' || a === '--continue' || a === '-c');
  const liveHere = sessionsList.filter((s) => s.alive && s.cwd === process.cwd());
  if (!resumeLike || !liveHere.length) { doCreate(); return; }

  console.log('⚠ В этом каталоге уже есть живые сессии:');
  for (const s of liveHere) console.log(`    ${s.id}\t${s.title}`);
  console.log('Если ваша беседа ещё открыта в одной из них, resume создаст её КОПИЮ.');
  console.log(`Вернуться в живую сессию: cursor-mobile -a ${liveHere[liveHere.length - 1].id}`);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  rl.question('Всё равно продолжить с resume? [y/N]: ', (ans) => {
    rl.close();
    if (/^[yд]/i.test(ans.trim())) doCreate();
    else die('Отменено.');
  });
}

function enterRaw() {
  if (raw || !process.stdin.isTTY) return;
  raw = true;
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.on('data', (d) => {
    if (d.length === 1 && d[0] === 0x1c) { // Ctrl+\
      die('\r\n[отсоединился — сессия продолжает работать; вернуться: cursor-mobile -a]\r');
    }
    send({ type: 'input', id: sid, data: d.toString('utf8') });
  });
  process.on('SIGWINCH', () => {
    send({ type: 'resize', id: sid, cols: process.stdout.columns, rows: process.stdout.rows });
  });
}

ws.on('error', (e) => die('Мост недоступен (' + e.message + '). Запущен ли bridge/server.js?', 1));

ws.on('message', (rawMsg) => {
  const m = JSON.parse(rawMsg);
  switch (m.type) {
    case 'sessions': {
      if (decided) return;
      decided = true;
      if (args[0] === '--device' || args[0] === '--devices') {
        send({ type: 'internet' }); // придёт раньше devices — меню покажет статус
        send({ type: 'devices' });
      } else if (args[0] === '--qr') {
        send({ type: 'pairinfo' });
      } else if (args[0] === '-l' || args[0] === '--list') {
        if (!m.sessions.length) console.log('Сессий нет.');
        for (const s of m.sessions) {
          console.log(`${s.id}\t${s.alive ? 'жива' : 'завершена'}\t${s.title}\t${s.cwd}`);
        }
        process.exit(0);
      } else if (args[0] === '-a' || args[0] === '--attach') {
        const target = args[1]
          ? m.sessions.find((s) => s.id === args[1])
          : m.sessions.filter((s) => s.alive).pop();
        if (!target) die('Живых сессий нет. Запустите: cursor-mobile', 1);
        else attach(target.id);
      } else {
        // все остальные аргументы уходят самому claude
        maybeCreate(m.sessions);
      }
      break;
    }
    case 'created':
      attach(m.id);
      break;
    case 'scrollback':
      enterRaw();
      process.stdout.write(m.data);
      break;
    case 'output':
      if (m.id === sid) process.stdout.write(m.data);
      break;
    case 'exit':
      if (m.id === sid) {
        let msg = `\r\n[сессия завершена, код ${m.exitCode}]`;
        // сессию создали мы, и agent умер почти сразу с ошибкой —
        // почти наверняка он не принял переданные аргументы
        if (createdAtMs && m.exitCode !== 0 && Date.now() - createdAtMs < 15000 && args.length) {
          msg += `\r\nagent вышел сразу после старта — возможно, не принял аргументы: agent ${args.join(' ')}`;
          msg += '\r\nСправка: cursor-mobile -h';
        }
        die(msg, m.exitCode === 0 ? 0 : 1);
      }
      break;
    case 'devices':
      if (args[0] === '--device' || args[0] === '--devices') deviceTui(m);
      break;
    case 'internet':
      inetState = m;
      break;
    case 'pairinfo': {
      const qrcode = require(path.join(__dirname, 'node_modules', 'qrcode-terminal'));
      console.log('\nСопряжение нового устройства с Cursor Bridge');
      console.log(`Адрес моста: ${m.host}:${m.port}`);
      if (m.internet) console.log(`Доступ через интернет: ${fmtInet(m.internet)}`);
      console.log(`Код для ручного ввода: ${m.code}  (действует ${m.ttlMin || 10} минут)\n`);
      qrcode.generate(JSON.stringify({
        crb: 1, host: m.host, port: m.port, code: m.code, fp: m.fp,
        ticket: m.ticket || null, nodeId: m.nodeId || null, internet: !!m.internet,
      }), { small: true }, (q) => { console.log(q); process.exit(0); });
      break;
    }
  }
});

// ------------------------------- TUI: доверенные устройства + интернет-доступ

let rl = null;
let inetState = null;

function fmtInet(s) {
  if (!s) return 'статус неизвестен';
  if (!s.available) return 'недоступен: не собран bridge/bin/cursor-tunnel';
  if (!s.enabled) return 'выключен';
  let line = 'ВКЛЮЧЁН' + (s.ticket ? '' : ' (p2p-endpoint запускается…)');
  if (s.peers && s.peers.length) {
    const paths = s.peers.map((p) =>
      (p.path === 'direct' ? 'напрямую' : p.path === 'relay' ? 'через relay' : '…') +
      (p.rttMs != null ? ` ${Math.round(p.rttMs)} мс` : '')).join(', ');
    line += `; интернет-подключений: ${s.peers.length} (${paths})`;
  }
  return line;
}

function deviceTui(m) {
  if (!rl) rl = readline.createInterface({ input: process.stdin, output: process.stdout });

  console.log('\nДоверенные устройства моста:');
  if (!m.devices.length) console.log('  (нет сопряжённых устройств)');
  m.devices.forEach((d, i) => {
    const self = d.id === m.self ? '  [это устройство]' : '';
    console.log(`  ${i + 1}. ${d.name}  ·  добавлено ${new Date(d.addedAt).toLocaleString('ru-RU')}${self}`);
  });
  console.log(`\nДоступ через интернет (p2p iroh): ${fmtInet(inetState)}`);

  rl.question('\nНомер — отозвать устройство, i — вкл/выкл интернет, Enter — выход: ', (ans) => {
    ans = ans.trim();
    if (/^[iи]$/i.test(ans)) {
      send({ type: 'setInternet', enabled: !(inetState && inetState.enabled) });
      // сервер разошлёт свежий internet-статус; перезапрашиваем меню
      setTimeout(() => { send({ type: 'internet' }); send({ type: 'devices' }); }, 400);
      return;
    }
    const n = parseInt(ans, 10);
    const dev = m.devices[n - 1];
    if (!dev) { console.log('Выход.'); process.exit(0); }
    rl.question(`Отозвать «${dev.name}»? Устройство сразу потеряет доступ [y/N]: `, (yn) => {
      if (/^[yд]/i.test(yn.trim())) {
        send({ type: 'revokeDevice', id: dev.id });
        console.log('Отозвано.');
        // сервер пришлёт обновлённый список — TUI покажет его снова
      } else {
        console.log('Отменено.');
        process.exit(0);
      }
    });
  });
}
