#!/usr/bin/env bash
# Persistent per-session watcher.  Emits one stdout banner per event in
# $EVENTS_DIR; exits when $STATE_DIR/finished or $STATE_DIR/cancelled exists.
#
# Required env:
#   SKILL, SID, STATE_DIR, EVENTS_DIR, CONSUMED_DIR
#
# Optional env:
#   CLAUDE_SID - the arming Claude Code session's id. When set, each
#     heartbeat also writes $STATE_DIR/watchers/$CLAUDE_SID.hb, so
#     the server can count distinct live Claude sessions attached to
#     one shared workspace (see attached_count in annotate/server.py).
#     Unset is fine — the watcher still works, it just won't be counted.

set -u

: "${SKILL:?}"; : "${SID:?}"; : "${STATE_DIR:?}"; : "${EVENTS_DIR:?}"; : "${CONSUMED_DIR:?}"

mkdir -p "$EVENTS_DIR" "$CONSUMED_DIR"

while [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; do
  date +%s > "$STATE_DIR/watcher_heartbeat"
  if [ -n "${CLAUDE_SID:-}" ]; then
    mkdir -p "$STATE_DIR/watchers"
    date +%s > "$STATE_DIR/watchers/$CLAUDE_SID.hb"
  fi
  # Fixed-width event-id filenames sort chronologically (see events.append).
  evt=$(ls "$EVENTS_DIR"/*.json 2>/dev/null | sort | head -n1)
  if [ -n "$evt" ]; then
    id=$(basename "$evt" .json)
    if [ -f "$CONSUMED_DIR/$id.ack" ]; then
      # Already acked (e.g. a re-emitted event that has since been handled).
      rm -f "$CONSUMED_DIR/$id.attempts"
      mv -f "$evt" "$CONSUMED_DIR/$id.json"
    else
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
        if [ -n "${CLAUDE_SID:-}" ]; then
          mkdir -p "$STATE_DIR/watchers"
          date +%s > "$STATE_DIR/watchers/$CLAUDE_SID.hb"
        fi
        sleep 1
      done
      if [ -f "$CONSUMED_DIR/$id.ack" ]; then
        rm -f "$CONSUMED_DIR/$id.attempts"
        mv -f "$evt" "$CONSUMED_DIR/$id.json"
      elif [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; then
        # Ack timed out. Re-emit on a later loop instead of silently dropping
        # the user's request — downstream dedups by source_event_id, so re-emit
        # is safe. Bound the attempts so one perpetually-unanswered event can't
        # wedge the (serially-processed) queue behind it forever.
        n=$(cat "$CONSUMED_DIR/$id.attempts" 2>/dev/null || echo 0)
        n=$((n + 1))
        if [ "$n" -ge "${WEBCOMPANION_MAX_EMITS:-3}" ]; then
          rm -f "$CONSUMED_DIR/$id.attempts"
          mv -f "$evt" "$CONSUMED_DIR/$id.json"
        else
          echo "$n" > "$CONSUMED_DIR/$id.attempts"
        fi
      fi
    fi
  else
    sleep 1
  fi
done

if [ -f "$STATE_DIR/cancelled" ]; then
  printf 'WEBCOMPANION_CANCELLED skill=%s sid=%s\n' "$SKILL" "$SID"
else
  printf 'WEBCOMPANION_FINISHED skill=%s sid=%s\n' "$SKILL" "$SID"
fi
