# Annotate Image Paste Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the annotate skill's comment editors accept pasted images so the user can include screenshots in their feedback, with Claude picking up each image via the `Read` tool on the next turn.

**Architecture:** New `POST /s/<sid>/api/upload` endpoint on the existing stdlib HTTP server writes the raw image bytes to `<state_dir>/images/<uuid>.<ext>` and returns the absolute path. Each comment textarea grows a paste listener that uploads on paste, inserts an `![paste-N]` token at the cursor, and renders a thumbnail strip below the textarea. On submit, each annotation carries an `images: [{token, path}, ...]` field. Claude's `Read` tool is multimodal — it ingests each path as a visual on the next turn.

**Tech Stack:** Python 3 stdlib (`http.server`, `uuid`, `pathlib`), vanilla JS (no framework), CSS custom-property design tokens already defined in `style.css`. Tests use `unittest` + `http.client` via subprocess, matching the existing `tests/test_server.py` pattern.

**Spec:** `docs/superpowers/specs/2026-05-20-annotate-image-paste-design.md`

---

## File map

- **Modify** `skills/annotate/server.py` — add `_handle_upload`, route it from `do_POST`, ensure `<state_dir>/images/` is created on first use.
- **Modify** `skills/annotate/static/script.js` — paste listener on each textarea, per-textarea `pastes` state, thumbnail strip, error chip, submit-payload `images` field.
- **Modify** `skills/annotate/static/style.css` — `.paste-strip`, `.paste-thumb`, `.paste-error` rules.
- **Modify** `skills/annotate/SKILL.md` — add `images` documentation in "How to read annotations".
- **Modify** `skills/annotate/tests/test_server.py` — upload endpoint happy path + the four rejection cases.

No new files. No new dependencies.

---

## Task 1: Server — upload endpoint happy path (TDD)

