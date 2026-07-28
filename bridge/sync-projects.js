'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

function fileUriToPath(uri) {
  let p = decodeURIComponent(String(uri).replace(/^file:\/\//, ''));
  if (/^\/[A-Za-z]:/.test(p)) p = p.slice(1);
  if (process.platform === 'win32') p = p.replace(/\//g, path.sep);
  try { p = fs.realpathSync(p); } catch { /* keep */ }
  return p;
}

const storage = path.join(process.env.APPDATA, 'Cursor', 'User', 'globalStorage', 'storage.json');
const cfgPath = path.join(__dirname, 'config.json');
const projects = [];

function add(p) {
  if (!p || !fs.existsSync(p)) return;
  const key = process.platform === 'win32' ? p.toLowerCase() : p;
  if (projects.some((x) => (process.platform === 'win32' ? x.toLowerCase() : x) === key)) return;
  projects.push(p);
}

if (fs.existsSync(storage)) {
  const j = JSON.parse(fs.readFileSync(storage, 'utf8'));
  for (const f of (j.backupWorkspaces && j.backupWorkspaces.folders) || []) {
    if (f.folderUri) add(fileUriToPath(f.folderUri));
  }
}

const wsRoot = path.join(process.env.APPDATA, 'Cursor', 'User', 'workspaceStorage');
if (fs.existsSync(wsRoot)) {
  for (const d of fs.readdirSync(wsRoot)) {
    const wp = path.join(wsRoot, d, 'workspace.json');
    if (!fs.existsSync(wp)) continue;
    try {
      const w = JSON.parse(fs.readFileSync(wp, 'utf8'));
      if (w.folder) add(fileUriToPath(w.folder));
    } catch { /* skip */ }
  }
}

add(path.join(os.homedir(), 'source', 'cursor-bridge'));

const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
cfg.projects = projects;
cfg.defaultCwd = projects[0] || cfg.defaultCwd || os.homedir();
fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2) + '\n');
console.log('projects:');
for (const p of projects) console.log(' -', p);
console.log('defaultCwd:', cfg.defaultCwd);
