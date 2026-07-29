'use strict';
/* global Terminal, FitAddon */

const $ = (s) => document.querySelector(s);
const ESC = '\x1b';
const KEYS = {
  esc: ESC,
  tab: '\t',
  shifttab: ESC + '[Z',
  up: ESC + '[A',
  down: ESC + '[B',
  left: ESC + '[D',
  right: ESC + '[C',
  enter: '\r',
  ctrlc: '\x03',
};

let ws = null;
let sessions = [];
let projects = [];
let current = localStorage.getItem('currentSession') || null;
let selectedProject = '';
let reconnectDelay = 500;
let audioCtx = null;

// ------------------------------------------------------------ terminal

const term = new Terminal({
  fontSize: 13,
  fontFamily: 'Menlo, monospace',
  theme: { background: '#0b1120' },
  cursorBlink: true,
  scrollback: 10000,
});
term.open($('#terminal'));

term.onData((data) => send({ type: 'input', id: current, data }));

// Локальный терминал зеркалит фактический размер PTY (сообщает сервер).
// Свои размеры мы только "предлагаем" — сервер применит их, когда мы печатаем.
const wrap = $('#termwrap');

function cellSize() {
  try {
    const c = term._core._renderService.dimensions.css.cell;
    if (c.width && c.height) return c;
  } catch {}
  return { width: 8, height: 17 };
}

function viewport() {
  const c = cellSize();
  return {
    cols: Math.max(20, Math.floor((wrap.clientWidth - 8) / c.width)),
    rows: Math.max(8, Math.floor((wrap.clientHeight - 8) / c.height)),
  };
}

const refit = () => {
  if (!current) return;
  const v = viewport();
  send({ type: 'resize', id: current, cols: v.cols, rows: v.rows });
};
window.addEventListener('resize', refit);
if (window.visualViewport) window.visualViewport.addEventListener('resize', refit);

// Прокрутка пальцем.
// Обычный буфер (bash и т.п.) — листаем scrollback xterm.
// Альтернативный экран (Cursor Agent TUI) — шлём приложению события колеса
// мыши: TUI сам перерисует свою историю, включая восстановленную --resume.
function sendScroll(lines) {
  if (!current) return;
  const n = Math.min(Math.abs(lines), 10);
  const mouse = term.modes.mouseTrackingMode !== 'none';
  const col = Math.max(1, Math.round(term.cols / 2));
  const row = Math.max(1, Math.round(term.rows / 2));
  let data = '';
  for (let i = 0; i < n; i++) {
    if (mouse) data += `\x1b[<${lines > 0 ? 64 : 65};${col};${row}M`; // колесо вверх/вниз
    else data += lines > 0 ? '\x1b[5~' : '\x1b[6~'; // PageUp / PageDown
  }
  // 'scroll' — как input, но не переключает "владельца размера" PTY
  send({ type: 'scroll', id: current, data });
}

// ------------------------------------------- выделение текста в терминале
// Как в текстовых полях: долгое нажатие выделяет слово под пальцем, по краям
// появляются два ползунка — каждый можно тянуть отдельно, посимвольно.

const sel = {
  active: false,
  a: null, // {line, col} — начало (инвариант: a <= b)
  b: null, // {line, col} — конец (включительно)
};
const handleEnd = { A: 'a', B: 'b' }; // какой ползунок ведёт какой конец

function cmpPos(p, q) { return (p.line - q.line) || (p.col - q.col); }

// позиция ячейки буфера под координатами экрана
function posAt(clientX, clientY) {
  const rect = term.element.getBoundingClientRect();
  const c = cellSize();
  const col = Math.max(0, Math.min(Math.floor((clientX - rect.left) / c.width), term.cols - 1));
  const bu = term.buffer.active;
  const line = Math.max(0, Math.min(
    bu.viewportY + Math.floor((clientY - rect.top) / c.height), bu.length - 1));
  return { line, col };
}

// слово (непрерывный непробельный кусок) вокруг позиции
function wordAt(pos) {
  const lineObj = term.buffer.active.getLine(pos.line);
  const text = lineObj ? lineObj.translateToString() : '';
  const isW = (ch) => !!ch && /\S/.test(ch);
  let s = pos.col;
  let e = pos.col;
  if (isW(text[pos.col])) {
    while (s > 0 && isW(text[s - 1])) s--;
    while (e < term.cols - 1 && isW(text[e + 1])) e++;
  }
  return { a: { line: pos.line, col: s }, b: { line: pos.line, col: e } };
}

