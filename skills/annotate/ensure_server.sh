#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
export PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null && pwd)"
export SKILL="annotate"
export MODULE="skills.annotate.server"
export BANNER="annotate-server v1"
exec "$PLUGIN_ROOT/skills/_shared/web_companion/ensure_server.sh"
