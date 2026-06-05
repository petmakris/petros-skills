#!/usr/bin/env bash
# Smoke test for watcher.sh.  Fakes events and asserts the banner format.
set -euo pipefail

ROOT="$(mktemp -d)"
trap "rm -rf $ROOT" EXIT
STATE="$ROOT/state"
EVENTS="$STATE/events"
CONSUMED="$STATE/consumed"
mkdir -p "$EVENTS" "$CONSUMED"

WATCHER="$(cd "$(dirname "$0")/.." && pwd)/watcher.sh"

OUT="$ROOT/out.txt"

(
  SKILL=test SID=sid-1 STATE_DIR="$STATE" EVENTS_DIR="$EVENTS" CONSUMED_DIR="$CONSUMED" \
    "$WATCHER" > "$OUT" 2>&1
) &
WATCHER_PID=$!

# Give watcher a moment to start
sleep 0.5

# Drop first event
echo '{"a":1}' > "$EVENTS/100.json"
sleep 1.5

# Ack the first
touch "$CONSUMED/100.ack"
sleep 1

# Drop second event
echo '{"b":2}' > "$EVENTS/200.json"
sleep 1.5
touch "$CONSUMED/200.ack"
sleep 1

# Finish the session
touch "$STATE/finished"

# Wait for clean exit (up to 5s)
for _ in $(seq 1 20); do
  if ! kill -0 $WATCHER_PID 2>/dev/null; then break; fi
  sleep 0.25
done
wait $WATCHER_PID 2>/dev/null || true

# Assertions
grep -q 'WEBCOMPANION_EVENT skill=test sid=sid-1 event_id=100' "$OUT" || { echo "FAIL: missing first event banner"; cat "$OUT"; exit 1; }
grep -q 'WEBCOMPANION_EVENT skill=test sid=sid-1 event_id=200' "$OUT" || { echo "FAIL: missing second event banner"; cat "$OUT"; exit 1; }
grep -q 'WEBCOMPANION_FINISHED skill=test sid=sid-1' "$OUT" || { echo "FAIL: missing finished banner"; cat "$OUT"; exit 1; }

# Both events should be moved to CONSUMED
test -f "$CONSUMED/100.json" || { echo "FAIL: 100.json not in consumed"; exit 1; }
test -f "$CONSUMED/200.json" || { echo "FAIL: 200.json not in consumed"; exit 1; }

echo "watcher.sh OK"
