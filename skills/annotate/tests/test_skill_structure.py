"""Structural guards for the progressive-disclosure layout of the annotate skill.

SKILL.md is a lean router that points at reference files loaded on demand. These
tests fail loudly if a referenced file goes missing, a reference file becomes an
orphan, or a block kind is added without wiring its reference into the menu — the
failure modes that silently break progressive disclosure as the skill scales.
"""
from __future__ import annotations

import re
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parents[1]
SKILL_MD = SKILL_DIR / "SKILL.md"

# Matches in-skill relative links like references/pushing.md,
# references/block-kinds/diagram.md, docs/token-budget.md.
LINK_RE = re.compile(r"(?:references|docs)/[A-Za-z0-9_./-]+\.md")


def _links_in(path: Path) -> set[str]:
    return set(LINK_RE.findall(path.read_text(encoding="utf-8")))


def test_skill_md_exists_and_is_lean():
    assert SKILL_MD.is_file()
    # The router must stay small; if it creeps back toward the old monolith,
    # this trips so we re-split. (Old monolith was ~500 lines.)
    assert SKILL_MD.read_text(encoding="utf-8").count("\n") < 120


def test_all_referenced_files_exist():
    # Every references/… or docs/… link anywhere in the skill must resolve.
    files = [SKILL_MD, *SKILL_DIR.glob("references/**/*.md")]
    missing = []
    for f in files:
        for link in _links_in(f):
            if not (SKILL_DIR / link).is_file():
                missing.append(f"{f.relative_to(SKILL_DIR)} -> {link}")
    assert not missing, f"broken in-skill links: {missing}"


def test_no_orphan_reference_files():
    # Every references/**/*.md must be reachable from SKILL.md transitively.
    reachable: set[str] = set()
    frontier = {l for l in _links_in(SKILL_MD) if l.startswith("references/")}
    while frontier:
        link = frontier.pop()
        if link in reachable:
            continue
        reachable.add(link)
        target = SKILL_DIR / link
        if target.is_file():
            for nxt in _links_in(target):
                if nxt.startswith("references/") and nxt not in reachable:
                    frontier.add(nxt)
    on_disk = {
        str(p.relative_to(SKILL_DIR)) for p in SKILL_DIR.glob("references/**/*.md")
    }
    orphans = on_disk - reachable
    assert not orphans, f"reference files not reachable from SKILL.md: {orphans}"


def test_block_kind_menu_matches_reference_files():
    text = SKILL_MD.read_text(encoding="utf-8")
    menu_refs = set(re.findall(r"references/block-kinds/([A-Za-z0-9_-]+)\.md", text))
    on_disk = {p.stem for p in (SKILL_DIR / "references" / "block-kinds").glob("*.md")}
    # Every block-kind reference on disk is wired into the menu, and every menu
    # entry has a file. (Markdown is the default kind and has no block-kinds file.)
    assert menu_refs == on_disk, (
        f"menu refs {menu_refs} != block-kinds files {on_disk}"
    )


def test_phase_map_points_at_both_lifecycles():
    text = SKILL_MD.read_text(encoding="utf-8")
    assert "references/pushing.md" in text
    assert "references/handling-events.md" in text
