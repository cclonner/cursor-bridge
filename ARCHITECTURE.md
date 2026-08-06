# Network core (from claude-bridge pattern)

1. LAN first: mDNS + UDP beacon (`CURSOR_BRIDGE?`), WSS + TLS cert pinning.
2. No LAN: WSS-эндпоинты моста (TCP): IPv6 напрямую → overlay (Tailscale/Radmin VPN/ZeroTier) → iroh over QUIC (`cursor-tunnel`) последним фолбэком.
3. LAN back: prefer local again (monitor paths / RTT).
4. App TLS = end-to-end trust; iroh = transport only.
5. Sidecar: Node bridge + Rust tunnel; keepers survive bridge restart.

Почему TCP/WSS раньше QUIC: QUIC = UDP — на LTE в РФ режется/троттлится, а
public-релеи n0 за границей. WSS по TCP проходит DPI, а при IPv6 у провайдеров
или overlay-сети на обоих устройствах релей не нужен вовсе.

Срез по LTE/relay (2026-08): [`docs/iroh-relay-verdict.md`](docs/iroh-relay-verdict.md) — что такое relay, вердикт «не хватает своей соты», ссылки n0.

## Пути соединения (порядок приоритета на телефоне)

1. **LAN** — mDNS/NSD + UDP beacon, WSS к RFC1918-адресу (свежий discovery).
2. **IPv6** — WSS к глобальному v6 моста: LTE/интернет без релея, если
   домашний провайдер и оператор дают v6.
3. **Overlay** — WSS к адресу overlay-интерфейса моста (Tailscale/Radmin VPN/
   ZeroTier): стабильный путь через TCP-443/TCP, установка overlay на оба
   устройства.
4. **iroh/QUIC** — `cursor-tunnel` (relay), best-effort.

Мост публикует кандидатов (`lan`/`v6`/`ov`) в QR, `/pair`, `pairinfo` и
WS-ответе `internet` — функция `bridgeEndpoints()` в `bridge/server.js`.
Overlay-интерфейсы определяются по именам (tailscale/radmin/zerotier/wg…),
мусор (WARP/Teredo/6to4/WSL/Hyper‑V/Docker) отсекается; ручные адреса —
`extraEndpoints` в `bridge/config.json`. Телефон перебирает кандидатов с
таймаутом 6 с на попытку (`pathIdx`, `viaRemoteKind` в `BridgeService.java`),
затем переходит к туннелю.

## Implemented (v0.2)

- `bridge/server.js` — HTTPS/WSS, pairing, sessions, UDP/mDNS, STT `/stt`, `/hook`, iroh control
- `bridge/keeper.js` — PTY owner; IPC via TCP `127.0.0.1`
- `bridge/certs.js` — self-signed TLS (`selfsigned`)
- `bridge/attach.js` — `cursor-mobile`
- `bridge/web/` — xterm UI
- `bridge/hooks/` — Cursor Agent hooks → push (`install-hooks.js`)
- `bridge/stt.py` — faster-whisper worker
- `tunnel/` — `cursor-tunnel` (iroh), Windows+Unix secret key
- `android/` — `com.q.cursorbridge` (LAN/QR/beacon/tunnel hooks)

## Hooks (Cursor)

Global `~/.cursor/hooks.json` → `bridge/hooks/bridge-hook.js`.
Only active when keeper sets `CURSOR_BRIDGE_SESSION` (+ `CURSOR_BRIDGE_PORT`).
Maps Cursor events → bridge statuses / notify (Stop, waiting, working).
