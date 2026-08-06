#!/usr/bin/env node
'use strict';
// Удалённые тесты/тактики на телефоне по LAN.
//   node phone-cmd.js snapshot
//   node phone-cmd.js cfg
//   node phone-cmd.js set-cfg onlineMode=skip dialTimeoutSecs=40 restart=1
//   node phone-cmd.js restart-tunnel
//   node phone-cmd.js bundle
//   node phone-cmd.js force-tunnel
//   …

const path = require('path');
const WebSocket = require(path.join(__dirname, 'node_modules', 'ws'));

const BRIDGE = process.env.CURSOR_BRIDGE_URL || 'wss://127.0.0.1:8790/ws';
const rawCmd = (process.argv[2] || 'help').trim();
const ARGS = {
  snapshot: 'полный снимок + /diag',
  diag: 'alias snapshot',
  reconnect: 'forceReconnect',
  'force-tunnel': 'сбросить LAN, поднять iroh',
  'force-lan': 'убить туннель, только WiFi',
  'iroh-probe': 'поднять connect и ждать ready ~30с',
  'relay-ping': 'HTTPS + UDP:7842 ping n0 relay',
  net: 'wifi/cellular + адреса',
  cfg: 'текущие knobs туннеля (без пересборки)',
  'get-cfg': 'alias cfg',
  'set-cfg': 'knobs: onlineMode=skip|bg|wait dialTimeoutSecs=N addrMode=auto|lan|relay|all preferTunnel=0|1 restart=1',
  'restart-tunnel': 'перезапуск сайдкара с текущим cfg',
  bundle: 'cfg+net+relay+udp+iroh-probe одним вызовом',
  'self-test': 'alias bundle',
  help: 'эта справка',
};

function parseKvArgs(argv) {
  const out = {};
  for (const a of argv) {
    const i = a.indexOf('=');
    if (i <= 0) continue;
    const k = a.slice(0, i).trim();
    let v = a.slice(i + 1).trim();
    if (v === '1' || v === 'true' || v === 'yes') v = true;
    else if (v === '0' || v === 'false' || v === 'no') v = false;
    else if (/^-?\d+$/.test(v)) v = parseInt(v, 10);
    out[k] = v;
  }
  return out;
}

if (rawCmd === 'help' || rawCmd === '-h' || rawCmd === '--help') {
  console.log('phone-cmd — удалённые тактики на телефоне (LAN)\n');
  for (const [k, v] of Object.entries(ARGS)) console.log(`  ${k.padEnd(16)} ${v}`);
  console.log('\nПримеры:');
  console.log('  node phone-cmd.js set-cfg onlineMode=skip preferTunnel=1 restart=1');
  console.log('  node phone-cmd.js set-cfg onlineMode=wait onlineTimeoutSecs=5');
  console.log('  node phone-cmd.js bundle');
  console.log('\nОтвет: bridge/logs/phone-cmd-latest.json (+ jsonl)');
  process.exit(0);
}

const aliasMap = {
  diag: 'snapshot',
  relay_ping: 'relay-ping',
  get_cfg: 'cfg',
  'get-cfg': 'cfg',
  set_cfg: 'set-cfg',
  restart_tunnel: 'restart-tunnel',
  'self-test': 'bundle',
};
const alias = aliasMap[rawCmd] || rawCmd;
if (!ARGS[alias] && !ARGS[rawCmd]) {
  console.error('Неизвестная команда:', rawCmd, '— см. phone-cmd.js help');
  process.exit(1);
}

const cmdArgs = parseKvArgs(process.argv.slice(3));
const ws = new WebSocket(BRIDGE, { rejectUnauthorized: false });
const cmdId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
let done = false;

function finish(code) {
  if (done) return;
  done = true;
  try { ws.close(); } catch {}
  process.exit(code);
}

const longCmds = new Set(['relay-ping', 'iroh-probe', 'bundle', 'self-test']);
const timeoutMs = longCmds.has(alias) ? 90000 : 40000;

ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'phoneCmd', cmd: alias, cmdId, args: cmdArgs }));
  console.log(`sent ${alias} id=${cmdId} args=${JSON.stringify(cmdArgs)}, жду ответ (до ${timeoutMs / 1000}с)…`);
});

ws.on('message', (raw) => {
  let m;
  try { m = JSON.parse(String(raw)); } catch { return; }
  if (m.type === 'phoneCmdDispatched') {
    console.log(`dispatched: wsTargets=${m.wsTargets} udp=: ${m.udpPort} devices=${m.devices}`);
    if (m.wsTargets === 0) {
      console.log('(телефон не на WSS — команда ушла UDP; жду result)');
    }
  }
  if (m.type === 'phoneCmdResult' && (!m.cmdId || m.cmdId === cmdId)) {
    console.log('--- result ---');
    console.log(JSON.stringify(m, null, 2));
    finish(m.ok ? 0 : 2);
  }
});

ws.on('error', (e) => {
  console.error('ws error:', e.message);
  console.error('Мост запущен? npm start в bridge/');
  finish(1);
});

setTimeout(() => {
  console.error('timeout — смотри bridge/logs/phone-cmd-latest.json и phone-diag-latest.json');
  finish(3);
}, timeoutMs);
