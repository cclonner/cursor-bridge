#!/usr/bin/env node
'use strict';
/**
 * Установить/обновить hooks Cursor Agent для Cursor Bridge.
 * Пишет/мержит ~/.cursor/hooks.json — хук срабатывает только при CURSOR_BRIDGE_SESSION.
 */
const fs = require('fs');
const path = require('path');
const os = require('os');

const quiet = process.argv.includes('--quiet');
const hookJs = path.resolve(__dirname, 'bridge-hook.js');
const command = `node "${hookJs.replace(/\\/g, '/')}"`;

const events = [
  'beforeSubmitPrompt',
  'afterAgentResponse',
  'stop',
  'sessionEnd',
  'beforeShellExecution',
  'beforeMCPExecution',
];

const cursorDir = path.join(os.homedir(), '.cursor');
const hooksPath = path.join(cursorDir, 'hooks.json');
fs.mkdirSync(cursorDir, { recursive: true });

let cfg = { version: 1, hooks: {} };
try { cfg = { version: 1, hooks: {}, ...JSON.parse(fs.readFileSync(hooksPath, 'utf8')) }; } catch {}
if (!cfg.hooks || typeof cfg.hooks !== 'object') cfg.hooks = {};

const marker = 'bridge-hook.js';
let changed = false;
for (const ev of events) {
  const list = Array.isArray(cfg.hooks[ev]) ? cfg.hooks[ev] : [];
  const filtered = list.filter((h) => !(h && String(h.command || '').includes(marker)));
  const had = filtered.length !== list.length;
  const sameCmd = list.some((h) => h && String(h.command) === command);
  if (!sameCmd || had) changed = true;
  filtered.push({ command });
  cfg.hooks[ev] = filtered;
}

if (changed || !fs.existsSync(hooksPath)) {
  fs.writeFileSync(hooksPath, JSON.stringify(cfg, null, 2) + '\n');
  if (!quiet) {
    console.log('Cursor Bridge hooks установлены:', hooksPath);
    console.log('Команда:', command);
  }
} else if (!quiet) {
  console.log('Cursor Bridge hooks уже актуальны:', hooksPath);
}
