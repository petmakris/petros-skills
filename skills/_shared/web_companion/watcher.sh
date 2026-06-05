#!/usr/bin/env bash
# Persistent per-session watcher.  Emits one stdout banner per event in
# $EVENTS_DIR; exits when $STATE_DIR/finished or $STATE_DIR/cancelled exists.
#
# Required env:
#   SKILL, SID, STATE_DIR, EVENTS_DIR, CONSUMED_DIR

set -u

: "${SKILL:?}"; : "${SID:?}"; : "${STATE_DIR:?}"; : "${EVENTS_DIR:?}"; : "${CONSUMED_DIR:?}"

mkdir -p "$EVENTS_DIR" "$CONSUMED_DIR"

while [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; do
  date +%s > "$STATE_DIR/watcher_heartbeat"
  evt=$(ls "$EVENTS_DIR"/*.json 2>/dev/null | sort | head -n1)
  if [ -n "$evt" ]; then
    id=$(basename "$evt" .json)
    if [ ! -f "$CONSUMED_DIR/$id.ack" ]; then
      printf 'WEBCOMPANION_EVENT skill=%s sid=%s event_id=%s\n' "$SKILL" "$SID" "$id"
      printf '%s\n' '---payload---'
      cat "$evt"
      printf '\n%s\n' '---end---'
      for _ in $(seq 1 1800); do
        if [ -f "$CONSUMED_DIR/$id.ack" ]; then break; fi
        if [ -f "$STATE_DIR/finished" ] || [ -f "$STATE_DIR/cancelled" ]; then break; fi
        # Keep the heartbeat fresh while blocked on the ack, otherwise
        # /poll's watcher_seen_at goes stale for up to 30 min and the page
        # would wrongly look like the watcher died.
        date +%s > "$STATE_DIR/watcher_heartbeat"
        sleep 1
      done
      if [ ! -f "$CONSUMED_DIR/$id.ack" ] && [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; then
        touch "$CONSUMED_DIR/$id.failed"
      fi
    fi
    mv -f "$evt" "$CONSUMED_DIR/$id.json"
  else
    sleep 1
  fi
done

if [ -f "$STATE_DIR/cancelled" ]; then
  printf 'WEBCOMPANION_CANCELLED skill=%s sid=%s\n' "$SKILL" "$SID"
else
  printf 'WEBCOMPANION_FINISHED skill=%s sid=%s\n' "$SKILL" "$SID"
fi
