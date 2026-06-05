# annotate: image paste support in comments

## Problem

The annotate skill lets the user highlight Claude's text and leave free-text comments. Today the only payload from user back to Claude is text. In Claude Code itself the user can paste or drag-and-drop screenshots, and that ability disappears the moment a response is routed through the annotate browser view. For UI/visual feedback (the most common reason to want annotate in the first place) this is a real gap.

This spec adds image paste to the comment editor. Drag-and-drop is explicitly out of scope.

## Goal

When the user is focused in any comment textarea (block annotation, general comment, hover-action editor) and pastes an image from the clipboard:

1. The image uploads to the annotate server and is saved on disk.
2. A token `![paste-N]` is inserted at the cursor in the textarea.
3. A thumbnail of the uploaded image appears in a strip below the textarea, labeled `paste-N`.
4. On submit, the annotation payload carries an `images` array mapping each token to an absolute file path.
5. Claude, on its next turn, sees the comment text with `![paste-N]` markers and uses the `Read` tool on each image path to view the screenshot.

The Read tool is multimodal — pointing it at a PNG/JPG file gives Claude the visual content directly. That's the whole reason this design works without a new transport.

## Non-goals

- Drag-and-drop uploads. Paste only.
- Image editing, cropping, or in-app annotation on the image itself.
- Reusing one upload across multiple annotations. Each paste is its own upload.
- GC of orphaned uploads (e.g. user pasted, then deleted the `![paste-N]` token before submit). The session directory is short-lived; we let the orphan ride.
- Base64-inline transport in the annotations JSON. Files on disk + Read tool is cheaper and is what the Read tool was built for.

## Token format

- Per annotation, paste counter starts at 1 and monotonically increases for the lifetime of that textarea instance.
- Token text is `![paste-N]` — visually obvious as a placeholder, recognizable in plain prose. The leading `!` mirrors Markdown image syntax so it reads "this is where the image goes" without us having to invent a custom marker.
- Numbering is per-annotation, not global. Two block annotations can both have `![paste-1]` — they map to different paths via their own `images` arrays.
- The token is plain text inside the textarea. The user can move it, delete it, duplicate it. If the user deletes a token, the corresponding upload is *not* removed from the `images` array on submit — orphaned references are cheap and we don't track text edits.
- The thumbnail strip below the textarea is the source of truth for "what was uploaded." The textarea text is the source of truth for "where the user wants those images to appear in their comment."

## Annotation payload

`annotations.json` schema gains one optional field per annotation (and per general comment):

```json
{
  "block_id": "b-3",
  "type": "comment",
  "comment": "look at this ![paste-1] — broken alignment, and ![paste-2] for context",
  "images": [
    { "token": "paste-1", "path": "/abs/.../images/<uuid>.png" },
    { "token": "paste-2", "path": "/abs/.../images/<uuid>.png" }
  ]
}
```

- `images` is absent when no images were pasted in that annotation.
- `images` order matches paste order (token N corresponds to the Nth pasted image, even if the user has rearranged tokens in the text).
- `path` is the absolute filesystem path returned by the upload endpoint.

## Server changes (`skills/annotate/server.py`)

### New route: `POST /s/<sid>/api/upload`

