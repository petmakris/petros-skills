"""Unified-diff parser + gh-CLI fetcher for interactive-review.

The skill snapshots a PR's diff at session-open time. The diff comes from
`gh pr diff` (raw unified-diff format). We parse it into a typed structure
(FileChange / Hunk / Line) the renderer can consume directly.

Line anchors used by the rest of the skill have the form:
    <path>:<side>:<linenum>           e.g. src/server.py:R:42
    <path>:<side>:<start>-<end>       e.g. src/server.py:R:42-58

`side` is L (base/left) or R (head/right).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Line:
    side: str             # "context" | "added" | "removed"
    old: int | None
    new: int | None
    text: str


@dataclass
class Hunk:
    old_start: int
    old_lines: int
    new_start: int
    new_lines: int
    lines: list[Line] = field(default_factory=list)


@dataclass
class FileChange:
    path: str
    hunks: list[Hunk] = field(default_factory=list)


_HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def parse_unified_diff(text: str) -> list[FileChange]:
    files: list[FileChange] = []
    current_file: FileChange | None = None
    current_hunk: Hunk | None = None
    old_ln = 0
    new_ln = 0

    for raw in text.splitlines():
        if raw.startswith("diff --git "):
            if current_file is not None:
                files.append(current_file)
            current_file = FileChange(path="")
            current_hunk = None
            continue
        if raw.startswith("+++ "):
            tail = raw[4:].strip()
            if tail == "/dev/null":
                if current_file:
                    current_file.path = "/dev/null"
            else:
                if tail.startswith("b/"):
                    tail = tail[2:]
                if current_file is not None:
                    current_file.path = tail
            continue
        if raw.startswith("--- ") or raw.startswith("index "):
            continue
        if raw.startswith("Binary files "):
            continue
        m = _HUNK_RE.match(raw)
        if m:
            old_start = int(m.group(1))
            old_lines = int(m.group(2)) if m.group(2) else 1
            new_start = int(m.group(3))
            new_lines = int(m.group(4)) if m.group(4) else 1
            current_hunk = Hunk(old_start=old_start, old_lines=old_lines,
                                new_start=new_start, new_lines=new_lines)
            old_ln = old_start
            new_ln = new_start
            if current_file is not None:
                current_file.hunks.append(current_hunk)
            continue
        if current_hunk is None:
            continue
        if raw.startswith("+"):
            current_hunk.lines.append(Line(side="added", old=None, new=new_ln, text=raw[1:]))
            new_ln += 1
        elif raw.startswith("-"):
            current_hunk.lines.append(Line(side="removed", old=old_ln, new=None, text=raw[1:]))
            old_ln += 1
        elif raw.startswith(" ") or raw == "":
            text_body = raw[1:] if raw.startswith(" ") else ""
            current_hunk.lines.append(Line(side="context", old=old_ln, new=new_ln, text=text_body))
            old_ln += 1
            new_ln += 1
        elif raw.startswith("\\"):
            continue
    if current_file is not None:
        files.append(current_file)
    return [f for f in files if f.path and f.path != "/dev/null"]


def files_to_json(files: list[FileChange]) -> list[dict]:
    out = []
    for f in files:
        added = sum(1 for h in f.hunks for l in h.lines if l.side == "added")
        removed = sum(1 for h in f.hunks for l in h.lines if l.side == "removed")
        out.append({
            "path": f.path,
            "added": added,
            "removed": removed,
            "hunks": [
                {
                    "old_start": h.old_start, "old_lines": h.old_lines,
                    "new_start": h.new_start, "new_lines": h.new_lines,
                    "lines": [
                        {"side": l.side, "old": l.old, "new": l.new, "text": l.text}
                        for l in h.lines
                    ],
                }
                for h in f.hunks
            ],
        })
    return out


# A pr_ref reaches `gh` argv verbatim, and session-create is a network-facing
# endpoint: constrain it to the three documented shapes (number, github.com PR
# URL, git branch ref) so `--web`-style flag injection and shell-odd bytes
# never reach the subprocess.
_PR_URL_RE = re.compile(r"^https://github\.com/[\w.-]+/[\w.-]+/pull/\d+$")
_BRANCH_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]*$")

# gh talks to the network; git is local. A hung remote call must not pin an
# HTTP worker thread forever (the skill-side curl gives up long before this).
_GH_TIMEOUT = 60
_GIT_TIMEOUT = 30


def validate_pr_ref(pr_ref: str) -> str:
    if pr_ref.isdigit() or _PR_URL_RE.match(pr_ref) or _BRANCH_RE.match(pr_ref):
        return pr_ref
    raise ValueError(f"invalid PR ref: {pr_ref!r} (expected number, "
                     "github.com PR URL, or branch name)")


def _pr_meta(pr_ref: str, cwd: str | None = None) -> dict:
    meta_json = subprocess.check_output(
        ["gh", "pr", "view", pr_ref, "--json", "title,headRefName,baseRefName,author,url,headRefOid"],
        text=True, cwd=cwd, timeout=_GH_TIMEOUT,
    )
    return json.loads(meta_json)


def _rev_exists(ref: str, cwd: str | None = None) -> bool:
    try:
        subprocess.check_output(["git", "rev-parse", "--verify", ref],
                                text=True, stderr=subprocess.DEVNULL, cwd=cwd,
                                timeout=_GIT_TIMEOUT)
        return True
    except subprocess.CalledProcessError:
        return False


def _local_git_diff(meta: dict, cwd: str | None = None) -> str:
    """Fallback for PRs too large for `gh pr diff` (GitHub caps at 300 files).

    Builds the diff locally with three-dot semantics against the PR's base,
    excluding generated bulk files (overridable via INTERACTIVE_REVIEW_LOCAL_EXCLUDE,
    a comma-separated list of pathspec globs; default excludes *.json).
    """
    base = meta.get("baseRefName") or "master"
    base_ref = f"origin/{base}" if _rev_exists(f"origin/{base}", cwd) else base
    head = next((h for h in (meta.get("headRefName"), meta.get("headRefOid"), "HEAD")
                 if h and _rev_exists(h, cwd)), "HEAD")
    exclude_env = os.environ.get("INTERACTIVE_REVIEW_LOCAL_EXCLUDE", "*.json")
    excludes = [g.strip() for g in exclude_env.split(",") if g.strip()]
    args = ["git", "-c", "color.ui=false", "diff", "--no-color",
            f"{base_ref}...{head}", "--", "."]
    args += [f":(exclude){g}" for g in excludes]
    # errors="replace": diffs may contain non-UTF-8 bytes (e.g. latin-1 .properties files)
    return subprocess.check_output(args, text=True, errors="replace", cwd=cwd,
                                   timeout=_GIT_TIMEOUT)


def fetch_pr_diff(pr_ref: str, cwd: str | None = None) -> tuple[str, dict]:
    validate_pr_ref(pr_ref)
    meta = _pr_meta(pr_ref, cwd)
    try:
        diff = subprocess.check_output(
            ["gh", "pr", "diff", pr_ref, "--patch"], text=True, errors="replace",
            stderr=subprocess.DEVNULL, cwd=cwd, timeout=_GH_TIMEOUT,
        )
    except subprocess.CalledProcessError:
        # gh refuses diffs over 300 files (HTTP 406). Fall back to local git,
        # filtered to non-generated files.
        diff = _local_git_diff(meta, cwd)
    return diff, meta
