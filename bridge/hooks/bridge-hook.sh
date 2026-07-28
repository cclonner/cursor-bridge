#!/bin/sh
# Cursor Agent hook -> Cursor Bridge (POSIX).
# Вне bridge-сессии молча выходит.
[ -z "$CURSOR_BRIDGE_SESSION" ] && exit 0
BODY=$(cat)
# Проброс через node-хук, если есть рядом; иначе curl с исходным JSON
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if command -v node >/dev/null 2>&1 && [ -f "$DIR/bridge-hook.js" ]; then
  printf '%s' "$BODY" | node "$DIR/bridge-hook.js"
  exit 0
fi
curl -sk --noproxy '*' -m 2 \
  -X POST "https://127.0.0.1:${CURSOR_BRIDGE_PORT:-8790}/hook" \
  -H "Content-Type: application/json" \
  -H "X-Bridge-Session: $CURSOR_BRIDGE_SESSION" \
  -d "$BODY" >/dev/null 2>&1 || true
echo '{"continue":true}'
exit 0
