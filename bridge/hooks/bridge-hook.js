#!/usr/bin/env node
'use strict';
/**
 * Cursor Agent hook -> Cursor Bridge.
 * Вне bridge-сессии (нет CURSOR_BRIDGE_SESSION) молча выходит.
 * stdin: JSON от Cursor hooks; stdout: JSON-ответ хука (continue: true).
 */
const https = require('https');

const session = process.env.CURSOR_BRIDGE_SESSION;
if (!session) process.exit(0);

const port = process.env.CURSOR_BRIDGE_PORT || '8790';

const MAP = {
  beforeSubmitPrompt: 'UserPromptSubmit',
  afterAgentResponse: 'PostToolUse',
  afterAgentThought: 'PostToolUse',
  postToolUse: 'PostToolUse',
  stop: 'Stop',
  sessionEnd: 'Stop',
  sessionStart: 'UserPromptSubmit',
  beforeShellExecution: 'Notification',
  beforeMCPExecution: 'Notification',
};

const MSG = {
  UserPromptSubmit: 'Agent работает',
  PostToolUse: 'Agent работает',
  Notification: 'Agent ждёт подтверждения',
  Stop: 'Agent завершил ответ',
};

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => { raw += c; });
process.stdin.on('end', () => {
  let payload = {};
  try { payload = JSON.parse(raw || '{}'); } catch { /* keep {} */ }

  const cursorEvent = payload.hook_event_name || '';
  const mapped = MAP[cursorEvent] || cursorEvent || 'Notification';
  const body = JSON.stringify({
    ...payload,
    hook_event_name: mapped,
    message: payload.message || MSG[mapped] || `Событие ${mapped}`,
    bridge_session: session,
  });

  const req = https.request({
    hostname: '127.0.0.1',
    port: Number(port),
    path: '/hook',
    method: 'POST',
    rejectUnauthorized: false,
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
      'X-Bridge-Session': session,
    },
    timeout: 2000,
  }, (res) => { res.resume(); });
  req.on('error', () => {});
  req.on('timeout', () => { try { req.destroy(); } catch {} });
  req.write(body);
  req.end();

  // Cursor ждёт JSON на stdout; observe-only
  process.stdout.write(JSON.stringify({ continue: true }) + '\n');
  process.exit(0);
});
