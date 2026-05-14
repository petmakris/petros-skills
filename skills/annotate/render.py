"""Render markdown to HTML with stable, sequential block IDs on each top-level block.

Top-level blocks include:
- paragraphs (<p>)
- headings (<h1>..<h6>)
- list items (<li>) — each item is its own anchor, not the wrapping <ul>/<ol>
- code blocks (<pre>)
- blockquotes (<blockquote>)

Block IDs are formatted "b-0", "b-1", ... assigned in document order.
The ID is added as a `data-block-id` attribute on the block's outermost tag.
"""

import re

from skills.annotate import markdown_lite


# Matches the opening tag of any element that should be a separately-anchored block.
# We intentionally do NOT match <ul>/<ol> — each <li> is its own block.
_BLOCK_TAG_RE = re.compile(
    r"<(p|h[1-6]|li|pre|blockquote)(?=[\s>])",
    re.IGNORECASE,
)

# Heading prefix: 1-6 '#' chars followed by a space.
_HEADING_RE = re.compile(r"^(#{1,6}) (.+)$")

# Opening tag of a block-level container that should not be wrapped in <p>.
_BLOCK_CONTAINER_RE = re.compile(r"^<(ul|ol|pre|blockquote)[\s>]", re.IGNORECASE)


def _wrap_blocks(html: str) -> str:
    """Wrap plain-text chunks from markdown_lite output in <p> or <hN> tags.

    markdown_lite handles lists (<ul>/<ol>/<li>) and code blocks (<pre>) but
    does not emit <p> or heading tags.  This function:
      - splits the HTML on blank lines to find top-level chunks
      - peels off any leading ``## Heading`` lines as their own blocks, even
        when the chunk continues with paragraph text on the next line
      - wraps anything else that is not already an HTML block container in <p>
    """
    chunks = re.split(r"\n{2,}", html.strip())
    out: list[str] = []
    for chunk in chunks:
        chunk = chunk.strip()
        if not chunk:
            continue
        if _BLOCK_CONTAINER_RE.match(chunk):
            out.append(chunk)
            continue
        lines = chunk.split("\n")
        while lines:
            m = _HEADING_RE.match(lines[0])
            if not m:
                break
            level = len(m.group(1))
            out.append(f"<h{level}>{m.group(2)}</h{level}>")
            lines = lines[1:]
        remainder = "\n".join(lines).strip()
        if remainder:
            out.append(f"<p>{remainder}</p>")
    return "\n".join(out)


def render_with_block_ids(markdown: str) -> str:
    """Render markdown to HTML and tag each top-level block with a sequential data-block-id."""
    if not markdown:
        return ""
    html = markdown_lite.render(markdown)
    html = _wrap_blocks(html)
    counter = {"n": 0}

    def add_id(match: re.Match) -> str:
        tag = match.group(0)
        block_id = f'b-{counter["n"]}'
        counter["n"] += 1
        return f'{tag} data-block-id="{block_id}"'

    return _BLOCK_TAG_RE.sub(add_id, html)