**Files:**
- Modify: `skills/annotate/server.py`
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Append to `skills/annotate/tests/test_server.py`, inside `class SessionTests(unittest.TestCase)` (or a new `UploadTests` class — match what's already there; the existing suite groups by feature). If you're unsure, add a new class at the bottom of the file just before `if __name__ == "__main__":`:

```python
class UploadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.cwd = self.tmp / "proj"
        self.cwd.mkdir()
        self.proc, info = _start_server(self.home)
        self.port = info["port"]
        self.sess = _create_session(self.port, self.cwd)

    def tearDown(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _upload(self, body: bytes, content_type: str):
        conn = http.client.HTTPConnection("localhost", self.port, timeout=2)
        conn.request(
            "POST", f"/s/{self.sess['sid']}/api/upload",
            body=body,
            headers={"Content-Type": content_type, "Content-Length": str(len(body))},
        )
        resp = conn.getresponse()
        status = resp.status
        data = resp.read().decode("utf-8")
        conn.close()
        return status, data

    def test_upload_writes_image_and_returns_path(self):
        # 1×1 PNG (smallest valid encoding).
        png = bytes.fromhex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
            "0000000d49444154789c63000100000005000101a5f645400000000049454e44ae426082"
        )
        status, body = self._upload(png, "image/png")
        self.assertEqual(status, 200, body)
        payload = json.loads(body)
        self.assertIn("path", payload)
        self.assertEqual(payload["size"], len(png))
        on_disk = Path(payload["path"])
        self.assertTrue(on_disk.is_file())
        self.assertEqual(on_disk.read_bytes(), png)
        self.assertEqual(on_disk.suffix, ".png")
        # Image must live under this session's state_dir/images/.
        expected_parent = Path(self.sess["state_dir"]) / "images"
        self.assertEqual(on_disk.parent, expected_parent)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.UploadTests.test_upload_writes_image_and_returns_path -v`
Expected: FAIL — `/api/upload` returns 404 (route not registered yet).

- [ ] **Step 3: Implement the upload handler in `server.py`**

Add this import near the top of `server.py` (alongside the existing `import secrets`):

```python
import uuid
```

Add the route to `do_POST` in `AnnotateHandler`. The existing block is:

```python
            if rest == "/api/submit":
                self._handle_submit(dirs)
                return
            if rest == "/api/cancel":
                self._handle_cancel(dirs)
                return
            self._send_text(404, "not found")
            return
```

Insert the new branch before the `404` fallback:

```python
            if rest == "/api/upload":
                self._handle_upload(dirs)
                return
```

Add the handler method on `AnnotateHandler`. Put it right after `_handle_cancel` so the upload/submit/cancel triplet stays together. Implements only the happy path for this task — terminal-state guard, MIME validation, and size cap come in Task 2 (their own failing tests). For now the handler is permissive so the happy-path test passes:

```python
    _UPLOAD_EXT = {
        "image/png": "png",
        "image/jpeg": "jpg",
        "image/gif": "gif",
        "image/webp": "webp",
    }
    _UPLOAD_MAX_BYTES = 10 * 1024 * 1024

    def _handle_upload(self, dirs: dict) -> None:
        ctype = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        ext = self._UPLOAD_EXT.get(ctype)
        if ext is None:
            self._send_text(415, "unsupported media type")
            return
        length_hdr = self.headers.get("Content-Length")
        if not length_hdr:
            self._send_text(411, "length required")
            return
        try:
            length = int(length_hdr)
        except ValueError:
            self._send_text(400, "invalid content-length")
            return
        if length <= 0 or length > self._UPLOAD_MAX_BYTES:
            self._send_text(413, "payload too large")
            return
        body = self.rfile.read(length)
        images_dir = dirs["state_dir"] / "images"
        images_dir.mkdir(parents=True, exist_ok=True)
        path = images_dir / f"{uuid.uuid4().hex}.{ext}"
        path.write_bytes(body)
        body_json = json.dumps({"path": str(path), "size": len(body)})
        data = body_json.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.UploadTests.test_upload_writes_image_and_returns_path -v`
Expected: PASS.

- [ ] **Step 5: Run the rest of the server suite to confirm no regressions**

Run: `python3 -m unittest skills.annotate.tests.test_server -v`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: add POST /s/<sid>/api/upload endpoint"
```

---

## Task 2: Server — upload rejection paths (TDD)

**Files:**
- Modify: `skills/annotate/server.py` (only if a test reveals a gap; the handler should already cover all four cases from Task 1)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing tests**

Add these methods inside `UploadTests` from Task 1:

```python
    def test_upload_rejects_unsupported_media_type(self):
        status, _ = self._upload(b"not really a pdf", "application/pdf")
        self.assertEqual(status, 415)

    def test_upload_rejects_oversized_payload(self):
        # Send 10 MB + 1 byte. Body itself doesn't have to be valid PNG —
        # the size check fires before we read it.
        big = b"\x00" * (10 * 1024 * 1024 + 1)
        status, _ = self._upload(big, "image/png")
        self.assertEqual(status, 413)

    def test_upload_rejects_missing_content_length(self):
        # http.client always sets Content-Length, so build the request manually.
        conn = http.client.HTTPConnection("localhost", self.port, timeout=2)
        conn.putrequest("POST", f"/s/{self.sess['sid']}/api/upload")
        conn.putheader("Content-Type", "image/png")
        conn.endheaders()
        # No body, no Content-Length.
        resp = conn.getresponse()
        self.assertEqual(resp.status, 411)
        conn.close()

    def test_upload_rejects_after_session_terminal(self):
        # Submit empty annotations to move the session to terminal state.
        conn = http.client.HTTPConnection("localhost", self.port, timeout=2)
        conn.request(
            "POST", f"/s/{self.sess['sid']}/api/submit",
            body=json.dumps({"response_id": "irrelevant", "annotations": []}),
            headers={"Content-Type": "application/json"},
        )
        # Meta isn't written, so the response_id mismatch path is bypassed —
        # the server treats missing meta as "no current id" and accepts.
        self.assertEqual(conn.getresponse().status, 200)
        conn.close()
        png = bytes.fromhex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
            "0000000d49444154789c63000100000005000101a5f645400000000049454e44ae426082"
        )
        status, _ = self._upload(png, "image/png")
        self.assertEqual(status, 409)
