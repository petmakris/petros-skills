"""steps.json persistence for the walkthrough skill.

One document per session at <state_dir>/steps.json:

    {
      "question": "...",          # the user's question, verbatim
      "kind": "explain"|"diff",
      "generated_ts": <int>,       # epoch seconds, bumped only on (re)generation
      "steps": [
        {"id": 1, "title": "...", "file": "rel/path.java", "line": 42,
         "snippet": "<verbatim line text>", "role": "context|seam|edit-site",
         "markdown": "..."}
      ]
    }

Steps are frozen once written — only per-step threads change afterwards. The
document is written atomically so a reader never sees a half-written file.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from skills._shared.web_companion.atomic import write_text_atomic

STEP_ROLES = frozenset({"context", "seam", "edit-site"})
DOC_KINDS = frozenset({"explain", "diff"})
MIN_STEPS = 1        # server-side floor; the 5-12 range is a SKILL.md rule
MAX_STEPS = 24       # hard ceiling — a runaway generator is a bug, not a tour

_ANCHOR_RE = re.compile(r"^step:([1-9]\d*)$")
STEPS_FILE = "steps.json"


def validate(doc: dict) -> list[str]:
    """Return a list of human-readable problems; empty means valid."""
    errors: list[str] = []
    if not isinstance(doc, dict):
        return ["document must be an object"]
    if not isinstance(doc.get("question"), str) or not doc["question"].strip():
        errors.append("question must be a non-empty string")
    if doc.get("kind") not in DOC_KINDS:
        errors.append(f"kind must be one of {sorted(DOC_KINDS)}")
    if not isinstance(doc.get("generated_ts"), int) or doc["generated_ts"] <= 0:
        errors.append("generated_ts must be a positive integer")
    steps = doc.get("steps")
    if not isinstance(steps, list):
        return errors + ["steps must be a list"]
    if len(steps) < MIN_STEPS:
        errors.append(f"steps must contain at least {MIN_STEPS} step")
    if len(steps) > MAX_STEPS:
        errors.append(f"steps must contain at most {MAX_STEPS} steps")
    seen_ids: set[int] = set()
    for i, s in enumerate(steps):
        where = f"step[{i}]"
        if not isinstance(s, dict):
            errors.append(f"{where} must be an object")
            continue
        sid = s.get("id")
        if not isinstance(sid, int) or isinstance(sid, bool) or sid < 1:
            errors.append(f"{where} id must be a positive integer")
        elif sid in seen_ids:
            errors.append(f"{where} duplicate id {sid}")
        else:
            seen_ids.add(sid)
        title = s.get("title")
        if not isinstance(title, str) or not title.strip():
            errors.append(f"{where} title must be a non-empty string")
        errors.extend(_file_errors(where, s.get("file")))
        line = s.get("line")
        if not isinstance(line, int) or isinstance(line, bool) or line < 1:
            errors.append(f"{where} line must be a positive integer")
        snippet = s.get("snippet")
        if not isinstance(snippet, str) or not snippet.strip():
            errors.append(f"{where} snippet must be non-empty (it anchors the step)")
        if s.get("role") not in STEP_ROLES:
            errors.append(f"{where} role must be one of {sorted(STEP_ROLES)}")
        markdown = s.get("markdown")
        if not isinstance(markdown, str) or not markdown.strip():
            errors.append(f"{where} markdown must be a non-empty string")
    return errors


def _file_errors(where: str, file: object) -> list[str]:
    if not isinstance(file, str) or not file.strip():
        return [f"{where} file must be a non-empty project-relative path"]
    if file.startswith("/"):
        return [f"{where} file must be project-relative, not absolute"]
    parts = file.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return [f"{where} file must not contain empty or dot path segments"]
    return []


def write_steps(state_dir: Path, doc: dict) -> None:
    """Validate and atomically write <state_dir>/steps.json. Raises ValueError."""
    errors = validate(doc)
    if errors:
        raise ValueError("; ".join(errors))
    write_text_atomic(Path(state_dir) / STEPS_FILE, json.dumps(doc, indent=2))


def load_steps(state_dir: Path) -> dict | None:
    p = Path(state_dir) / STEPS_FILE
    try:
        doc = json.loads(p.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None
    return doc if isinstance(doc, dict) else None


def generated_ts(state_dir: Path) -> int:
    doc = load_steps(state_dir)
    if not doc:
        return 0
    ts = doc.get("generated_ts")
    return ts if isinstance(ts, int) and not isinstance(ts, bool) else 0


def step_anchor(step_id: int) -> str:
    return f"step:{step_id}"


def valid_anchor(anchor: str) -> bool:
    return isinstance(anchor, str) and _ANCHOR_RE.match(anchor) is not None


def anchor_step_id(anchor: str) -> int | None:
    m = _ANCHOR_RE.match(anchor) if isinstance(anchor, str) else None
    return int(m.group(1)) if m else None