// Перетаскивание ползунков — через document: события не теряются,
// даже если палец уехал с ползунка или под другие элементы.
let dragH = null; // { h, id }

function makeHandle() {
  const h = document.createElement('div');
  h.className = 'selh hidden';
  wrap.appendChild(h);
  h.addEventListener('pointerdown', (e) => {
    if (!sel.active) return;
    e.preventDefault();
    e.stopPropagation();
    dragH = { h, id: e.pointerId };
    try { h.setPointerCapture(e.pointerId); } catch {}
  });
  return h;
}
const selhA = makeHandle();
const selhB = makeHandle();

function dragMoveTo(clientX, clientY) {
  // ползунок висит под строкой: целимся на строку выше пальца
  const p = posAt(clientX, clientY - 12 - cellSize().height / 2);
  const key = dragH.h === selhA ? 'A' : 'B';
  sel[handleEnd[key]] = p;
  if (cmpPos(sel.a, sel.b) > 0) {
    // концы поменялись местами — палец теперь ведёт другой конец
    const t = sel.a; sel.a = sel.b; sel.b = t;
    const o = handleEnd.A; handleEnd.A = handleEnd.B; handleEnd.B = o;
  }
  applySelection();
}

// Автоскролл: палец/ползунок у верхнего или нижнего края терминала листает
// историю, выделение тянется следом. Работает и при растягивании тем же
// пальцем после долгого нажатия, и при перетаскивании ползунков.
// В обычном буфере листаем scrollback xterm; в альтернативном экране
// (Cursor Agent TUI) шлём приложению колесо мыши — контент прокручивается
// под выделением, копируется то, что подсвечено на экране.
const AUTOSCROLL_ZONE = 48;
let autoTimer = null;
let lastDragXY = null;
let autoMode = null; // 'handle' — тянем ползунок, 'press' — палец после long-press

function stopAutoScroll() {
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
  autoMode = null;
  altBusy = false;
}

function autoScrollDir(y) {
  const r = wrap.getBoundingClientRect();
  if (y < r.top + AUTOSCROLL_ZONE) return -1;
  if (y > r.bottom - AUTOSCROLL_ZONE) return 1;
  return 0;
}

// растягивание выделения пальцем после долгого нажатия.
// Якорь — исходное слово (pressWord); палец лишь расширяет выделение в свою
// сторону, само слово всегда остаётся внутри. Так при автоскролле вверх
// нижняя граница (якорь) не теряется.
let pressWord = null;
function pressMoveTo(x, y) {
  if (!pressWord) return;
  const p = posAt(x, y);
  sel.a = cmpPos(p, pressWord.a) < 0 ? p : { line: pressWord.a.line, col: pressWord.a.col };
  sel.b = cmpPos(p, pressWord.b) > 0 ? p : { line: pressWord.b.line, col: pressWord.b.col };
  applySelection();
}

// снимок видимых строк — чтобы понять, на сколько TUI сдвинул контент
function viewportSnapshot() {
  const b = term.buffer.active;
  const out = [];
  for (let i = 0; i < term.rows; i++) {
    const l = b.getLine(b.viewportY + i);
    out.push(l ? l.translateToString().trim() : '');
  }
  return out;
}

// на сколько строк сместился контент между снимками (0 — не понять)
function detectShift(prev, next, dir) {
  let best = 0;
  let bestScore = 0;
  for (let s = 1; s <= 6; s++) {
    let match = 0;
    let total = 0;
    for (let i = 0; i < prev.length; i++) {
      const j = dir < 0 ? i + s : i - s;
      if (j < 0 || j >= next.length || !prev[i]) continue;
      total++;
      if (prev[i] === next[j]) match++;
    }
    const score = total ? match / total : 0;
    if (score > bestScore) { bestScore = score; best = s; }
  }
  return bestScore >= 0.5 ? best : 0;
}

let altBusy = false; // ждём перерисовку TUI после посланного колеса

function followFinger() {
  if (!lastDragXY || !sel.active) return;
  if (autoMode === 'handle' && dragH) dragMoveTo(lastDragXY.x, lastDragXY.y);
  else if (autoMode === 'press') pressMoveTo(lastDragXY.x, lastDragXY.y);
}