```

- [ ] **Step 2: Run tests to verify which fail**

Run: `python3 -m unittest skills.annotate.tests.test_server.UploadTests -v`
Expected:
- `test_upload_rejects_unsupported_media_type` PASS (already handled in Task 1)
- `test_upload_rejects_oversized_payload` PASS
- `test_upload_rejects_missing_content_length` PASS
- `test_upload_rejects_after_session_terminal` **FAIL** — the handler does not yet check `_terminal_state`.

- [ ] **Step 3: Add the terminal-state guard**

Edit `_handle_upload` in `server.py`. At the very top of the method body (before the content-type check), insert:

```python
        if _terminal_state(dirs["state_dir"]) is not None:
            self._send_text(409, "session closed")
            return
```

- [ ] **Step 4: Run tests to verify they all pass**

Run: `python3 -m unittest skills.annotate.tests.test_server.UploadTests -v`
Expected: all four reject tests PASS, plus the happy path from Task 1.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: harden /api/upload (415, 413, 411, 409 paths)"
```

---

## Task 3: Frontend — paste-strip state + DOM scaffolding

**Files:**
- Modify: `skills/annotate/static/script.js` (inside `buildCard`)

No automated test — verification is manual in Task 6.

- [ ] **Step 1: Add the per-textarea state and strip element**

In `script.js`, locate `buildCard(id, a)`. After the `const ta = document.createElement("textarea");` line and before `ta.addEventListener("input", ...)`, insert:

```js
    // Image-paste state for this textarea. `pastes` is the ordered list of
    // uploaded images for this annotation; `nextIndex` is the next paste-N
    // number to assign. Initial state is rehydrated from the annotation's
    // `images` array on re-render (drafts → reload survives across pageloads).
    const pasteState = {
      pastes: (annotations[id].images || []).map((img, i) => ({
        token: img.token,
        path: img.path,
        thumbUrl: null, // no blob available after reload; strip will show a placeholder tile
      })),
      nextIndex: ((annotations[id].images || []).length) + 1,
    };

    const pasteStrip = document.createElement("div");
    pasteStrip.className = "paste-strip";
    if (pasteState.pastes.length === 0) pasteStrip.dataset.empty = "1";
```

- [ ] **Step 2: Add the strip-rendering helper inside `buildCard`**

Below the `pasteStrip` declaration, add:

```js
    function renderStrip() {
      pasteStrip.replaceChildren();
      if (pasteState.pastes.length === 0) {
        pasteStrip.dataset.empty = "1";
        return;
      }
      delete pasteStrip.dataset.empty;
      for (const p of pasteState.pastes) {
        const tile = document.createElement("div");
        tile.className = "paste-thumb";
        tile.dataset.token = p.token;
        const img = document.createElement("img");
        img.alt = p.token;
        if (p.thumbUrl) img.src = p.thumbUrl;
        else tile.classList.add("no-thumb"); // rehydrated after reload — show placeholder
        const label = document.createElement("span");
        label.className = "paste-label";
        label.textContent = p.token;
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "paste-remove";
        remove.title = "Remove";
        remove.textContent = "×";
        remove.addEventListener("click", (ev) => {
          ev.stopPropagation();
          pasteState.pastes = pasteState.pastes.filter(x => x.token !== p.token);
          persistImages();
          renderStrip();
        });
        tile.appendChild(img);
        tile.appendChild(label);
        tile.appendChild(remove);
        pasteStrip.appendChild(tile);
      }
    }

    function persistImages() {
      if (pasteState.pastes.length === 0) {
        delete annotations[id].images;
      } else {
        annotations[id].images = pasteState.pastes.map(p => ({ token: p.token, path: p.path }));
      }
      saveDrafts();
    }
```

