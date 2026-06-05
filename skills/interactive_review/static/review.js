// interactive-review client. Renders a PR diff, supports per-line comment
// threads, polls for updates.
(function () {
  const reviewEl = document.querySelector("main.review");
  const generalEl = document.getElementById("general-thread");
  const addGeneralBtn = document.getElementById("add-general");
  const doneBtn = document.getElementById("done-btn");

  const md = (typeof window.markdownit === "function")
    ? window.markdownit({ html: false, linkify: true, breaks: true })
    : null;

  const threadVersions = {};

  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    if (attrs) for (const k of Object.keys(attrs)) {
      if (k === "class") e.className = attrs[k];
      else if (k.startsWith("on") && typeof attrs[k] === "function") e.addEventListener(k.slice(2), attrs[k]);
      else if (k === "dataset") for (const dk of Object.keys(attrs.dataset)) e.dataset[dk] = attrs.dataset[dk];
      else e.setAttribute(k, attrs[k]);
    }
    for (const c of children) {
      if (c == null) continue;
      e.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return e;
  }

  function anchorFor(filePath, line) {
    return `${filePath}:${line.side === "removed" ? "L" : "R"}:${line.side === "removed" ? line.old : line.new}`;
  }

  function buildLine(filePath, line) {
    const lineEl = el("div",
      { class: `diff-line ${line.side}`, dataset: { anchor: anchorFor(filePath, line), side: line.side } },
      el("span", { class: "gutter-old" }, line.old != null ? String(line.old) : ""),
      el("span", { class: "gutter-new" }, line.new != null ? String(line.new) : ""),
      el("span", { class: "diff-text" }, line.text),
    );
    if (line.side !== "removed" || line.old != null) {
      lineEl.addEventListener("click", () => openComposer(filePath, line));
    }
    return lineEl;
  }

  function buildFile(fileData) {
    const file = el("div", { class: "file-block", dataset: { path: fileData.path } },
      el("div", { class: "file-header" },
        el("span", { class: "file-path" }, fileData.path),
        el("span", { class: "file-stats" },
          el("span", { class: "added" }, `+${fileData.added}`),
          " ",
          el("span", { class: "removed" }, `-${fileData.removed}`),
        ),
      ),
    );
    for (const h of fileData.hunks) {
      file.appendChild(el("div", { class: "hunk-header" },
        `@@ -${h.old_start},${h.old_lines} +${h.new_start},${h.new_lines} @@`));
      for (const line of h.lines) {
        file.appendChild(buildLine(fileData.path, line));
      }
    }
    return file;
  }

  function renderMessage(msg) {
    const role = msg.role === "claude" ? "claude" : "user";
    const content = md ? md.render(msg.text || "") : `<p>${(msg.text || "").replace(/&/g, "&amp;").replace(/</g, "&lt;")}</p>`;
    const wrap = el("div", { class: `thread-message role-${role}` },
      el("div", null,
        el("span", { class: "role-label" }, role === "claude" ? "Claude" : "You")),
    );
    const body = el("div", { class: "message-content" });
    body.innerHTML = content;
    wrap.appendChild(body);
    return wrap;
  }

  function ensureThreadContainerAfter(lineEl, anchor) {
    let next = lineEl.nextElementSibling;
    while (next && next.classList && (next.classList.contains("thread") || next.classList.contains("composer"))) {
      if (next.classList.contains("thread") && next.dataset.anchor === anchor) return next;
      next = next.nextElementSibling;
    }
    const t = el("div", { class: "thread", dataset: { anchor } });
    lineEl.after(t);
    return t;
  }

  async function renderThread(anchor) {
    const data = await WebCompanion.api.fetchJSON(`thread?anchor=${encodeURIComponent(anchor)}`);
    threadVersions[anchor] = data.version || 0;
    if (anchor === "__general__") {
      generalEl.innerHTML = "";
      for (const m of data.messages || []) generalEl.appendChild(renderMessage(m));
      return;
    }
    const lineEl = document.querySelector(`.diff-line[data-anchor="${CSS.escape(anchor)}"]`);
    if (!lineEl) return;
    const container = ensureThreadContainerAfter(lineEl, anchor);
    container.innerHTML = "";
    for (const m of data.messages || []) container.appendChild(renderMessage(m));
    const msgs = data.messages || [];
    if (msgs.length && msgs[msgs.length - 1].role === "user") {
      container.appendChild(el("div", { class: "thinking" }, "Claude is thinking…"));
    }
  }

  function openComposer(filePath, line) {
    const anchor = anchorFor(filePath, line);
    const lineEl = document.querySelector(`.diff-line[data-anchor="${CSS.escape(anchor)}"]`);
    if (!lineEl) return;
    if (lineEl.nextElementSibling && lineEl.nextElementSibling.classList.contains("composer")
        && lineEl.nextElementSibling.dataset.anchor === anchor) {
      lineEl.nextElementSibling.querySelector("textarea").focus();
      return;
    }
    buildComposer(anchor, lineEl);
  }

  function buildComposer(anchor, anchorEl) {
    const ta = el("textarea", { placeholder: "Ask Claude…" });
    const submit = el("button", null, "Submit");
    const cancel = el("button", { class: "cancel" }, "cancel");
    const comp = el("div", { class: "composer", dataset: { anchor } },
      ta,
      el("div", { class: "composer-actions" }, cancel, submit),
    );
    cancel.addEventListener("click", () => comp.remove());
    submit.addEventListener("click", async () => {
      const text = ta.value.trim();
      if (!text) return;
      submit.disabled = true;
      submit.textContent = "Submitting…";
      try {
        await WebCompanion.api.submit({ anchor, type: "comment", text, images: [] });
        comp.remove();
        await renderThread(anchor);
      } catch (e) {
        submit.disabled = false;
        submit.textContent = "Submit";
        ta.placeholder = `Error: ${e.message}`;
      }
    });
    if (anchorEl) anchorEl.after(comp);
    else generalEl.parentElement.insertBefore(comp, addGeneralBtn);
    ta.focus();
  }

  async function loadDiff() {
    let files;
    try {
      files = await WebCompanion.api.fetchJSON("files");
    } catch (e) {
      reviewEl.innerHTML = `<p style="padding:20px">Failed to load diff: ${e.message}</p>`;
      return;
    }
    reviewEl.innerHTML = "";
    if (!files.length) {
      reviewEl.innerHTML = "<p style='padding:20px'>No changes in this PR.</p>";
      return;
    }
    for (const f of files) reviewEl.appendChild(buildFile(f));
  }

  function onPollDelta(data, _prev) {
    const cur = data.threads || {};
    for (const anchor of Object.keys(cur)) {
      if (cur[anchor] !== threadVersions[anchor]) {
        renderThread(anchor);
      }
    }
  }

  if (addGeneralBtn) {
    addGeneralBtn.addEventListener("click", () => {
      const existing = generalEl.parentElement.querySelector('.composer[data-anchor="__general__"]');
      if (existing) { existing.querySelector("textarea").focus(); return; }
      buildComposer("__general__", null);
    });
  }
  if (doneBtn) {
    doneBtn.addEventListener("click", async () => {
      if (!confirm("Finish this review session?")) return;
      await WebCompanion.api.finish();
      window.location.reload();
    });
  }

  loadDiff().then(() => {
    renderThread("__general__");
    WebCompanion.init({ onPollDelta });
  });
})();
