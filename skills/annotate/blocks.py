"""blocks.json document model — annotate's canonical doc structure.

Schema:
    {
      "response_id": str,
      "title": str,
      "blocks": [{"id": "section-N", "title": str, "markdown": str, ...}, ...]
    }

Per-block versions are NOT stored here. They are derived from a
per-block content-hash chain in `versions.json` (see versions.py). The
legacy `version` field on blocks (if present from older data or from
Claude habit) is stripped on save and ignored on read.

Block ids are stable for the session — minted once via next_block_id(),
never reassigned. update_block() is a no-op when content is unchanged
(content-hash dedup for re-apply safety).
"""
from __future__ import annotations

import json
import re as _re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from skills._shared.web_companion.atomic import write_text_atomic


@dataclass
class BlocksDoc:
    response_id: str = ""
    title: str = ""
    blocks: list[dict[str, Any]] = field(default_factory=list)
    glossary: list[dict[str, Any]] = field(default_factory=list)


def load(path: Path) -> BlocksDoc:
    path = Path(path)
    if not path.exists():
        return BlocksDoc()
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return BlocksDoc()
    # Drop markdown blocks with no content — a blank card is pure noise (one
    # real push shipped a 0-char untitled block). Spec blocks (kind set) carry
    # their content in `spec`, so they pass regardless of markdown.
    blocks = [
        b for b in (raw.get("blocks") or [])
        if b.get("kind") or (b.get("markdown") or "").strip()
    ]
    return BlocksDoc(
        response_id=raw.get("response_id", ""),
        title=raw.get("title", ""),
        blocks=blocks,
        glossary=list(raw.get("glossary") or []),
    )


def save_atomic(path: Path, doc: BlocksDoc) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    cleaned = [{k: v for k, v in b.items() if k != "version"} for b in doc.blocks]
    out: dict[str, Any] = {
        "response_id": doc.response_id,
        "title": doc.title,
        "blocks": cleaned,
    }
    if doc.glossary:
        out["glossary"] = doc.glossary
    write_text_atomic(path, json.dumps(out, indent=2))


def find_block(doc: BlocksDoc, block_id: str) -> dict[str, Any]:
    for b in doc.blocks:
        if b.get("id") == block_id:
            return b
    raise KeyError(block_id)


def update_block(doc: BlocksDoc, block_id: str, new_markdown: str) -> bool:
    """Update a block's markdown. Returns True if content changed.

    No version field is mutated — versions are derived in versions.py.
    Content-hash dedup makes a no-op rewrite a true no-op.
    """
    b = find_block(doc, block_id)
    if b.get("markdown") == new_markdown:
        return False
    b["markdown"] = new_markdown
    return True


def next_block_id(doc: BlocksDoc) -> str:
    """Mint a fresh block id never used in this doc.

    Returns "section-N" where N is the smallest positive integer (1-based) not
    currently used as a block id. The trailing-integer match also accounts for
    any legacy "b-N" ids so a mixed doc never collides.
    """
    used = set()
    for b in doc.blocks:
        m = _re.search(r"(\d+)$", b.get("id", ""))
        if m:
            used.add(int(m.group(1)))
    n = 1
    while n in used:
        n += 1
    return f"section-{n}"


def _canonical_spec(spec: dict[str, Any]) -> str:
    """Stable JSON serialization for content-hash dedup."""
    return json.dumps(spec, sort_keys=True, separators=(",", ":"))


def update_spec_block(doc: BlocksDoc, block_id: str, new_spec: dict[str, Any]) -> bool:
    """Update a sequence block's spec. Returns True if content changed.

    No version field is mutated — versions are derived in versions.py.
    Canonical-JSON compare so reordered keys are a no-op.
    """
    b = find_block(doc, block_id)
    if _canonical_spec(b.get("spec") or {}) == _canonical_spec(new_spec):
        return False
    b["spec"] = new_spec
    return True


def next_step_id(spec: dict[str, Any]) -> str:
    """Mint a fresh step id never used in this spec. Returns 'sN'."""
    used: set[int] = set()
    for s in (spec.get("steps") or []):
        sid = s.get("id", "")
        if sid.startswith("s"):
            try:
                used.add(int(sid[1:]))
            except ValueError:
                pass
    n = 1
    while n in used:
        n += 1
    return f"s{n}"


def choice_option_ids(spec: dict[str, Any]) -> list[str]:
    """Return the option ids of a choice block's spec, in order."""
    return [o.get("id") for o in (spec.get("options") or []) if o.get("id")]


def validate_choice_selection(spec: dict[str, Any], selected: Any) -> str | None:
    """Validate a submitted selection against a choice spec.

    Returns None when valid, else a short human-readable error string.
    """
    if not isinstance(selected, list) or not all(isinstance(s, str) for s in selected):
        return "selected_options must be a list of strings"
    if not selected:
        return "selected_options must not be empty"
    valid = set(choice_option_ids(spec))
    unknown = [s for s in selected if s not in valid]
    if unknown:
        return f"unknown option id(s): {', '.join(unknown)}"
    if not spec.get("multiSelect") and len(selected) != 1:
        return "single-select choice requires exactly one option"
    return None


def convert_block_to_markdown(doc: BlocksDoc, block_id: str, new_markdown: str) -> bool:
    """Resolve a block to a markdown block: set markdown, drop kind/spec.

    Returns True if the block changed. Used when a choice block is resolved
    into a decision paragraph after the user picks.
    """
    b = find_block(doc, block_id)
    already_md = (b.get("kind") or "markdown") == "markdown"
    if already_md and b.get("markdown") == new_markdown:
        return False
    b.pop("kind", None)
    b.pop("spec", None)
    b["markdown"] = new_markdown
    return True


def remove_block(doc: BlocksDoc, block_id: str) -> bool:
    """Remove a block by id. Returns True if a block was removed.

    No-op (returns False) when the id is absent — required for watcher
    re-apply safety, where a dismiss event may be re-emitted after the
    block is already gone. Version chains for removed ids are pruned by
    versions.derive_versions on the next read, so a later reused id starts
    fresh at v1.
    """
    before = len(doc.blocks)
    doc.blocks = [b for b in doc.blocks if b.get("id") != block_id]
    return len(doc.blocks) != before


def _term_appears(term: str, text: str) -> bool:
    """Case-sensitive whole-word match for `term` in `text`.

    Case-sensitive on purpose: popover.js decorates and looks up terms
    case-sensitively, so the drop rule must agree — otherwise a term kept
    here (because a lowercased form appears) would never get a tooltip.
    """
    if not term:
        return False
    pattern = r"(?<![A-Za-z0-9_])" + _re.escape(term) + r"(?![A-Za-z0-9_])"
    return _re.search(pattern, text) is not None


def drop_unused_terms(doc: BlocksDoc) -> bool:
    """Remove glossary entries whose `term` does not appear in any block.

    Returns True if any entries were dropped.
    """
    # Concatenate markdown from all markdown blocks; sequence-block specs
    # are skipped (terms in spec labels are out of scope for v1).
    haystack = "\n".join(b.get("markdown", "") for b in doc.blocks
                         if (b.get("kind") or "markdown") == "markdown")
    kept = [g for g in doc.glossary if _term_appears(g.get("term", ""), haystack)]
    if len(kept) == len(doc.glossary):
        return False
    doc.glossary = kept
    return True
