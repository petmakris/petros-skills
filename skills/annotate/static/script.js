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

  const ACTION_TYPES = [
    { id: "reject",   glyph: "✗",  title: "Reject"   },
    { id: "question", glyph: "?",  title: "Question" },
    { id: "rewrite",  glyph: "✏",  title: "Rewrite"  },
    { id: "comment",  glyph: "💬", title: "Comment"  },
  ];

  const TYPE_BADGE_TEXT = {
    reject: "✗ reject",
    question: "? question",
    rewrite: "✏ rewrite",
    comment: "💬 comment",
  };

  // annotations: { [annotId]: { block_id, selected_text, comment, prefix?, suffix? } }
  let annotations = loadDrafts();
  renderComments();

  const cancelBtn = document.getElementById("cancel-btn");

  function renderHoverActions() {
    document.querySelectorAll("[data-block-id]").forEach(block => {
      if (block.querySelector(".hover-actions")) return;
      const wrap = document.createElement("div");
      wrap.className = "hover-actions";
      for (const t of ACTION_TYPES) {
        const b = document.createElement("button");
        b.type = "button";
        b.dataset.type = t.id;
        b.textContent = t.glyph;
        b.title = t.title;
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
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
    if (type === "rewrite") annot.replacement = "";
    annotations[id] = annot;
    saveDrafts();
    renderComments();
    applyEngagedStyling();
    if (sel) sel.removeAllRanges();
    focusComment(id);
  }

  renderHoverActions();
  applyEngagedStyling();

  submitBtn.addEventListener("click", onSubmit);
  cancelBtn.addEventListener("click", onCancel);

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

  function buildCard(id, a) {
    const card = document.createElement("div");
    card.className = "comment-card";
    card.dataset.id = id;
    card.dataset.type = a.type;

    const badge = document.createElement("div");
    badge.className = "type-badge";
    badge.dataset.type = a.type;
    badge.textContent = TYPE_BADGE_TEXT[a.type] || a.type;
    card.appendChild(badge);

    if (a.selected_text) {
      const quote = document.createElement("div");
      quote.className = "quote";
      quote.dataset.type = a.type;
      quote.textContent = a.selected_text;
      card.appendChild(quote);
    }

    const ta = document.createElement("textarea");
    if (a.type === "rewrite") {
      ta.value = a.replacement || "";
      ta.placeholder = "Replace selected text with…";
      ta.rows = 3;
      ta.addEventListener("input", () => {
        annotations[id].replacement = ta.value;
        saveDrafts();
      });
    } else {
      ta.value = a.comment;
      ta.placeholder = a.type === "reject" ? "Optional note…" : "Your comment…";
      ta.addEventListener("input", () => {
        annotations[id].comment = ta.value;
        saveDrafts();
      });
    }
    card.appendChild(ta);

    const actions = document.createElement("div");
    actions.className = "card-actions";

    const changeBtn = document.createElement("button");
    changeBtn.type = "button";
    changeBtn.className = "card-link";
    changeBtn.textContent = "change type";
    changeBtn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      openChangeTypeMenu(id, card);
    });
    actions.appendChild(changeBtn);

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "card-link";
    remove.textContent = "remove";
    remove.addEventListener("click", () => {
      delete annotations[id];
      saveDrafts();
      renderComments();
      applyEngagedStyling();
    });
    actions.appendChild(remove);

    card.appendChild(actions);
    return card;
  }

  function renderComments() {
    document.querySelectorAll(".inline-comments").forEach(el => el.remove());

    const byBlock = {};
    for (const [id, a] of Object.entries(annotations)) {
      (byBlock[a.block_id] ||= []).push([id, a]);
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

    const n = Object.keys(annotations).length;
    if (countEl) countEl.textContent = n === 0 ? "" : (n === 1 ? "1 comment" : `${n} comments`);
  }

  function openChangeTypeMenu(annotationId, card) {
    const existing = card.querySelector(".type-menu");
    if (existing) { existing.remove(); return; }
    const menu = document.createElement("div");
    menu.className = "type-menu";
    for (const t of ACTION_TYPES) {
      const opt = document.createElement("button");
      opt.type = "button";
      opt.textContent = TYPE_BADGE_TEXT[t.id];
      opt.addEventListener("click", () => {
        const a = annotations[annotationId];
        a.type = t.id;
        if (t.id === "rewrite" && a.replacement === undefined) a.replacement = "";
        saveDrafts();
        renderComments();
        applyEngagedStyling();
      });
      menu.appendChild(opt);
    }
    card.appendChild(menu);
  }

  function focusComment(id) {
    const ta = document.querySelector(`.comment-card[data-id="${id}"] textarea`);
    if (ta) ta.focus();
  }

  function loadDrafts() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}"); }
    catch { return {}; }
  }
  function saveDrafts() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(annotations)); }
    catch { /* ignore */ }
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
        if (a.replacement !== undefined) out.replacement = a.replacement;
        if (a.prefix !== undefined) out.prefix = a.prefix;
        if (a.suffix !== undefined) out.suffix = a.suffix;
        return out;
      }),
    };
    fetch(BASE + "api/submit", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }).then(r => {
      if (r.ok) {
        statusEl.textContent = "Submitted ✓";
        statusEl.className = "ok";
        localStorage.removeItem(STORAGE_KEY);
      } else if (r.status === 409) {
        statusEl.textContent = "Response is stale — reload the page.";
        statusEl.className = "err";
      } else {
        statusEl.textContent = "Submit failed.";
        statusEl.className = "err";
      }
    }).catch(() => {
      statusEl.textContent = "Network error.";
      statusEl.className = "err";
    }).finally(() => {
      submitBtn.disabled = false;
    });
  }

  function pollForUpdate() {
    fetch(BASE + "poll").then(r => r.json()).then(data => {
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
          statusEl.textContent = "Cancelled — you can close this tab.";
          statusEl.className = "ok";
          submitBtn.disabled = true;
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