- [ ] **Step 3: Insert the strip into the editor wrap**

Find the existing append sequence in `buildCard`:

```js
    wrap.appendChild(preview);
    wrap.appendChild(ta);
    wrap.appendChild(handle);
    card.appendChild(wrap);
    renderPreview();
```

Replace with:

```js
    wrap.appendChild(preview);
    wrap.appendChild(ta);
    wrap.appendChild(handle);
    card.appendChild(wrap);
    card.appendChild(pasteStrip);
    renderPreview();
    renderStrip();
```

- [ ] **Step 4: Sanity-check syntax**

Run: `node --check skills/annotate/static/script.js`
Expected: no output (clean parse).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: scaffold paste-strip state in comment cards"
```

---

## Task 4: Frontend — paste listener uploads + inserts token

**Files:**
- Modify: `skills/annotate/static/script.js` (inside `buildCard`)

- [ ] **Step 1: Add the paste handler**

Inside `buildCard`, after the `renderStrip();` line at the end of the editor setup (so `pasteStrip`, `pasteState`, `ta`, and the helpers are all in scope), add:

```js
    ta.addEventListener("paste", async (ev) => {
      const items = ev.clipboardData?.items;
      if (!items) return;
      let imageItem = null;
      for (const it of items) {
        if (it.kind === "file" && it.type.startsWith("image/")) {
          imageItem = it;
          break;
        }
      }
      if (!imageItem) return; // fall through to default text paste
      ev.preventDefault();
      const blob = imageItem.getAsFile();
      if (!blob) return;
      const token = `paste-${pasteState.nextIndex++}`;
      // Insert token at the caret immediately so the user has visual feedback
      // while the upload is in flight. If upload fails we leave the token in
      // place — orphan tokens are harmless — and show the error chip.
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const insertion = `![${token}]`;
      ta.value = ta.value.slice(0, start) + insertion + ta.value.slice(end);
      const caret = start + insertion.length;
      ta.setSelectionRange(caret, caret);
      // Mirror the new value into the annotation draft and refresh the preview.
      if (a.type === "rewrite") annotations[id].replacement = ta.value;
      else annotations[id].comment = ta.value;
      saveDrafts();
      try {
        const resp = await fetch(BASE + "api/upload", {
          method: "POST",
          headers: { "Content-Type": blob.type },
          body: blob,
        });
        if (!resp.ok) {
          showPasteError(`upload failed (${resp.status})`);
          return;
        }
        const { path } = await resp.json();
        pasteState.pastes.push({
          token,
          path,
          thumbUrl: URL.createObjectURL(blob),
        });
        persistImages();
        renderStrip();
      } catch (err) {
        showPasteError("upload failed (network)");
      }
    });

    let errorChipTimer = null;
    function showPasteError(msg) {
      let chip = pasteStrip.querySelector(".paste-error");
      if (!chip) {
        chip = document.createElement("span");
        chip.className = "paste-error";
        pasteStrip.appendChild(chip);
      }
      chip.textContent = msg;
      if (errorChipTimer) clearTimeout(errorChipTimer);
      errorChipTimer = setTimeout(() => { chip.remove(); errorChipTimer = null; }, 4000);
    }
```

- [ ] **Step 2: Sanity-check syntax**

Run: `node --check skills/annotate/static/script.js`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: paste image → upload + insert ![paste-N] token"
```

---

## Task 5: Frontend — propagate `images` in submit payload

**Files:**
- Modify: `skills/annotate/static/script.js` (inside `onSubmit`)

- [ ] **Step 1: Add `images` to the submit-payload mapper**

In `script.js`, locate `onSubmit`. The payload-building mapper currently reads:

```js
      annotations: Object.values(annotations).map(a => {
        const out = {
          block_id: a.block_id,
          type: a.type || "comment",
          selected_text: a.selected_text,
          comment: a.comment,
        };
        if (a.replacement !== undefined) out.replacement = a.replacement;
        if (a.prefix !== undefined) out.prefix = a.prefix;
        if (a.suffix !== undefined) out.suffix = a.suffix;
        if (a.block_id) {
          const snippet = blockSnippet(a.block_id);
          if (snippet) out.block_snippet = snippet;
        }
        return out;
      }),
```

