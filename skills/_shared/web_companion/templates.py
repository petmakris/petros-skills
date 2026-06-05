"""Shared HTML shell templates used by skill renderers."""
from __future__ import annotations

import html as _html


def html_escape(s: str) -> str:
    return _html.escape(s, quote=True)


def render_page(title: str, head_assets: str, body_html: str,
                response_id: str = "") -> str:
    """Standard page shell. Includes the core stylesheet and markdown-it.  The
    skill's body_html should already include any skill-specific scripts.
    head_assets is extra <link>/<script> tags. The palette is a single theme
    defined in core.css :root — no runtime accent switching."""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{html_escape(title)}</title>
<link rel="stylesheet" href="/static/core.css">
{head_assets}
</head>
<body data-response-id="{html_escape(response_id)}">
{body_html}
<script src="/static/markdown-it.min.js"></script>
<script src="/static/core.js"></script>
</body>
</html>
"""
