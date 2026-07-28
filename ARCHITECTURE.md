# Network core (from claude-bridge pattern)

1. LAN first: mDNS + UDP beacon, WSS + TLS cert pinning.
2. No LAN: iroh over QUIC, EndpointTicket, ed25519 NodeId, allowlist.
3. LAN back: prefer local again (monitor paths / RTT).
4. App TLS = end-to-end trust; iroh = transport only.
5. Sidecar: Node bridge + Rust tunnel; keepers survive bridge restart.

Differences vs Claude Bridge:
- Spawn `agent` (Cursor CLI) instead of `claude`.
- Windows support primary (named pipes / ConPTY) + Linux.
- Windows-native install path for Cursor CLI.
