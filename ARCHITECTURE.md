# Network core (from claude-bridge pattern)

1. LAN first: mDNS + UDP beacon (`CURSOR_BRIDGE?`), WSS + TLS cert pinning.
2. No LAN: iroh over QUIC (`cursor-tunnel`), EndpointTicket, ed25519 NodeId, allowlist.
3. LAN back: prefer local again (monitor paths / RTT).
4. App TLS = end-to-end trust; iroh = transport only.
5. Sidecar: Node bridge + Rust tunnel; keepers survive bridge restart.

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
