"""One-command reply+ack for watcher-event handling.

SKILL.md used to inline a heredoc that imported append_message and asked the
model to separately touch the ack file — two more prose-enforced invariants.
Now the whole "append my reply to the thread and ack the event" step is:

    PYTHONPATH="$PLUGIN_ROOT" STATE_DIR="$STATE_DIR" \
      python3 -m skills._shared.web_companion.reply_cli --ack "$EVENT_ID"

Inputs (written with the Write tool, never shell-interpolated):
    <state_dir>/.reply.md         — the reply markdown, verbatim
    <state_dir>/.reply.meta.json  — {"anchor": ..., "title": ..., "source_event_id": ...}

--ack writes <state_dir>/consumed/<event_id>.ack after the append succeeds,
so a crashed append leaves the event un-acked and the watcher re-emits it
(append_message dedups by source_event_id, making the retry safe).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from skills._shared.web_companion.threads import append_message


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="reply_cli", description=__doc__)
    parser.add_argument("--state-dir", default=os.environ.get("STATE_DIR"),
                        help="session state dir (default: $STATE_DIR)")
    parser.add_argument("--ack", metavar="EVENT_ID", default=None,
                        help="also ack this event id after appending")
    parser.add_argument("--ack-only", action="store_true",
                        help="write the ack without appending a reply "
                             "(malformed-payload path)")
    args = parser.parse_args(argv)

    if not args.state_dir:
        print("reply_cli: no state dir (pass --state-dir or set STATE_DIR)",
              file=sys.stderr)
        return 2
    state_dir = Path(args.state_dir)
    if not state_dir.is_dir():
        print(f"reply_cli: state dir does not exist: {state_dir}", file=sys.stderr)
        return 2

    if not args.ack_only:
        try:
            meta = json.loads((state_dir / ".reply.meta.json").read_text())
            text = (state_dir / ".reply.md").read_text()
        except (FileNotFoundError, json.JSONDecodeError) as e:
            print(f"reply_cli: bad reply inputs: {e}", file=sys.stderr)
            return 2
        anchor = meta.get("anchor")
        if not isinstance(anchor, str) or not anchor:
            print("reply_cli: .reply.meta.json missing anchor", file=sys.stderr)
            return 2
        append_message(state_dir / "threads", anchor, {
            "role": "claude",
            "ts": int(time.time()),
            "text": text,
            "source_event_id": meta.get("source_event_id"),
        }, title=(meta.get("title") or None))

    if args.ack or args.ack_only:
        event_id = args.ack or ""
        if not event_id:
            print("reply_cli: --ack-only requires --ack EVENT_ID", file=sys.stderr)
            return 2
        consumed = state_dir / "consumed"
        consumed.mkdir(parents=True, exist_ok=True)
        (consumed / f"{event_id}.ack").touch()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
