# Network core (from claude-bridge pattern)

1. LAN first: mDNS + UDP beacon (`CURSOR_BRIDGE?`), WSS + TLS cert pinning.
2. No LAN: iroh over QUIC (`cursor-tunnel`), EndpointTicket, ed25519 NodeId, allowlist.
3. LAN back: prefer local again (monitor paths / RTT).
4. App TLS = end-to-end trust; iroh = transport only.
5. Sidecar: Node bridge + Rust tunnel; keepers survive bridge restart.

## Implemented (v0.1)

- `bridge/server.js` — HTTPS/WSS, pairing, sessions, UDP/mDNS discovery
- `bridge/keeper.js` — PTY owner; IPC via TCP `127.0.0.1` (Windows-friendly)
- `bridge/certs.js` — self-signed TLS without openssl (`selfsigned`)
- `bridge/attach.js` — `cursor-mobile` local client
- `bridge/web/` — xterm UI (local browser)
- Windows: loopback on main port = local trust; `agent.cmd` auto-resolve

## Differences vs Claude Bridge

- Spawn Cursor CLI `agent` instead of `claude`
- TCP IPC instead of unix sockets
- TLS via `selfsigned` (no openssl)
- Beacon/QR: `CURSOR_BRIDGE?` / `{ crb: 1, ... }`
- Windows primary + Linux

## Not yet / partial

- Android: исходники портированы (`com.q.cursorbridge`), сборка APK нужен SDK
- `cursor-tunnel`: собран под Windows (`bridge/bin/cursor-tunnel.exe`), ALPN `cursor-bridge/1`
- STT / hooks как у Claude — опционально позже
