# Cursor Bridge

Зеркалируй и управляй сессиями [Cursor CLI](https://cursor.com/docs/cli/overview) (`agent`) со смартфона.
Напрямую между устройствами: LAN первым, вне сети — p2p-туннель (iroh/QUIC). Без обязательного облака.

Вдохновлено [HelpFreedom/claude-bridge](https://github.com/HelpFreedom/claude-bridge) (GPL-3.0).
Адаптация под Cursor Agent CLI вместо Claude Code.

## Статус

Скелет репозитория. Реализация моста / туннеля / Android — в работе.

## Архитектура (цель)

```
ПК (Windows/Linux)                          Телефон (Android)
─────────────────                           ─────────────────
Cursor CLI `agent` (PTY)                    WebView + xterm.js
        │                                            │
   keeper.js ── unix/named pipe                      │
        │                                            │
   server.js (WSS + TLS pin) ◄── LAN (mDNS/UDP) ─────┤
        │                                            │
   cursor-tunnel (iroh)  ◄──── p2p QUIC ─────────────┘
```

1. Телефон ищет ПК в LAN (mDNS + UDP-маяк).
2. Нет LAN → auto iroh/QUIC (ticket, NodeId, allowlist).
3. LAN вернулся → снова локальный путь.
4. TLS pin end-to-end; iroh только транспорт.

## Структура

| Каталог | Назначение |
|---------|------------|
| `bridge/` | Node.js мост: PTY keepers, WSS, discovery, pairing |
| `tunnel/` | Rust sidecar: TCP-over-iroh (`cursor-tunnel`) |
| `android/` | Клиент (позже) |

## Требования (план)

- ПК: Node.js 18+, Cursor CLI (`agent` в PATH)
- Опционально: Rust (p2p), Android 8+

## Лицензия

GNU GPL v3.0 — совместимо с upstream claude-bridge.
