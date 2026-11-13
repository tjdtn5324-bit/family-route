#!/bin/sh
# Clone-and-run demo launcher. Needs Node.js 24+ only.
command -v node >/dev/null 2>&1 || { echo "Node.js 24 or newer is required: https://nodejs.org"; exit 1; }
exec node "$(dirname "$0")/tools/demo.mjs" "$@"