function autoStep() {
  if (!sel.active || !lastDragXY) { stopAutoScroll(); return; }
  if (autoMode === 'handle' && !dragH) { stopAutoScroll(); return; }
  const d = autoScrollDir(lastDragXY.y);
  if (!d) { stopAutoScroll(); return; }
  if (term.buffer.active.type === 'alternate') {
    // Историей управляет сам TUI (Cursor Agent): шлём колесо, а после его
    // перерисовки сдвигаем выделение на столько же строк — оно остаётся
    // «приклеенным» к тексту, пока тот не уедет за пределы экрана.
    if (altBusy) return;
    altBusy = true;
    const prev = viewportSnapshot();
    sendScroll(-d);
    setTimeout(() => {
      altBusy = false;
      if (!sel.active) return;
      const s = detectShift(prev, viewportSnapshot(), d);
      if (s) {
        const shift = d < 0 ? s : -s;
        const maxL = term.buffer.active.length - 1;
        const mv = (p) => { p.line = Math.max(0, Math.min(p.line + shift, maxL)); };
        mv(sel.a);
        mv(sel.b);
        if (pressWord) { mv(pressWord.a); mv(pressWord.b); }
      }
      followFinger();
      applySelection();
    }, 250);
    return;
  }
  term.scrollLines(d);
  followFinger(); // конец выделения остаётся под пальцем, захватывая прокрученное
}

function ensureAutoScroll(mode, x, y) {
  lastDragXY = { x, y };
  autoMode = mode;
  const dir = autoScrollDir(y);
  if (dir && !autoTimer) autoTimer = setInterval(autoStep, 100);
  else if (!dir) stopAutoScroll();
}

document.addEventListener('pointermove', (e) => {
  if (!dragH || e.pointerId !== dragH.id || !sel.active) return;
  e.preventDefault();
  dragMoveTo(e.clientX, e.clientY);
  ensureAutoScroll('handle', e.clientX, e.clientY);
}, { passive: false });
document.addEventListener('pointerup', (e) => {
  if (dragH && e.pointerId === dragH.id) { dragH = null; stopAutoScroll(); }
});
document.addEventListener('pointercancel', (e) => {
  if (dragH && e.pointerId === dragH.id) { dragH = null; stopAutoScroll(); }
});

function applySelection() {
  if (!sel.active) return;
  const len = (sel.b.line - sel.a.line) * term.cols + (sel.b.col - sel.a.col) + 1;
  term.select(sel.a.col, sel.a.line, Math.max(1, len));
  positionHandles();
}

function positionHandles() {
  const c = cellSize();
  const termRect = term.element.getBoundingClientRect();
  const wrapRect = wrap.getBoundingClientRect();
  const vy = term.buffer.active.viewportY;
  const baseX = termRect.left - wrapRect.left + wrap.scrollLeft;
  const baseY = termRect.top - wrapRect.top + wrap.scrollTop;
  const place = (h, p, endEdge) => {
    const vis = sel.active && p.line >= vy && p.line < vy + term.rows;
    h.classList.toggle('hidden', !vis);
    if (!vis) return;
    h.style.left = (baseX + (p.col + (endEdge ? 1 : 0)) * c.width) + 'px';
    h.style.top = (baseY + (p.line - vy + 1) * c.height) + 'px';
  };
  const hStart = handleEnd.A === 'a' ? selhA : selhB;
  const hEnd = hStart === selhA ? selhB : selhA;
  place(hStart, sel.a, false);
  place(hEnd, sel.b, true);
}

function clearSel() {
  sel.active = false;
  term.clearSelection();
  selhA.classList.add('hidden');
  selhB.classList.add('hidden');
  hideSelBar();
}

// выделение «едет» вместе с прокруткой и пересчётом размера
term.onScroll(() => { if (sel.active) positionHandles(); });
term.onResize(() => { if (sel.active) clearSel(); });

