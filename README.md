# Cursor Bridge

**Зеркалируй и управляй сессиями [Cursor CLI](https://cursor.com/docs/cli/overview) (`agent`) со смартфона.**
Без облака-посредника: телефон ↔ ПК по LAN или p2p (iroh/QUIC).

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/ПК-Windows%20%7C%20Linux-informational)](#требования)
[![Android](https://img.shields.io/badge/Телефон-Android%208%2B-green)](#2-android-приложение)

🇷🇺 Русский · [🇬🇧 English](README.en.md)

Форк / адаптация [HelpFreedom/claude-bridge](https://github.com/HelpFreedom/claude-bridge) под Cursor Agent CLI.

---

## Что это

Лёгкий мост (Node.js) отражает терминальные сессии Cursor CLI на Android (или браузер на ПК). Ввод, вкладки, push из hooks, голос (опц.), p2p вне дома.

### Возможности

- Полный терминал (xterm.js): ввод, Esc/Tab/Ctrl-C, прокрутка, выделение
- Несколько сессий (`--resume`, `--force`, `--plan`)
- Push из Cursor hooks («ждёт», «закончил»)
- LAN первым; вне дома — iroh/QUIC
- Голосовой ввод (faster-whisper), опционально
- TLS + пиннинг, QR/код сопряжения
- Keeper: сессии живут при рестарте моста

Архитектура: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Как устроено

```
ПК (Windows/Linux)                              Телефон (Android)
─────────────────                               ─────────────────
Cursor Agent (PTY) × N                          WebView + xterm.js
        │                                              │
   keeper.js ── TCP 127.0.0.1                          │
        │                                              │
   server.js (WSS + TLS pin) ◄── LAN mDNS/UDP ─────────┤
        │                                              │
   cursor-tunnel (iroh)  ◄──── p2p QUIC ───────────────┘
   hooks → POST /hook → push
```

---

## Требования

**ПК:**
- Windows 10+ или Linux
- Node.js 18+
- [Cursor CLI](https://cursor.com/docs/cli/installation) (`agent` в PATH)
- *(опц.)* Rust — сборка `cursor-tunnel`
- *(опц.)* Python + `faster-whisper` — голос
- *(опц.)* Avahi/mDNS (Linux)

**Телефон:** Android 8.0+ (API 26)

---

## Установка

### 1. Мост на ПК

```bash
git clone https://github.com/cclonner/cursor-bridge.git
cd cursor-bridge/bridge
npm install
npm start
```

Первый запуск: `config.json`, TLS в `certs/`, QR + код, установка Cursor hooks в `~/.cursor/hooks.json`.

Локальный UI: `https://127.0.0.1:8790/`

```bash
node attach.js              # новая сессия agent в cwd
node attach.js -l
node attach.js -a
node attach.js --qr
node attach.js --device     # устройства + вкл/выкл интернет (i)
npm run install-hooks       # переустановить hooks
```

#### p2p-туннель

```bash
cd tunnel
cargo build --release
# Windows:
mkdir ..\bridge\bin
copy target\release\cursor-tunnel.exe ..\bridge\bin\
# Linux:
mkdir -p ../bridge/bin && cp target/release/cursor-tunnel ../bridge/bin/
```

Включить: `node attach.js --device` → `i` (или из Android).

#### Автозапуск

**Linux (systemd user):**

```ini
# ~/.config/systemd/user/cursor-bridge.service
[Unit]
Description=Cursor Bridge
After=network.target

[Service]
Type=simple
WorkingDirectory=%h/cursor-bridge/bridge
ExecStart=/usr/bin/node %h/cursor-bridge/bridge/server.js
Restart=on-failure
RestartSec=3
KillMode=process
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now cursor-bridge
```

**Windows:** Task Scheduler → при входе запускать `node C:\...\cursor-bridge\bridge\server.js` (рабочая папка = `bridge`).

### 2. Android

```bash
cd android
./gradlew assembleDebug   # нужен Android SDK
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Для p2p на телефоне — `libcursortunnel.so` (aarch64) из кросс-сборки `tunnel/` (см. ARCHITECTURE).

Сопряжение: QR с моста или код вручную.

### 3. Hooks (push)

При `npm start` хуки ставятся в `~/.cursor/hooks.json`. Скрипт `bridge/hooks/bridge-hook.js` шлёт события на `/hook` только если в окружении есть `CURSOR_BRIDGE_SESSION` (ставит keeper).

События: `beforeSubmitPrompt`, `afterAgentResponse`, `stop`, `sessionEnd`, `beforeShellExecution`, `beforeMCPExecution`.

### 4. Голос (опц.)

```bash
pip install faster-whisper
```

В `config.json`: `sttPython`, `sttModel`, `sttLanguage`.

---

## Настройка

`bridge/config.json`:

| Ключ | Назначение |
|------|------------|
| `port` | Порт (8790) |
| `agentCommand` | `agent` или полный путь к `agent.cmd` |
| `projects` | Белый список cwd для телефона |
| `defaultCwd` | Каталог по умолчанию |
| `env` | env для сессий (прокси и т.п.) |
| `stt*` | Голосовой ввод |

`config.json` в `.gitignore`.

---

## Безопасность

- TLS + пиннинг SHA-256
- Сопряжение: одноразовый код, лимит попыток
- Токены только у доверенных устройств; отзыв рвёт соединения
- Windows: loopback на основном порту = локальный клиент
- Linux: локальный доступ по UID
- Не многопользовательский SaaS

---

## Лицензия

[GNU GPL v3](LICENSE). См. [AUTHORS.md](AUTHORS.md).
