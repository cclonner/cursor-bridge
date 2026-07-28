# Cursor Bridge

**Mirror and control [Cursor CLI](https://cursor.com/docs/cli/overview) (`agent`) sessions from your phone.**
Direct device-to-device: LAN first, p2p (iroh/QUIC) when away. No mandatory cloud.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

🇷🇺 [Russian](README.md) · 🇬🇧 English

Fork/adaptation of [HelpFreedom/claude-bridge](https://github.com/HelpFreedom/claude-bridge) for Cursor Agent CLI.

## Quick start

```bash
git clone https://github.com/cclonner/cursor-bridge.git
cd cursor-bridge/bridge
npm install
npm start
```

Open `https://127.0.0.1:8790/` locally. Pair phone via QR/code.

```bash
node attach.js          # new agent session in cwd
node attach.js --qr
node attach.js --device # toggle internet (iroh)
```

### Tunnel

```bash
cd tunnel && cargo build --release
# copy binary to bridge/bin/cursor-tunnel(.exe)
```

### Android

Download the APK from [Releases](https://github.com/cclonner/cursor-bridge/releases) (`cursor-bridge-v0.1.0.apk`), allow unknown sources, install.

Or build: `cd android && ./gradlew assembleDebug`

### Hooks / STT

Hooks install into `~/.cursor/hooks.json` on bridge start (`npm run install-hooks`).
Optional STT: `pip install faster-whisper`, configure `stt*` in `config.json`.

See [ARCHITECTURE.md](ARCHITECTURE.md), [AUTHORS.md](AUTHORS.md), [LICENSE](LICENSE).
