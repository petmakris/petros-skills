from pathlib import Path

SKILL = Path(__file__).resolve().parent.parent / "SKILL.md"

REQUIRED_SECTIONS = [
    "## Invocation",
    "## On every invocation: ensure the server is running",
    "## Create a session",
    "## Generate the steps",
    "## Generation contract",
    "## Arm the watcher",
    "## Mode D — handling a watcher event",
    "## Response style guide",
    "## Edge cases",
]


def test_frontmatter_declares_name_and_description():
    text = SKILL.read_text()
    assert text.startswith("---\n")
    frontmatter = text.split("---", 2)[1]
    assert "name: walkthrough" in frontmatter
    assert "description:" in frontmatter


def test_required_sections_present():
    text = SKILL.read_text()
    missing = [s for s in REQUIRED_SECTIONS if s not in text]
    assert missing == [], f"SKILL.md missing sections: {missing}"


def test_generation_contract_states_the_hard_rules():
    text = SKILL.read_text()
    for rule in ["5–12 steps", "snippet", "execution order", "edit-site", "cross-block"]:
        assert rule.lower() in text.lower(), f"generation contract missing rule: {rule}"


def test_documents_step_anchor_form():
    assert "step:<id>" in SKILL.read_text()
