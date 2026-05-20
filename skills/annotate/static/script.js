// annotate skill — client-side selection capture and submission
(function () {
  const responseId = document.body.dataset.responseId || "";
  // Base path of the page, e.g. "/" or "/s/<sid>/". All fetches are relative to it.
  const BASE = (() => {
    const p = window.location.pathname;
    return p.endsWith("/") ? p : p + "/";
  })();
  const proseEl = document.querySelector("main.prose");
  const submitBtn = document.getElementById("submit-btn");
  const statusEl = document.getElementById("submit-status");
  const countEl = document.getElementById("comment-count");
  const STORAGE_KEY = `annotate.drafts.${responseId}`;

  const commentMd = (typeof window.markdownit === "function")
    ? window.markdownit({ html: false, linkify: true, typographer: false, breaks: true })
    : null;

  const PLACEHOLDER_TEXT = {
    reject: "Optional note…",
    comment: "Your comment…",
  };

  // Top-level elements that get their own annotation block id (matches the
  // contract the Python renderer used to provide).
  const BLOCK_TAGS = new Set(["P", "H1", "H2", "H3", "H4", "H5", "H6", "LI", "PRE", "BLOCKQUOTE"]);

  function assignBlockIds(root) {
    let n = 0;
    function walk(el) {
      if (el.nodeType !== 1) return;
      if (BLOCK_TAGS.has(el.tagName)) {
        el.dataset.blockId = `b-${n++}`;
        // Lists themselves don't get IDs but their <li> children do, so recurse
        // through unwrapped container tags.
      }
      if (el.tagName === "UL" || el.tagName === "OL" || el.tagName === "BLOCKQUOTE") {
        for (const child of el.children) walk(child);
      }
    }
    for (const child of root.children) walk(child);
  }

  async function renderMarkdown() {
    if (!proseEl || typeof window.markdownit !== "function") return;
    let text = "";
    try {
      const r = await fetch(BASE + "raw", { cache: "no-store" });
      if (!r.ok) return;
      text = await r.text();
    } catch (_) {
      return;
    }
    const md = window.markdownit({
      html: false,
      linkify: true,
      typographer: false,
      breaks: false,
    });
    proseEl.innerHTML = md.render(text);
    assignBlockIds(proseEl);
    renderHoverActions();
    renderComments();
    applyEngagedStyling();
  }

  const THEME_KEY = "annotate.theme";

  function getInitialTheme() {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === "light" || saved === "dark") return saved;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  const themeLightBtn = document.getElementById("theme-light");
  const themeDarkBtn = document.getElementById("theme-dark");

  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    if (themeLightBtn && themeDarkBtn) {
      themeLightBtn.classList.toggle("active", theme === "light");
      themeDarkBtn.classList.toggle("active", theme === "dark");
    }
  }
  applyTheme(getInitialTheme());

  function persistTheme(theme) {
    try { localStorage.setItem(THEME_KEY, theme); } catch (_) { /* ignore */ }
  }

  themeLightBtn?.addEventListener("click", () => {
    persistTheme("light");
    applyTheme("light");
  });
  themeDarkBtn?.addEventListener("click", () => {
    persistTheme("dark");
    applyTheme("dark");
  });

  const ACCENT_KEY = "annotate.accent";
  const ACCENTS = ["mint", "lavender", "blue"];
  const DEFAULT_ACCENT = "mint";

  function getInitialAccent() {
    const saved = localStorage.getItem(ACCENT_KEY);
    return ACCENTS.includes(saved) ? saved : DEFAULT_ACCENT;
  }
  const accentBtns = [
    document.getElementById("accent-mint"),
    document.getElementById("accent-lavender"),
    document.getElementById("accent-blue"),
  ];

  function applyAccent(accent) {
    document.documentElement.dataset.accent = accent;
    accentBtns.forEach((btn, i) => {
      if (btn) btn.classList.toggle("active", ACCENTS[i] === accent);
    });
  }
  applyAccent(getInitialAccent());

  function persistAccent(accent) {
    try { localStorage.setItem(ACCENT_KEY, accent); } catch (_) { /* ignore */ }
  }

  accentBtns.forEach((btn, i) => {
    btn?.addEventListener("click", () => {
      persistAccent(ACCENTS[i]);
      applyAccent(ACCENTS[i]);
    });
  });

  const ACTION_TYPES = [
    { id: "comment", glyph: "💬", title: "Comment" },
    { id: "reject",  glyph: "✗",  title: "Reject"  },
  ];

  // annotations: { [annotId]: { block_id, selected_text, comment, prefix?, suffix? } }
  let annotations = loadDrafts();

  const cancelBtn = document.getElementById("cancel-btn");

  const HOVER_LINGER_MS = 500;
  const HEADING_TAGS = new Set(["H1", "H2", "H3", "H4", "H5", "H6"]);

  function renderHoverActions() {
    document.querySelectorAll("[data-block-id]").forEach(block => {
      if (HEADING_TAGS.has(block.tagName)) return;
      if (block.querySelector(".hover-actions")) return;
      const wrap = document.createElement("div");
      wrap.className = "hover-actions";
      let hideTimer = null;
      const show = () => {
        if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
        wrap.dataset.visible = "1";
      };
      const scheduleHide = () => {
        if (hideTimer) clearTimeout(hideTimer);
        hideTimer = setTimeout(() => { delete wrap.dataset.visible; hideTimer = null; }, HOVER_LINGER_MS);
      };
      block.addEventListener("mouseenter", show);
      block.addEventListener("mouseleave", scheduleHide);
      wrap.addEventListener("mouseenter", show);
      wrap.addEventListener("mouseleave", scheduleHide);
      for (const t of ACTION_TYPES) {
        const b = document.createElement("button");
        b.type = "button";
        b.dataset.type = t.id;
        b.textContent = t.glyph;
        b.title = t.title;
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          show();
          onHoverAction(block, t.id);
        });
        wrap.appendChild(b);
      }
      block.appendChild(wrap);
    });
  }

  function applyEngagedStyling() {
    document.querySelectorAll("[data-block-id][data-engaged-type]").forEach(b => {
      delete b.dataset.engagedType;
    });
    for (const a of Object.values(annotations)) {
      if (!a.block_id) continue;
      const block = document.querySelector(`[data-block-id="${a.block_id}"]`);
      if (block) block.dataset.engagedType = a.type;
    }
  }

  function onHoverAction(block, type) {
    const sel = window.getSelection();
    let selectedText = "";
    if (sel && !sel.isCollapsed) {
      const range = sel.getRangeAt(0);
      const startBlock = closestBlock(range.startContainer);
      if (startBlock === block) {
        selectedText = sel.toString().split("\n")[0];
      }
    }
    const id = `a-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
    const annot = {
      block_id: block.dataset.blockId,
      type,
      selected_text: selectedText,
      comment: "",
    };
    if (selectedText) {
      const blockText = block.textContent;
      if (occurrences(blockText, selectedText) > 1) {
        const idx = blockText.indexOf(selectedText);
        annot.prefix = blockText.slice(Math.max(0, idx - 20), idx);
        annot.suffix = blockText.slice(idx + selectedText.length, idx + selectedText.length + 20);
      }
    }
    annotations[id] = annot;
    saveDrafts();
    renderComments();
    applyEngagedStyling();
    if (sel) sel.removeAllRanges();
    focusComment(id);
  }

  renderMarkdown();

  submitBtn.addEventListener("click", onSubmit);
  cancelBtn.addEventListener("click", onCancel);
  const addGeneralBtn = document.getElementById("add-general");
  if (addGeneralBtn) addGeneralBtn.addEventListener("click", addGeneralComment);

  // Background polling — if response_id changes, the page is stale.
  setInterval(pollForUpdate, 2000);

  function closestBlock(node) {
    let n = node;
    while (n && n.nodeType !== 1) n = n.parentNode;
    while (n && !n.dataset?.blockId) n = n.parentElement;
    return n;
  }

  function occurrences(haystack, needle) {
    if (!needle) return 0;
    let n = 0, i = 0;
    while ((i = haystack.indexOf(needle, i)) !== -1) { n++; i += needle.length; }
    return n;
  }

  // Short, human-readable label for a block — used by the submit payload so the
  // terminal-side hook can show "Unified skill scaffolding command" instead of
  // a bare "b-12". Strips hover-action UI before extracting text.
  function blockSnippet(blockId) {
    if (!blockId) return "";
    const block = document.querySelector(`[data-block-id="${blockId}"]`);
    if (!block) return "";
    const clone = block.cloneNode(true);
    for (const ha of clone.querySelectorAll(".hover-actions")) ha.remove();
    const text = (clone.textContent || "").replace(/\s+/g, " ").trim();
    if (!text) return "";
    return text.length > 60 ? text.slice(0, 59).trimEnd() + "…" : text;
  }

  function buildCard(id, a) {
    const card = document.createElement("div");
    card.className = "comment-card";
    card.dataset.id = id;
    card.dataset.type = a.type;

    const closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "card-close";
    closeBtn.dataset.type = a.type;
    closeBtn.title = "Remove";
    closeBtn.setAttribute("aria-label", "Remove annotation");
    closeBtn.textContent = "×";
    closeBtn.addEventListener("click", () => {
      delete annotations[id];
      saveDrafts();
      renderComments();
      applyEngagedStyling();
    });
    card.appendChild(closeBtn);

    if (a.selected_text) {
      const quote = document.createElement("div");
      quote.className = "quote";
      quote.dataset.type = a.type;
      quote.textContent = a.selected_text;
      card.appendChild(quote);
    }

    const wrap = document.createElement("div");
    wrap.className = "editor-wrap preview-mode";

    const preview = document.createElement("div");
    preview.className = "editor-preview";

    const ta = document.createElement("textarea");
    // Image-paste state for this textarea. `pastes` is the ordered list of
    // uploaded images for this annotation; `nextIndex` is the next paste-N
    // number to assign. Initial state is rehydrated from the annotation's
    // `images` array on re-render (drafts → reload survives across pageloads).
    const pasteState = {
      pastes: (annotations[id].images || []).map((img) => ({
        token: img.token,
        path: img.path,
        thumbUrl: null, // no blob available after reload; strip will show a placeholder tile
      })),
      nextIndex: ((annotations[id].images || []).length) + 1,
    };

    const pasteStrip = document.createElement("div");
    pasteStrip.className = "paste-strip";
    if (pasteState.pastes.length === 0) pasteStrip.dataset.empty = "1";

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
        else tile.classList.add("no-thumb");
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

    const placeholder = PLACEHOLDER_TEXT[a.type] || PLACEHOLDER_TEXT.comment;
    ta.placeholder = placeholder;
    ta.value = a.comment || "";
    ta.addEventListener("input", () => {
      annotations[id].comment = ta.value;
      saveDrafts();
      autoGrow();
    });

    const renderPreview = () => {
      const v = annotations[id].comment || "";
      if (!v.trim()) {
        preview.classList.add("empty");
        preview.textContent = placeholder;
        return;
      }
      preview.classList.remove("empty");
      if (!commentMd) {
        preview.textContent = v;
        return;
      }
      preview.innerHTML = commentMd.render(v);
    };

    const autoGrow = () => {
      if (wrap.dataset.userSized === "1") return;
      ta.style.height = "auto";
      const cap = Math.max(160, Math.round(window.innerHeight * 0.5));
      ta.style.height = Math.min(ta.scrollHeight + 2, cap) + "px";
    };

    preview.addEventListener("click", () => {
      wrap.classList.remove("preview-mode");
      wrap.classList.add("edit-mode");
      ta.focus();
    });
    ta.addEventListener("focus", () => {
      wrap.classList.remove("preview-mode");
      wrap.classList.add("edit-mode");
      autoGrow();
    });
    ta.addEventListener("blur", () => {
      renderPreview();
      wrap.classList.remove("edit-mode");
      wrap.classList.add("preview-mode");
    });

    const handle = document.createElement("div");
    handle.className = "editor-resize";
    handle.title = "Drag to resize · double-click to reset";
    handle.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      const startY = e.clientY;
      const startH = ta.offsetHeight;
      handle.setPointerCapture(e.pointerId);
      const move = (ev) => {
        const newH = Math.max(60, startH + (ev.clientY - startY));
        ta.style.height = newH + "px";
        wrap.dataset.userSized = "1";
      };
      const up = () => {
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
    });
    handle.addEventListener("dblclick", () => {
      delete wrap.dataset.userSized;
      ta.style.height = "";
      autoGrow();
    });

    wrap.appendChild(preview);
    wrap.appendChild(ta);
    wrap.appendChild(handle);
    card.appendChild(wrap);
    card.appendChild(pasteStrip);
    renderPreview();
    renderStrip();

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
      annotations[id].comment = ta.value;
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

    return card;
  }

  function renderComments() {
    document.querySelectorAll(".inline-comments").forEach(el => el.remove());
    const generalEl = document.getElementById("general-comments");
    if (generalEl) generalEl.replaceChildren();

    const byBlock = {};
    const general = [];
    for (const [id, a] of Object.entries(annotations)) {
      if (!a.block_id) general.push([id, a]);
      else (byBlock[a.block_id] ||= []).push([id, a]);
    }

    for (const [blockId, items] of Object.entries(byBlock)) {
      const block = document.querySelector(`[data-block-id="${blockId}"]`);
      if (!block) continue;
      const wrap = document.createElement("div");
      wrap.className = "inline-comments";
      wrap.dataset.forBlock = blockId;
      for (const [id, a] of items) wrap.appendChild(buildCard(id, a));
      block.insertAdjacentElement("afterend", wrap);
    }

    if (generalEl) {
      for (const [id, a] of general) {
        const card = buildCard(id, a);
        card.classList.add("general");
        generalEl.appendChild(card);
      }
    }

    const n = Object.keys(annotations).length;
    if (countEl) countEl.textContent = n === 0 ? "" : (n === 1 ? "1 comment" : `${n} comments`);
  }

  function addGeneralComment() {
    const id = `a-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
    annotations[id] = {
      block_id: null,
      type: "comment",
      selected_text: "",
      comment: "",
    };
    saveDrafts();
    renderComments();
    focusComment(id);
  }

  function focusComment(id) {
    const card = document.querySelector(`.comment-card[data-id="${id}"]`);
    if (!card) return;
    const wrap = card.querySelector(".editor-wrap");
    const ta = card.querySelector("textarea");
    if (wrap) {
      wrap.classList.remove("preview-mode");
      wrap.classList.add("edit-mode");
    }
    if (ta) ta.focus({ preventScroll: true });
  }

  function loadDrafts() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}"); }
    catch { return {}; }
  }
  function saveDrafts() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(annotations)); }
    catch { /* ignore */ }
  }


  // `hasAction: true` means the locked card gets a button. Only the `stale`
  // state has somewhere meaningful to send the user — `submitted` and
  // `cancelled` are terminal and there's no page to refresh into, so they
  // ship without a button.
  const LOCKED_STATES = {
    submitted: { icon: "✓", title: "Annotations submitted",         message: "You can close this tab.",  hasAction: false },
    stale:     { icon: "⟳", title: "A newer response is available", message: "Refresh to load it.",      hasAction: true,  actionLabel: "Refresh" },
    cancelled: { icon: "⊘", title: "Annotation round cancelled",     message: "You can close this tab.", hasAction: false },
  };

  let lockedOverlay = null;
  let lockedKind = null;

  function enterLockedState(kind) {
    const cfg = LOCKED_STATES[kind];
    if (!cfg) return;
    if (lockedKind === kind) return;  // idempotent — repeated poll hits are a no-op
    lockedKind = kind;
    if (!lockedOverlay) {
      lockedOverlay = document.createElement("div");
      lockedOverlay.className = "locked-overlay";
      lockedOverlay.innerHTML = `
        <div class="locked-card">
          <div class="locked-icon"></div>
          <h2 class="locked-title"></h2>
          <p class="locked-message"></p>
        </div>
      `;
      document.body.appendChild(lockedOverlay);
    }
    const iconEl = lockedOverlay.querySelector(".locked-icon");
    iconEl.textContent = cfg.icon;
    iconEl.dataset.kind = kind;
    lockedOverlay.querySelector(".locked-title").textContent = cfg.title;
    lockedOverlay.querySelector(".locked-message").textContent = cfg.message;

    // Rebuild the action area each time so re-entering with a different kind
    // (e.g. stale → submitted, if that ever happens) flips the button cleanly.
    const card = lockedOverlay.querySelector(".locked-card");
    const existingBtn = card.querySelector(".locked-action");
    if (existingBtn) existingBtn.remove();
    if (cfg.hasAction) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "locked-action";
      btn.textContent = cfg.actionLabel || "Refresh";
      btn.addEventListener("click", () => { window.location.reload(); });
      card.appendChild(btn);
    }

    const prose = document.querySelector("main.prose");
    const footer = document.querySelector("footer.actions");
    if (prose) prose.inert = true;
    if (footer) footer.inert = true;
    submitBtn.disabled = true;
    cancelBtn.disabled = true;
  }

  function onSubmit() {
    submitBtn.disabled = true;
    statusEl.textContent = "Submitting…";
    statusEl.className = "";
    const payload = {
      response_id: responseId,
      annotations: Object.values(annotations).map(a => {
        const out = {
          block_id: a.block_id,
          type: a.type || "comment",
          selected_text: a.selected_text,
          comment: a.comment,
        };
        if (a.prefix !== undefined) out.prefix = a.prefix;
        if (a.suffix !== undefined) out.suffix = a.suffix;
        if (Array.isArray(a.images) && a.images.length > 0) out.images = a.images;
        if (a.block_id) {
          const snippet = blockSnippet(a.block_id);
          if (snippet) out.block_snippet = snippet;
        }
        return out;
      }),
    };
    fetch(BASE + "api/submit", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }).then(r => {
      if (r.ok) {
        localStorage.removeItem(STORAGE_KEY);
        enterLockedState("submitted");
      } else if (r.status === 409) {
        enterLockedState("stale");
      } else {
        statusEl.textContent = "Submit failed.";
        statusEl.className = "err";
        submitBtn.disabled = false;
      }
    }).catch(() => {
      statusEl.textContent = "Network error.";
      statusEl.className = "err";
      submitBtn.disabled = false;
    });
  }

  function pollForUpdate() {
    fetch(BASE + "poll").then(r => r.json()).then(data => {
      // Terminal beats "stale" — if another tab (or this one) submitted/cancelled,
      // lock the page immediately rather than nagging about a newer response that
      // the user can no longer act on.
      if (data.terminal === "submitted" || data.terminal === "cancelled") {
        enterLockedState(data.terminal);
        return;
      }
      if (data.response_id && data.response_id !== responseId) {
        showToast("New response available — reload");
      }
    }).catch(() => { /* ignore */ });
  }

  function showToast(msg) {
    const t = document.createElement("div");
    t.className = "toast";
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 2500);
  }

  function onCancel() {
    if (!window.confirm("Cancel this annotation round? Claude will resume without any annotations.")) return;
    cancelBtn.disabled = true;
    statusEl.textContent = "Cancelling…";
    statusEl.className = "";
    fetch(BASE + "api/cancel", { method: "POST" })
      .then(r => {
        if (r.ok) {
          enterLockedState("cancelled");
        } else {
          statusEl.textContent = "Cancel failed.";
          statusEl.className = "err";
          cancelBtn.disabled = false;
        }
      })
      .catch(() => {
        statusEl.textContent = "Network error.";
        statusEl.className = "err";
        cancelBtn.disabled = false;
      });
  }

  function showFirstTimeToast() {
    try {
      if (localStorage.getItem("annotate.welcomed") === "1") return;
    } catch (_) {}
    const toast = document.createElement("div");
    toast.className = "first-time-toast";
    toast.innerHTML = `
      <span>👋 Hover any paragraph and click <span class="kbd">✗</span> <span class="kbd">?</span> <span class="kbd">✏</span> <span class="kbd">💬</span> to react. Unmarked blocks are approved.</span>
      <button type="button" class="dismiss" aria-label="Dismiss">×</button>
    `;
    document.body.appendChild(toast);
    toast.querySelector(".dismiss").addEventListener("click", () => {
      try { localStorage.setItem("annotate.welcomed", "1"); } catch (_) {}
      toast.remove();
    });
  }
  showFirstTimeToast();
})();