Insert one new line after the `suffix` propagation and before the `block_id` snippet block:

```js
        if (Array.isArray(a.images) && a.images.length > 0) out.images = a.images;
```

So the final mapper reads:

```js
      annotations: Object.values(annotations).map(a => {
        const out = {
          block_id: a.block_id,
          type: a.type || "comment",
          selected_text: a.selected_text,
          comment: a.comment,
        };
        if (a.replacement !== undefined) out.replacement = a.replacement;
        if (a.prefix !== undefined) out.prefix = a.prefix;
        if (a.suffix !== undefined) out.suffix = a.suffix;
        if (Array.isArray(a.images) && a.images.length > 0) out.images = a.images;
        if (a.block_id) {
          const snippet = blockSnippet(a.block_id);
          if (snippet) out.block_snippet = snippet;
        }
        return out;
      }),
```

- [ ] **Step 2: Sanity-check syntax**

Run: `node --check skills/annotate/static/script.js`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: include images[] in submit payload"
```

---

## Task 6: CSS — paste strip styling

**Files:**
- Modify: `skills/annotate/static/style.css`

- [ ] **Step 1: Append the new rules to `style.css`**

Append at the end of the file:

```css
/* === Paste strip (image attachments in comment editors) =============== */

.paste-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
  align-items: flex-start;
}
.paste-strip[data-empty="1"] { display: none; }

.paste-thumb {
  position: relative;
  width: 60px;
  height: 60px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  display: flex;
  align-items: flex-end;
  justify-content: stretch;
  flex-direction: column;
}
.paste-thumb img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.paste-thumb.no-thumb::before {
  content: "🖼";
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: var(--text-dim);
}
.paste-label {
  position: relative;
  z-index: 1;
  width: 100%;
  font-family: 'Monaspace Radon', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 9.5px;
  line-height: 1;
  text-align: center;
  padding: 2px 0;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  letter-spacing: 0.02em;
}
.paste-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  z-index: 2;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  display: none;
  align-items: center;
  justify-content: center;
}
.paste-thumb:hover .paste-remove { display: inline-flex; }
.paste-remove:hover { background: rgba(0, 0, 0, 0.75); }

.paste-error {
  align-self: center;
  font-size: 12px;
  color: var(--type-reject-fg);
  background: var(--type-reject-wash);
  border: 1px solid var(--type-reject-bg);
  border-radius: 4px;
  padding: 3px 8px;
}
```

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/static/style.css
git commit -m "annotate: style paste strip and error chip"
```

---

## Task 7: SKILL.md — document the `images` field

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Add the new bullet**

In `skills/annotate/SKILL.md`, locate the "How to read annotations" section. The per-annotation bulleted list ends with the `prefix` / `suffix` bullet:

```
   - Optional `prefix` and `suffix` (~20 chars each) disambiguate when the same `selected_text` appears multiple times in the block.
```

Add a new bullet **immediately after** that line:

