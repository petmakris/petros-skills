"""Geometry invariants for rendered sequence diagrams.

Same idea as the flowchart geometry suite: assert on the rendered SVG, so a
label that no longer fits its pill fails a test instead of quietly overflowing
in the browser.
"""
from __future__ import annotations

import xml.etree.ElementTree as ET

import pytest

from skills.annotate.diagrams.sequence import render
from skills.annotate.diagrams.text_metrics import text_px


def _spec(labels: list[str]) -> dict:
    actors = [{"id": f"a{i}", "label": lb} for i, lb in enumerate(labels)]
    steps = [{"id": "s1", "from": "a0", "to": f"a{len(labels) - 1}",
              "arrow": "request", "label": "call", "sub": "with payload"}]
    return {"actors": actors, "steps": steps}


CASES = {
    "short": ["UI", "API"],
    "typical": ["Browser", "ProposalService", "Repository"],
    "long_labels": ["ProposalLifecycleActionsService", "LifeCycleTaskRepository",
                    "NotificationDispatcher"],
    "many": [f"Actor{i}" for i in range(6)],
}


def _parse(svg: str):
    root = ET.fromstring(svg)
    vw, vh = [float(v) for v in root.get("viewBox").split()][2:]
    pills, labels = [], []
    for el in root.iter():
        tag = el.tag.rsplit("}", 1)[-1]
        if tag == "rect" and el.get("class") == "actor-pill":
            x, w = float(el.get("x")), float(el.get("width"))
            pills.append((x, x + w))
        elif tag == "text" and el.get("class") == "actor-label":
            labels.append((el.text or "", float(el.get("x"))))
    return vw, vh, pills, labels


@pytest.mark.parametrize("name,lbls", CASES.items(), ids=list(CASES))
def test_actor_label_fits_its_pill(name, lbls):
    _, _, pills, labels = _parse(render(_spec(lbls), "section-1"))
    for (x0, x1), (txt, cx) in zip(pills, labels):
        w = text_px(txt, "actor-label")
        assert cx - w / 2 >= x0 - 0.5 and cx + w / 2 <= x1 + 0.5, \
            f"{name}: actor label {txt!r} overflows its pill"


@pytest.mark.parametrize("name,lbls", CASES.items(), ids=list(CASES))
def test_actor_pills_do_not_touch(name, lbls):
    _, _, pills, _ = _parse(render(_spec(lbls), "section-1"))
    for (a0, a1), (b0, b1) in zip(pills, pills[1:]):
        assert b0 - a1 >= 8, f"{name}: actor pills {a1:.0f} / {b0:.0f} too close"


@pytest.mark.parametrize("name,lbls", CASES.items(), ids=list(CASES))
def test_pills_inside_viewbox(name, lbls):
    vw, _, pills, _ = _parse(render(_spec(lbls), "section-1"))
    for x0, x1 in pills:
        assert x0 >= -0.5 and x1 <= vw + 0.5, f"{name}: pill ({x0},{x1}) outside width {vw}"