// Прокрутка пальцем + долгое нажатие (выделение слова).
(() => {
  let ty = null;
  let tx = null;
  let selTimer = null;
  let dragSel = false; // тем же пальцем после долгого нажатия тянем конец
  const el = term.element;

  el.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1) return;
    // перехватываем жест целиком: иначе нативный long-press Android
    // «съедает» палец (touchcancel) и растягивание выделения обрывается
    e.preventDefault();
    ty = e.touches[0].clientY;
    tx = e.touches[0].clientX;
    if (sel.active) clearSel(); // тап по терминалу снимает выделение
    dragSel = false; // прошлый жест мог оборваться без touchend
    clearTimeout(selTimer);
    const x = tx;
    const y = ty;
    selTimer = setTimeout(() => {
      dragSel = true;
      sel.active = true;
      handleEnd.A = 'a';
      handleEnd.B = 'b';
      const w = wordAt(posAt(x, y));
      sel.a = w.a;
      sel.b = w.b;
      pressWord = { a: w.a, b: w.b }; // фиксированный якорь на всё время жеста
      applySelection();
      showSelBar();
      if (navigator.vibrate) navigator.vibrate(30);
    }, 500);
  }, { passive: false });

  el.addEventListener('touchmove', (e) => {
    if (ty == null) return;
    const cy = e.touches[0].clientY;
    const cx = e.touches[0].clientX;
    const dy = cy - ty;
    const dx = cx - tx;
    if (dragSel && sel.active) {
      // продолжение долгого нажатия: ведём конец выделения (+автоскролл у краёв)
      pressMoveTo(cx, cy);
      ensureAutoScroll('press', cx, cy);
      e.preventDefault();
      return;
    }
    // палец ощутимо поехал — это скролл, а не долгое нажатие
    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) clearTimeout(selTimer);
    if (Math.abs(dx) > Math.abs(dy)) return;
    const ch = cellSize().height;
    const lines = Math.trunc(dy / ch);
    if (lines) {
      if (term.buffer.active.type === 'alternate') sendScroll(lines);
      else term.scrollLines(-lines);
      ty += lines * ch;
    }
    e.preventDefault();
  }, { passive: false });

  const endTouch = () => {
    clearTimeout(selTimer);
    ty = null;
    if (dragSel) stopAutoScroll();
    dragSel = false;
  };
  el.addEventListener('touchend', endTouch, { passive: true });
  el.addEventListener('touchcancel', endTouch, { passive: true });
})();

// -------------------------------------------------- копирование и вставка

function showSelBar() { $('#selbar').classList.remove('hidden'); }
function hideSelBar() { $('#selbar').classList.add('hidden'); }

$('#selCopy').addEventListener('click', () => {
  const text = term.getSelection();
  if (text) {
    if (typeof window.AndroidApp !== 'undefined' && window.AndroidApp.copyText) {
      window.AndroidApp.copyText(text);
    } else if (navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(() => {});
    }
    banner('📋 Скопировано: ' + text.length + ' симв.');
  }
  clearSel();
});
$('#selCancel').addEventListener('click', clearSel);

$('#pasteBtn').addEventListener('click', async () => {
  let text = '';
  if (typeof window.AndroidApp !== 'undefined' && window.AndroidApp.pasteText) {
    text = window.AndroidApp.pasteText() || '';
  } else if (navigator.clipboard) {
    try { text = await navigator.clipboard.readText(); } catch { /* нет доступа */ }
  }
  if (!text) { banner('📋 Буфер обмена пуст'); return; }
  // вставляем в поле ввода (в позицию курсора) — отправка отдельной кнопкой
  const inp = $('#msg');
  const s = inp.selectionStart == null ? inp.value.length : inp.selectionStart;
  const e = inp.selectionEnd == null ? inp.value.length : inp.selectionEnd;
  inp.value = inp.value.slice(0, s) + text + inp.value.slice(e);
  const np = s + text.length;
  try { inp.setSelectionRange(np, np); } catch { /* не критично */ }
  inp.focus();
});

// Кнопка «в конец»: появляется, когда мы пролистали вверх
const toBottom = $('#toBottom');
toBottom.addEventListener('click', () => term.scrollToBottom());
term.onScroll(() => {
  const b = term.buffer.active;
  toBottom.classList.toggle('hidden', b.viewportY >= b.baseY);
});
term.onWriteParsed(() => {
  const b = term.buffer.active;
  toBottom.classList.toggle('hidden', b.viewportY >= b.baseY);
});

// ------------------------------------------------------------ websocket

let reconnectTimer = null;
function scheduleReconnect() {
  if (reconnectTimer) return; // один цикл переподключения, не несколько
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect(); }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, 10_000);
}

