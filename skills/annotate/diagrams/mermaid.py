"""Mermaid-backed diagram validator + server-side SVG renderer.

Renders a `kind: "diagram"` block's Mermaid source to SVG via the `mmdc`
CLI (mermaid-cli). Called by server.py when rendering a block with
kind == "diagram". Unlike diagrams/sequence.py (pure functions), render()
does subprocess + temp-file I/O because layout is delegated to an external
engine; that I/O is isolated entirely within this module.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from typing import Any

SUPPORTED_TYPES = ("flowchart", "architecture", "state", "er", "class")

# mmdc spawns headless Chrome; a generous ceiling so a hung render can't
# wedge the request thread forever.
RENDER_TIMEOUT_S = 30


class ValidationError(ValueError):
    """Raised when a diagram spec is structurally invalid."""


class RenderError(RuntimeError):
    """Raised when mmdc is missing or fails to produce an SVG."""


def validate(spec: dict[str, Any]) -> None:
    """Raise ValidationError if the spec is malformed; otherwise return None."""
    dtype = spec.get("type")
    if dtype not in SUPPORTED_TYPES:
        raise ValidationError(
            f"unknown diagram type {dtype!r}; expected one of {SUPPORTED_TYPES}"
        )
    source = spec.get("source")
    if not isinstance(source, str) or not source.strip():
        raise ValidationError("diagram source must be a non-empty string")


def render(spec: dict[str, Any], block_id: str) -> str:
    """Render a validated spec to an SVG string.

    Raises ValidationError if the spec is malformed, RenderError if mmdc is
    missing or fails. The block_id is accepted for signature parity with
    diagrams/sequence.render (v1 injects no per-node hit targets).
    """
    validate(spec)

    mmdc = shutil.which("mmdc")
    if not mmdc:
        raise RenderError("mmdc (mermaid-cli) not found on PATH")

    # Neutralize inline init directives before render. A `%%{init: {...}}%%`
    # directive in the source can override the config below — including
    # `securityLevel` — so source text could re-enable script-bearing SVG that
    # we then inject as innerHTML. Strip every directive; theme/background are
    # already fixed via CLI flags, so nothing legitimate is lost.
    source = _strip_init_directives(spec["source"])
    with tempfile.TemporaryDirectory() as td:
        in_path = os.path.join(td, "in.mmd")
        out_path = os.path.join(td, "out.svg")
        cfg_path = os.path.join(td, "config.json")
        with open(in_path, "w", encoding="utf-8") as f:
            f.write(source)
        # Force native SVG <text> labels instead of mermaid's default HTML
        # <foreignObject> labels. foreignObject labels carry no baked geometry —
        # they re-layout against the host page's CSS when this SVG is inlined into
        # the annotate card, overflowing the node boxes mmdc measured at render
        # time (text clips at the box edge). <text> labels lock geometry in SVG
        # user-space so the diagram renders identically in any host document.
        #
        # securityLevel:"strict" is mermaid's default, but we pin it explicitly:
        # the rendered SVG is injected into the page as innerHTML, so a config or
        # mmdc default that ever relaxed it would open script injection.
        with open(cfg_path, "w", encoding="utf-8") as f:
            f.write('{"securityLevel": "strict", "htmlLabels": false,'
                    ' "flowchart": {"htmlLabels": false}}')
        try:
            proc = subprocess.run(
                [mmdc, "-i", in_path, "-o", out_path, "-c", cfg_path,
                 "-t", "neutral", "-b", "transparent"],
                capture_output=True,
                text=True,
                timeout=RENDER_TIMEOUT_S,
            )
        except subprocess.TimeoutExpired as e:
            raise RenderError(f"mmdc timed out after {RENDER_TIMEOUT_S}s") from e
        # Require a non-empty file: returncode 0 with a zero-byte out.svg
        # (seen on some mmdc edge cases) would otherwise render as a silent
        # blank diagram instead of the inline error pill.
        if (proc.returncode != 0 or not os.path.exists(out_path)
                or os.path.getsize(out_path) == 0):
            detail = (proc.stderr or proc.stdout or "").strip()
            raise RenderError(f"mmdc failed: {detail or 'no output produced'}")
        with open(out_path, "r", encoding="utf-8") as f:
            svg = f.read()

    return _postprocess(svg)


_XML_PROLOG = re.compile(r"^\s*<\?xml[^>]*\?>\s*", re.IGNORECASE)
_INIT_DIRECTIVE = re.compile(r"%%\{.*?\}%%", re.DOTALL)
_SCRIPT_TAG = re.compile(r"<script\b.*?</script\s*>", re.IGNORECASE | re.DOTALL)


def _strip_init_directives(source: str) -> str:
    """Remove mermaid `%%{ ... }%%` directives (e.g. init) from source.

    These can override the render config — including securityLevel — so they
    are stripped before render. Non-greedy so multiple directives each match.
    """
    return _INIT_DIRECTIVE.sub("", source)


def _postprocess(svg: str) -> str:
    """Strip the XML prolog and tag the root <svg> with our CSS hook class.

    If mmdc already emitted a class attribute on the root <svg>, append to it
    rather than injecting a second (malformed) class attribute.
    """
    svg = _XML_PROLOG.sub("", svg).strip()
    # Defense in depth: this SVG is injected as innerHTML. With securityLevel
    # strict + directives stripped, mmdc never emits <script>, but excise any
    # that slip through so a single regression can't become script execution.
    svg = _SCRIPT_TAG.sub("", svg)
    if re.search(r"""<svg\b[^>]*class=["'][^"']*\bannotate-diagram\b""", svg):
        return svg  # already tagged
    if re.search(r"""<svg\b[^>]*class=["']""", svg):
        return re.sub(r"""(<svg\b[^>]*class=["'])""", r"\1annotate-diagram ", svg, count=1)
    return re.sub(r"<svg\b", '<svg class="annotate-diagram"', svg, count=1)
