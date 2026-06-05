"""Per-block version chain — server-derived versioning.

A block's `version` is *not* something Claude writes into blocks.json. It is
computed on every read by hashing the block's content (normalized for
cosmetic noise) and comparing against a per-block hash chain stored in a
sidecar `versions.json` alongside `blocks.json`. The reported version is
the length of that chain — a value that can only grow when the content
actually changes.

This kills two failure modes at once:
  (a) Claude rewriting unrelated blocks bumps their versions for no reason.
  (b) Cosmetic byte-churn (trailing whitespace, re-flowed inter-tag
      whitespace in HTML) bumps versions even though the prose is
      unchanged.

The chain is the single source of truth. The on-disk `version` field on
blocks (if present from older data or from Claude habit) is ignored.

The hash function is the single knob for future cosmetic-noise filters —
add normalisation here, not in higher layers.
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from skills._shared.web_companion.atomic import write_text_atomic


_INTER_TAG_WS = re.compile(r">\s+<")
_TRAILING_WS = re.compile(r"[ \t]+$", re.MULTILINE)


def _canonical_spec(spec: dict[str, Any]) -> str:
    """Stable JSON serialization — must match blocks._canonical_spec."""
    return json.dumps(spec, sort_keys=True, separators=(",", ":"))


def _normalize_markdown(s: str) -> str:
    """Cosmetic-noise filter for markdown/HTML block content.

    Strip edge whitespace, drop trailing per-line whitespace, collapse any
    run of whitespace between `>` and `<` to a single space so a re-flowed
    HTML block with identical visual structure hashes identically. Plain
    markdown without inline HTML is unaffected by the inter-tag rule.
    """
    s = s.strip()
    s = _TRAILING_WS.sub("", s)
    s = _INTER_TAG_WS.sub("><", s)
    return s


def _block_hash(blk: dict[str, Any]) -> str:
    """SHA1 of (kind, normalized-content)."""
    kind = blk.get("kind") or "markdown"
    if kind == "sequence":
        body = _canonical_spec(blk.get("spec") or {})
    else:
        body = _normalize_markdown(blk.get("markdown") or "")
    h = hashlib.sha1()
    h.update(kind.encode("utf-8") + b"\x00" + body.encode("utf-8"))
    return h.hexdigest()


def _load_chain(path: Path) -> dict[str, list[str]]:
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return {}
    if not isinstance(raw, dict):
        return {}
    out: dict[str, list[str]] = {}
    for k, v in raw.items():
        if isinstance(k, str) and isinstance(v, list) and all(isinstance(x, str) for x in v):
            out[k] = list(v)
    return out


def _save_chain_atomic(path: Path, chain: dict[str, list[str]]) -> None:
    write_text_atomic(path, json.dumps(chain, indent=2))


def derive_versions(versions_path: Path, blocks: list[dict[str, Any]]) -> dict[str, int]:
    """Return {block_id: version} derived from the chain at versions_path.

    Appends a new hash to a block's chain when the block's current hash
    differs from the chain's tail. Writes the sidecar atomically only if
    any chain grew. Concurrent calls converge: both see the same tail,
    both append the same hash, last-writer-wins yields identical state.

    Chains for blocks no longer present in the input are PRUNED. This is
    required for correctness: next_block_id() remints the smallest free id,
    so a deleted id can be reused by an unrelated block; if its old chain
    lingered, the new block would inherit a stale version (and could even be
    reported unchanged if its content happened to hash-match the old tail).
    Pruning makes a reused id start fresh at version 1.
    """
    chain = _load_chain(versions_path)
    changed = False
    current_ids = {blk.get("id") for blk in blocks if isinstance(blk.get("id"), str)}
    for stale_id in [k for k in chain if k not in current_ids]:
        del chain[stale_id]
        changed = True
    for blk in blocks:
        bid = blk.get("id")
        if not isinstance(bid, str):
            continue
        h = _block_hash(blk)
        history = chain.setdefault(bid, [])
        if not history or history[-1] != h:
            history.append(h)
            changed = True
    if changed:
        _save_chain_atomic(versions_path, chain)
    out: dict[str, int] = {}
    for blk in blocks:
        bid = blk.get("id")
        if isinstance(bid, str):
            out[bid] = len(chain.get(bid, [])) or 1
    return out
