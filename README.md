# Cursor Bridge

Зеркалируй и управляй сессиями [Cursor CLI](https://cursor.com/docs/cli/overview) (`agent`) со смартфона или браузера.
LAN первым, вне сети — p2p (iroh/QUIC, опционально). Без обязательного облака.

Вдохновлено [HelpFreedom/claude-bridge](https://github.com/HelpFreedom/claude-bridge) (GPL-3.0).

## Быстрый старт (ПК)

```bash
cd bridge
npm install
npm start
```

Открой `https://127.0.0.1:8790/` (самоподписанный TLS — принять предупреждение).
Локальный доступ с ПК доверен без токена (loopback).

Утилита:

```bash
node attach.js          # новая сессия agent в текущем каталоге
node attach.js -l       # список сессий
node attach.js -a       # подключиться
node attach.js --qr     # QR / код сопряжения
```

Требования: Node.js 18+, Cursor CLI (`agent`). Windows и Linux.

## Конфиг

`bridge/config.json` (из `config.example.json`):

| Ключ | Назначение |
|------|------------|
| `port` | Порт моста (8790) |
| `agentCommand` | `agent` или полный путь к `agent.cmd` |
| `projects` | Белый список cwd для удалённых устройств |
| `defaultCwd` | Каталог по умолчанию |

## Архитектура

См. [ARCHITECTURE.md](ARCHITECTURE.md).

- `keeper.js` — PTY живёт отдельно от моста (IPC: TCP 127.0.0.1)
- TLS через `selfsigned` (openssl не нужен)
- UDP-маяк `CURSOR_BRIDGE?` на port+1, mDNS `cursor-bridge`
- iroh-туннель: собрать `tunnel/` → `bridge/bin/cursor-tunnel` (позже)

## Android

Клиент пока не портирован. Локальный web UI уже работает.
План: форк android из claude-bridge под beacon/QR `crb`.

## Лицензия

GNU GPL v3.0.