function bridgeWsUrl() {
  // внутри приложения страница живёт локально (assets), а к мосту ведёт
  // локальный ws-прокси с пиннингом TLS — адрес выдаёт приложение
  if (typeof window.AndroidApp !== 'undefined' && window.AndroidApp.wsUrl) {
    return window.AndroidApp.wsUrl();
  }
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}/ws`;
}

function connect() {
  if (ws && (ws.readyState === 0 || ws.readyState === 1)) return; // уже подключаемся/подключены
  ws = new WebSocket(bridgeWsUrl());

  ws.onopen = () => {
    reconnectDelay = 500;
    setStatus('🟢'); // в панели показываем только индикатор, чтобы не занимать место
    if (current) attach(current, false);
  };

  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    switch (m.type) {
      case 'sessions':
        sessions = m.sessions;
        projects = m.projects || [];
        renderTabs();
        if (current && !sessions.find((s) => s.id === current)) {
          current = sessions.length ? sessions[0].id : null;
          if (current) attach(current, true);
          else { document.body.classList.remove('has-session'); term.clear(); }
        }
        if (!current && sessions.length) attach(sessions[0].id, true);
        break;
      case 'created':
        attach(m.id, true);
        break;
      case 'scrollback':
        term.reset();
        if (m.cols && m.rows) term.resize(m.cols, m.rows);
        term.write(m.data);
        break;
      case 'resized':
        if (m.id === current) term.resize(m.cols, m.rows);
        break;
      case 'output':
        if (m.id === current) term.write(m.data);
        break;
      case 'exit':
        if (m.id === current) term.write(`\r\n\x1b[31m[процесс завершён, код ${m.exitCode}]\x1b[0m\r\n`);
        break;
      case 'notify':
        onNotify(m);
        break;
      case 'devices':
        renderDevices(m);
        break;
    }
  };

  ws.onclose = () => {
    setStatus('🔴 реконнект…');
    scheduleReconnect();
  };
  ws.onerror = () => ws.close();
}

const NO_ID_TYPES = ['create', 'list', 'devices'];
function send(obj) {
  if (ws && ws.readyState === 1 && (obj.id || NO_ID_TYPES.includes(obj.type))) {
    ws.send(JSON.stringify(obj));
  }
}

function setStatus(t) { $('#status').textContent = t; }

// ------------------------------------------------------------ sessions ui

function normPath(p) {
  return (p || '').replace(/[\\/]+$/, '').toLowerCase();
}

function shortProjectName(p) {
  const parts = (p || '').split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] || p || 'Проект';
}

function projectParent(p) {
  const parts = (p || '').split(/[\\/]/).filter(Boolean);
  if (parts.length <= 1) return p || '';
  return parts.slice(0, -1).join('/');
}

function prettyTime(ts) {
  if (!ts) return '';
  try {
    return new Date(ts).toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
}

function sessionsForProject(projectPath) {
  const key = normPath(projectPath);
  return sessions
    .filter((s) => normPath(s.cwd) === key)
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

function attach(id, save) {
  current = id;
  if (save) localStorage.setItem('currentSession', id);
  document.body.classList.add('has-session');
  const v = viewport();
  send({ type: 'attach', id, cols: v.cols, rows: v.rows });
  renderTabs();
}

function renderTabs() {
  const tabs = $('#tabs');
  tabs.innerHTML = '';
  for (const s of sessions) {
    const b = document.createElement('button');
    b.className = 'tab' + (s.id === current ? ' active' : '') + (s.alive ? '' : ' dead');
    b.textContent = s.title;
    if (s.alive && (s.status === 'waiting' || s.status === 'working')) {
      const dot = document.createElement('span');
      dot.className = 'dot ' + s.status; // 🟠 ждёт ответа / 🔵 работает
      dot.textContent = ' ●';
      b.appendChild(dot);
    }
    b.onclick = () => { s.lastNotify = null; attach(s.id, true); };
    let t;
    b.oncontextmenu = (e) => { e.preventDefault(); askKill(s); };
    b.addEventListener('touchstart', () => { t = setTimeout(() => askKill(s), 700); }, { passive: true });
    b.addEventListener('touchend', () => clearTimeout(t));
    tabs.appendChild(b);
  }
}

function askKill(s) {
  const q = s.alive ? `Завершить сессию «${s.title}»?` : `Убрать «${s.title}» из списка?`;
  if (window.confirm(q)) send({ type: s.alive ? 'kill' : 'remove', id: s.id });
}

// ------------------------------------------------------------ notifications

function beep() {
  try {
    if (!audioCtx) return;
    const o = audioCtx.createOscillator();
    const g = audioCtx.createGain();
    o.connect(g); g.connect(audioCtx.destination);
    o.frequency.value = 880; g.gain.value = 0.06;
    o.start(); o.stop(audioCtx.currentTime + 0.18);
  } catch {}
}

function onNotify(m) {
  const text = `${m.title || m.id}: ${m.message}`;
  const banner = $('#banner');
  banner.textContent = '🔔 ' + text;
  banner.classList.remove('hidden');
  banner.onclick = () => { banner.classList.add('hidden'); if (m.id) attach(m.id, true); };
  setTimeout(() => banner.classList.add('hidden'), 15_000);

  if (navigator.vibrate) navigator.vibrate([120, 60, 120]);
  beep();

  if (window.Notification && Notification.permission === 'granted' && document.hidden) {
    try { new Notification('Cursor Agent', { body: text }); } catch {}
  }
  renderTabs();
}

// первый тап: разрешаем звук и спрашиваем разрешение на уведомления
document.body.addEventListener('pointerdown', function once() {
  document.body.removeEventListener('pointerdown', once);
  try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch {}
  if (window.Notification && Notification.permission === 'default') {
    Notification.requestPermission().catch(() => {});
  }
}, { once: true });

// ------------------------------------------------------------ controls

for (const btn of document.querySelectorAll('#keys button')) {
  btn.addEventListener('click', () => {
    const seq = KEYS[btn.dataset.key];
    if (seq && current) send({ type: 'input', id: current, data: seq });
  });
}

function sendMessage() {
  const inp = $('#msg');
  if (!current) return;
  if (inp.value) send({ type: 'input', id: current, data: inp.value });
  send({ type: 'input', id: current, data: '\r' });
  inp.value = '';
}
$('#msg').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); sendMessage(); } });

// ------------------------------------------------------------ голосовой ввод
// Долгое нажатие на кнопку отправки: запись через нативный мост (AndroidSTT),
// распознавание — whisper на ПК. «Принять» кладёт текст в поле для правки,
// «Ввод» отправляет его в Agent сразу.

const voice = {
  el: $('#voice'),
  mic: $('#voiceMic'),
  timer: $('#voiceTimer'),
  hint: $('#voiceHint'),
  btns: $('#voiceBtns'),
  recording: false,
  pressTimer: null,
  suppressClick: false,
  tickTimer: null,
  startedAt: 0,
};

function voiceAvailable() { return typeof window.AndroidSTT !== 'undefined'; }

function voiceTick() {
  const s = Math.floor((Date.now() - voice.startedAt) / 1000);
  voice.timer.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function voiceBegin() {
  if (!voiceAvailable() || voice.recording) return;
  if (!window.AndroidSTT.start()) {
    banner('🎤 Нет доступа к микрофону — разрешите и попробуйте ещё раз');
    return;
  }
  voice.recording = true;
  voice.suppressClick = true;
  voice.startedAt = Date.now();
  voice.el.classList.remove('hidden');
  voice.el.classList.add('rec');
  voice.el.classList.remove('busy');
  voice.btns.classList.add('hidden');
  voice.hint.textContent = 'Говорите… отпустите кнопку, когда закончите';
  voiceTick();
  voice.tickTimer = setInterval(voiceTick, 500);
  if (navigator.vibrate) navigator.vibrate(40);
}

function voiceRelease() {
  if (!voice.recording) return;
  voice.recording = false;
  clearInterval(voice.tickTimer);
  window.AndroidSTT.stop();
  voice.el.classList.remove('rec');
  voice.hint.textContent = '«Принять» — текст в поле ввода, «Ввод» — сразу в Agent';
  voice.btns.classList.remove('hidden');
}

function voiceClose() {
  clearInterval(voice.tickTimer);
  voice.recording = false;
  voice.el.classList.add('hidden');
}

let voiceMode = 'accept';
function voiceTranscribe(mode) {
  voiceMode = mode;
  voice.btns.classList.add('hidden');
  voice.el.classList.add('busy');
  voice.hint.textContent = 'Аудио обрабатывается…';
  voice.mic.textContent = '⏳';
  window.AndroidSTT.transcribe();
}

window.sttResult = (text) => {
  voice.mic.textContent = '🎤';
  voiceClose();
  text = (text || '').trim();
  if (!text) { banner('🎤 Речь не распознана'); return; }
  if (voiceMode === 'send' && current) {
    send({ type: 'input', id: current, data: text });
    send({ type: 'input', id: current, data: '\r' });
  } else {
    const inp = $('#msg');
    inp.value = text;
    inp.focus();
  }
};

window.sttError = (msg) => {
  voice.mic.textContent = '🎤';
  voiceClose();
  banner('🎤 Ошибка распознавания: ' + msg);
};

function banner(text) {
  const b = $('#banner');
  b.textContent = text;
  b.classList.remove('hidden');
  b.onclick = () => b.classList.add('hidden');
  setTimeout(() => b.classList.add('hidden'), 6000);
}

$('#voiceCancel').addEventListener('click', () => { window.AndroidSTT.cancel(); voiceClose(); });
$('#voiceAccept').addEventListener('click', () => voiceTranscribe('accept'));
$('#voiceSend').addEventListener('click', () => voiceTranscribe('send'));

function bindVoiceHold(btn, { onShortClick } = {}) {
  if (!btn) return;
  btn.addEventListener('contextmenu', (e) => e.preventDefault());
  btn.addEventListener('pointerdown', (e) => {
    voice.suppressClick = false;
    if (!voiceAvailable()) return;
    try { btn.setPointerCapture(e.pointerId); } catch {}
    voice.pressTimer = setTimeout(voiceBegin, 350);
  });
  const endPress = (e) => {
    clearTimeout(voice.pressTimer);
    try { btn.releasePointerCapture(e.pointerId); } catch {}
    if (voice.recording) voiceRelease();
  };
  btn.addEventListener('pointerup', endPress);
  btn.addEventListener('pointercancel', endPress);
  btn.addEventListener('click', (e) => {
    if (voice.suppressClick) { e.preventDefault(); return; }
    if (onShortClick) onShortClick(e);
  });
}

const sendBtn = $('#send');
bindVoiceHold(sendBtn, { onShortClick: () => sendMessage() });

const micBtn = $('#micBtn');
if (micBtn) {
  if (!voiceAvailable()) micBtn.classList.add('hidden');
  bindVoiceHold(micBtn, {
    onShortClick: () => {
      // короткое нажатие = подсказка / старт записи сразу
      if (!voiceAvailable()) {
        banner('🎤 Голос: удерживайте 🎤 или ➤. Нужен faster-whisper на ПК.');
        return;
      }
      voiceBegin();
    },
  });
}
// ------------------------------------------------------------ devices dialog

$('#devBtn').addEventListener('click', () => {
  // в приложении все настройки (компьютеры + устройства) — нативный экран
  if (typeof window.AndroidApp !== 'undefined') {
    window.AndroidApp.openSettings();
    return;
  }
  $('#devList').textContent = 'Загрузка…';
  send({ type: 'devices' });
  $('#devDialog').showModal();
});
$('#devClose').addEventListener('click', () => $('#devDialog').close());

function renderDevices(m) {
  const box = $('#devList');
  box.innerHTML = '';
  if (!m.devices.length) {
    box.textContent = 'Нет сопряжённых устройств.';
    return;
  }
  for (const d of m.devices) {
    const row = document.createElement('div');
    row.className = 'dev';

    const info = document.createElement('div');
    info.className = 'info';
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = d.name + ' ';
    if (d.id === m.self) {
      const self = document.createElement('span');
      self.className = 'self';
      self.textContent = '● это устройство';
      name.appendChild(self);
    }
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = 'добавлено ' + new Date(d.addedAt).toLocaleString();
    info.appendChild(name);
    info.appendChild(meta);
    row.appendChild(info);

    const btn = document.createElement('button');
    btn.textContent = 'Отозвать';
    btn.onclick = () => {
      const warn = d.id === m.self
        ? `Отозвать ЭТО устройство? Понадобится повторное сопряжение.`
        : `Отозвать «${d.name}»? Устройство потеряет доступ немедленно.`;
      if (window.confirm(warn)) send({ type: 'revokeDevice', id: d.id });
    };
    row.appendChild(btn);
    box.appendChild(row);
  }
}

// ------------------------------------------------------------ new session dialog

function renderProjectPicker() {
  const list = $('#projectList');
  const empty = $('#projectEmpty');
  const query = ($('#projectSearch').value || '').trim().toLowerCase();
  const projectItems = projects
    .filter((p) => !query || p.toLowerCase().includes(query) || shortProjectName(p).toLowerCase().includes(query));

  if (!selectedProject || !projects.some((p) => normPath(p) === normPath(selectedProject))) {
    selectedProject = projectItems[0] || projects[0] || '';
  }
  if (projectItems.length && !projectItems.some((p) => normPath(p) === normPath(selectedProject))) {
    selectedProject = projectItems[0];
  }

  list.innerHTML = '';
  empty.classList.toggle('hidden', projectItems.length > 0);
  for (const projectPath of projectItems) {
    const projectSessions = sessionsForProject(projectPath);
    const b = document.createElement('button');
    b.className = 'pick project' + (normPath(projectPath) === normPath(selectedProject) ? ' active' : '');
    b.innerHTML =
      `<div class="title">${shortProjectName(projectPath)}</div>` +
      `<div class="meta">${projectPath}</div>` +
      `<div class="badges">` +
      `<span class="badge">${projectSessions.length} сесс.</span>` +
      `${projectSessions.some((s) => s.alive) ? '<span class="badge">есть активные</span>' : ''}` +
      `</div>`;
    b.onclick = () => {
      selectedProject = projectPath;
      $('#projectCustom').value = projectPath;
      renderProjectPicker();
      renderProjectSessions();
    };
    list.appendChild(b);
  }
  if (!$('#projectCustom').value.trim() && selectedProject) $('#projectCustom').value = selectedProject;
  renderProjectSessions();
}

function renderProjectSessions() {
  const cwd = $('#projectCustom').value.trim() || selectedProject;
  const list = $('#sessionList');
  const empty = $('#sessionEmpty');
  const hint = $('#projectHint');
  const items = cwd ? sessionsForProject(cwd) : [];

  hint.textContent = cwd ? cwd : 'Выберите проект или укажите путь вручную.';
  list.innerHTML = '';
  empty.classList.toggle('hidden', items.length > 0);

  for (const s of items) {
    const b = document.createElement('button');
    b.className = 'pick session ' + (s.alive ? 'alive' : 'dead') + (s.id === current ? ' active' : '');
    b.innerHTML =
      `<div class="title">${s.title}</div>` +
      `<div class="meta">${s.alive ? 'Активна' : 'Завершена'} · ${prettyTime(s.createdAt)} · ${s.id}</div>` +
      `<div class="badges">` +
      `${s.status === 'working' ? '<span class="badge">работает</span>' : ''}` +
      `${s.status === 'waiting' ? '<span class="badge">ждёт</span>' : ''}` +
      `${s.id === current ? '<span class="badge">открыта</span>' : ''}` +
      `</div>`;
    b.onclick = () => {
      $('#newDialog').close();
      attach(s.id, true);
    };
    list.appendChild(b);
  }
}

$('#newBtn').addEventListener('click', () => {
  selectedProject = selectedProject || projects[0] || '';
  $('#projectSearch').value = '';
  $('#projectCustom').value = selectedProject;
  renderProjectPicker();
  $('#newDialog').showModal();
});
$('#createCancel').addEventListener('click', () => $('#newDialog').close());
$('#projectSearch').addEventListener('input', renderProjectPicker);
$('#projectCustom').addEventListener('input', () => {
  selectedProject = $('#projectCustom').value.trim() || selectedProject;
  renderProjectPicker();
});
$('#createOk').addEventListener('click', () => {
  const cwd = $('#projectCustom').value.trim() || selectedProject;
  const cargs = [];
  if ($('#optResume').checked) cargs.push('--resume');
  if ($('#optForce').checked) cargs.push('--force');
  if ($('#optPlan') && $('#optPlan').checked) cargs.push('--plan');
  // resume при живой сессии в том же каталоге: если беседа ещё открыта в ней,
  // agent не сможет продолжить тот же файл и молча создаст КОПИЮ беседы —
  // так в списке resume появляются дубликаты. Предлагаем вернуться в живую.
  if (cargs.includes('--resume')) {
    const live = sessions.filter((s) => s.alive && normPath(s.cwd) === normPath(cwd));
    if (live.length && window.confirm(
        `В «${cwd}» уже есть живая сессия: ${live[0].title}.\n` +
        `Если беседа ещё открыта в ней, resume создаст копию беседы.\n\n` +
        `Открыть живую сессию вместо этого?`)) {
      $('#newDialog').close();
      attach(live[0].id, true);
      return;
    }
  }
  send({ type: 'create', cwd, args: cargs });
  $('#newDialog').close();
});

connect();