- Reject with 409 if the session is in a terminal state (same `_terminal_state(state_dir)` guard used by `/api/submit` and `/api/cancel`).
- Read `Content-Type` from the request header. Allow only `image/png`, `image/jpeg`, `image/gif`, `image/webp`. Reject others with 415.
- Cap request body at 10 MB (read `Content-Length`; reject with 413 if larger or missing).
- Generate `uuid4().hex`, write the raw body to `<state_dir>/images/<uuid>.<ext>`, where `<ext>` is derived from the content-type (`png`, `jpg`, `gif`, `webp`).
- Create `<state_dir>/images/` lazily on first upload (it doesn't exist for sessions that never pasted).
- Respond `200` with JSON `{"path": "<absolute path>", "size": <bytes>}`.

### Existing handlers — no change

`/api/submit` still writes `annotations.json` verbatim. The new `images` field rides through untouched because the server doesn't inspect annotation shape beyond `response_id` and `annotations` being a list.

### Session directory

A session that uses pastes ends up with:

```
.claude/annotate/<sid>/
  response/{response.md, meta.json}
  annotations/annotations.json
  state/{cancelled?, submitted?, images/<uuid>.<ext>...}
```

Images live under `state/` because (a) it's the per-session bag of side data already used for terminal-state markers, and (b) cancellation/submission cleanup of `state/` (if we ever add it) would naturally take the images too.

## Frontend changes (`skills/annotate/static/script.js`)

### Paste handler

Attach a `paste` event listener to every comment textarea (block annotations, general comments, hover-action editors).

When the listener fires:

1. Walk `event.clipboardData.items`. For each item where `kind === "file"` and `type.startsWith("image/")`:
   - `event.preventDefault()` so the default paste (which would paste a filename, nothing, or base64 markup depending on platform) doesn't run.
   - `getAsFile()` to obtain a `Blob`.
   - POST the blob to `/s/<sid>/api/upload` with `Content-Type` set to the blob's MIME type.
   - On success, increment the textarea's paste counter, insert `![paste-N]` at the current cursor position, append `{token, path, thumbUrl}` to the textarea's `pastes` array, and render a new thumbnail in the strip.
   - On failure (size cap, network, etc.), show a small inline error chip next to the strip ("paste failed: too large") and abort.

If no image items are found in the clipboard, do not call `preventDefault` — let the default text paste proceed.

### Per-textarea state

Each comment textarea owns:

```js
{
  pastes: [{ token: "paste-1", path: "/abs/...", thumbUrl: "blob:..." }, ...],
  nextIndex: 2
}
```

`thumbUrl` is a `URL.createObjectURL(blob)` reference to the original blob, used purely for the in-browser thumbnail. The server URL isn't needed because we don't have a download route — the thumb is built from the same blob that was uploaded.

### Thumbnail strip

A `<div class="paste-strip">` rendered immediately below the textarea (above any existing action buttons). For each entry in `pastes`:

- 60×60 px thumbnail (CSS `object-fit: cover`).
- Label `paste-N` underneath.
- A small × button on the thumb to remove the entry from `pastes`. Removing does *not* edit the textarea text; the user is responsible for removing the orphaned token if they care. (Cheaper than tracking edits, and tokens-without-images are harmless — Claude will just see a stray `![paste-1]` in the comment text.)

### Submit payload

When building the per-annotation payload, if `pastes.length > 0`, attach:

```js
images: pastes.map(p => ({ token: p.token, path: p.path }))
```

`thumbUrl` is local-only and not sent.

## Skill instructions (`skills/annotate/SKILL.md`)

Add to the "How to read annotations" section, as a sub-bullet under the per-annotation list:

> **`images`** — optional array of `{token, path}` objects. If present and non-empty, `Read` each `path` before composing your reply so you can see the screenshots the user pasted. The `![paste-N]` markers inside `comment` show where in the user's text each image belongs; treat them as inline references when interpreting the comment.

No changes to the Stop hook — the annotations JSON is forwarded as-is, and `images` rides along.

## Risks and edge cases

- **Empty clipboard image (e.g. macOS sometimes pastes a PDF + bitmap pair)**: walk all items, take the first `image/*` we find. If none, fall through to default text paste.
- **Very large screenshots (> 10 MB)**: server returns 413, frontend shows the error chip. Most macOS Retina screenshots are 1–4 MB so 10 MB is a comfortable ceiling.
- **Hover-action editor that's mid-composition when the user cancels**: the editor's `pastes` state is discarded with the editor instance. The uploaded files remain on disk in `<state_dir>/images/` — orphans, but harmless.
- **Same Claude Code session, sequential annotate rounds**: each round has its own `<sid>`, its own `state_dir`, its own `images/`. No cross-talk.
- **`paste-N` collisions across two annotations**: not a collision — `paste-N` is scoped to a single annotation's `images` array. Two annotations can each have `paste-1` and they're different files.
- **User pastes the same image twice**: two uploads, two distinct uuids, two thumbnails, `paste-1` and `paste-2`. We don't dedupe.
- **Path encoding in `comment`**: paths are not embedded in the comment text — they live in `images[].path`. The `comment` only contains `![paste-N]` tokens, which are plain ASCII.

## Testing notes

- Unit-ish: manually paste a PNG into a block-level comment, a general comment, and a hover-action editor; submit; verify `annotations.json` shape and on-disk image file.
- Verify 415 on a non-image upload (e.g. PDF) and 413 on an oversized upload.
- Verify that orphaned tokens (delete `![paste-1]` text but keep the thumbnail) submit cleanly and Claude handles the case without complaint.
- Verify cross-session isolation by running two browser tabs against two `<sid>`s in the same project and pasting into both.

## Acceptance

- Pasting an image in any comment editor inserts a token at the cursor and adds a thumbnail below the textarea.
- Submitting produces an `annotations.json` whose annotations carry an `images` array with valid absolute paths.
- Claude, on its next turn, reads each path with the `Read` tool and references the screenshot in its reply.
- 415 / 413 errors surface as inline chips, not silent failures.
- No regressions in text-only comment flows.
