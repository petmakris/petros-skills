"""Hand-rolled markdown subset renderer.

Supported features:
- Inline code:           `code`           -> <code>code</code>
- Bold:                  **text**          -> <strong>text</strong>
- Italic:                _text_            -> <em>text</em> (not adjacent to word chars)
- Fenced code blocks:    ``` ... ```       -> <pre><code>...</code></pre>
- Bulleted lists:        - item             -> <ul><li>...</li></ul>
- Numbered lists:        1. item            -> <ol><li>...</li></ol>
- Safe links:            [text](url)        -> <a href="url">text</a>  (http/https/mailto only)

All input is HTML-escaped first. Code blocks and inline code do not process inner markdown.
"""

import html
import re

ALLOWED_LINK_SCHEMES = ("http://", "https://", "mailto:")
_FENCE = "```"
_CODE_PLACEHOLDER = "\x00CODEBLOCK{}\x00"
_INLINE_PLACEHOLDER = "\x00INLINE{}\x00"


def render(text: str) -> str:
    if not text:
        return ""
    # Strip NUL bytes — they're used internally as placeholder markers.
    text = text.replace("\x00", "")

    # 1. Pull out fenced code blocks (preserving their raw content, escaping HTML inside).
    blocks: list[str] = []

    def stash_block(match: re.Match) -> str:
        body = html.escape(match.group(1))
        blocks.append(f"<pre><code>{body}</code></pre>")
        return _CODE_PLACEHOLDER.format(len(blocks) - 1)

    text = re.sub(rf"{re.escape(_FENCE)}\n?(.*?)\n?{re.escape(_FENCE)}", stash_block, text, flags=re.DOTALL)

    # 2. Escape the rest.
    text = html.escape(text, quote=True)

    # 3. Pull out inline code spans (escaping HTML inside).
    inlines: list[str] = []

    def stash_inline(match: re.Match) -> str:
        # The text is already HTML-escaped at this point, so no further escaping needed.
        inlines.append(f"<code>{match.group(1)}</code>")
        return _INLINE_PLACEHOLDER.format(len(inlines) - 1)

    text = re.sub(r"`([^`\n]+)`", stash_inline, text)

    # 4. Bold and italic.
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\w)_([^_\n]+)_(?!\w)", r"<em>\1</em>", text)

    # 5. Links — only allow safe schemes. Note text and url are already HTML-escaped.
    def render_link(match: re.Match) -> str:
        link_text = match.group(1)
        url = match.group(2)
        # The url has been HTML-escaped, so http:// looks the same. Compare scheme on the escaped form.
        if any(url.lower().startswith(scheme) for scheme in ALLOWED_LINK_SCHEMES):
            return f'<a href="{url}">{link_text}</a>'
        # Unsafe — return the raw text form. Need to un-escape the escaped form to rebuild the markdown source.
        return f"[{link_text}]({url})"

    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", render_link, text)

    # 6. Lists — process line groups.
    text = _render_lists(text)

    # 7. Restore inline code placeholders.
    for i, span in enumerate(inlines):
        text = text.replace(_INLINE_PLACEHOLDER.format(i), span)

    # 8. Restore fenced code block placeholders.
    for i, block in enumerate(blocks):
        text = text.replace(_CODE_PLACEHOLDER.format(i), block)

    return text


def _render_lists(text: str) -> str:
    """Group consecutive `- ` or `\\d+\\. ` lines into <ul>/<ol>."""
    lines = text.split("\n")
    out: list[str] = []
    buf: list[str] = []
    list_kind: str | None = None  # 'ul' | 'ol' | None

    def flush():
        nonlocal buf, list_kind
        if list_kind and buf:
            inner = "".join(f"<li>{item}</li>" for item in buf)
            out.append(f"<{list_kind}>{inner}</{list_kind}>")
        buf = []
        list_kind = None

    for line in lines:
        ul_match = re.match(r"^- (.+)$", line)
        ol_match = re.match(r"^\d+\. (.+)$", line)
        if ul_match:
            if list_kind == "ol":
                flush()
            list_kind = "ul"
            buf.append(ul_match.group(1))
        elif ol_match:
            if list_kind == "ul":
                flush()
            list_kind = "ol"
            buf.append(ol_match.group(1))
        else:
            flush()
            out.append(line)

    flush()
    return "\n".join(out) if list_kind is None else "".join(out)