```
   - `images` — optional array of `{token, path}` objects. When present and non-empty, `Read` each `path` before composing your reply so you can see the screenshots the user pasted. The `![paste-N]` markers inside `comment` show where in the user's text each image belongs; treat them as inline references when interpreting the comment. The path is on the local filesystem (under the session's `state_dir/images/`) — the standard `Read` tool ingests it directly as an image.
```

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: document images[] field in SKILL.md"
```

---

## Task 8: End-to-end manual verification

This task has no automated test — the integration only exists at the level of "real browser pastes a real screenshot." Run through the checklist below and only check off each step once you've actually performed it.

- [ ] **Step 1: Start the server**

Run: `"$CLAUDE_PLUGIN_ROOT/skills/annotate/ensure_server.sh"` (or directly: `PYTHONPATH=$PWD python3 -m skills.annotate.server`)
Expected: a `server-started` JSON line on stdout, plus `~/.claude/annotate/server.json` written.

- [ ] **Step 2: Create a session and seed a response**

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
SESS=$(curl -sf -X POST "$SERVER_URL/api/sessions" -H 'Content-Type: application/json' -d "{\"cwd\": \"$PWD\"}")
echo "$SESS"
RESP_DIR=$(echo "$SESS" | python3 -c 'import json,sys; print(json.load(sys.stdin)["response_dir"])')
URL=$(echo "$SESS" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
printf '{"response_id":"r-manual","title":"manual paste test","claude_session_id":"manual"}\n' > "$RESP_DIR/meta.json"
printf '# Manual paste test\n\nFirst paragraph for an annotation.\n\nSecond paragraph.\n' > "$RESP_DIR/response.md"
echo "Open: $URL"
```

Expected: a printable URL.

- [ ] **Step 3: Paste a screenshot into a block-level comment**

In a browser: open the URL. Hover the first paragraph, click `💬`. The comment card appears. Take a screenshot (Cmd-Shift-Ctrl-4 on macOS to copy to clipboard). Click into the textarea, paste.
Expected:
- `![paste-1]` text appears at the cursor.
- A 60×60 thumbnail of the screenshot appears in a strip below the textarea, labeled `paste-1`.
- Hovering the thumbnail shows a `×` remove button.

- [ ] **Step 4: Paste a second screenshot in the same comment**

Take another screenshot, paste again.
Expected: `![paste-2]` inserts at the cursor, second thumbnail appears with label `paste-2`.

- [ ] **Step 5: Try a non-image paste — should fall through to text**

Copy some text from another window, paste into the textarea.
Expected: the text appears in the textarea (no `![paste-N]` token, no thumbnail, no error).

- [ ] **Step 6: Force the 413 path**

Use the browser devtools to upload a >10MB image by hand (or run the curl command below):
```bash
dd if=/dev/zero bs=1m count=11 2>/dev/null | base64 | head -c $((11*1024*1024+1)) | curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$URL"api/upload -H "Content-Type: image/png" --data-binary @-
```
Expected: `413`.

- [ ] **Step 7: Submit and inspect the payload**

Click "Submit annotations" in the browser.
Then:
```bash
ANN_DIR=$(echo "$SESS" | python3 -c 'import json,sys; print(json.load(sys.stdin)["annotations_dir"])')
cat "$ANN_DIR/annotations.json"
```
Expected: the annotation has an `images` array of `{token, path}` objects with two entries, paths exist on disk, and the comment text contains `![paste-1]` and `![paste-2]`.

- [ ] **Step 8: Verify Claude can Read the image**

From a Claude Code session, manually invoke the `Read` tool on one of the paths from `annotations.json`.
Expected: Claude sees the screenshot visually (no "binary file" error, no base64 dump).

- [ ] **Step 9: Final commit (any cleanup)**

If Steps 1–8 surfaced any issues, fix them and commit. If everything passed, no commit needed.

---

## Spec self-review

- **Spec coverage:** Upload endpoint (Tasks 1–2), token format (Task 4), thumbnail strip (Task 3 + Task 6), submit-payload `images` field (Task 5), SKILL.md docs (Task 7), end-to-end smoke (Task 8). All goal/non-goal items map back.
- **Placeholders:** none — every code step shows the exact diff.
- **Type/name consistency:** `pasteState`, `pasteStrip`, `paste-strip`, `paste-thumb`, `paste-label`, `paste-remove`, `paste-error`, `_handle_upload`, `_UPLOAD_EXT`, `_UPLOAD_MAX_BYTES` — used consistently across server, JS, and CSS tasks. Annotation field is `images` (not `attachments` / `pastes`) at every payload boundary.
- **Tests:** server endpoint has automated coverage (happy path + 4 rejection cases); frontend is verified manually in Task 8 because there's no JS test harness in this repo (matches existing convention — `tests/` only covers Python).
