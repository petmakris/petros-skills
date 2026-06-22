// annotate skill — client-side incremental rendering and per-block submission
(function () {
  // Base path of the page, e.g. "/" or "/s/<sid>/". Relative fetches use this.
  const BASE = (() => {
    const p = window.location.pathname;
    return p.endsWith("/") ? p : p + "/";
  })();

  const proseEl = document.querySelector("main.prose");
  const STORAGE_KEY = `annotate.drafts.${document.body.dataset.responseId || ""}`;

  const commentMd = (typeof window.markdownit === "function")
    ? window.markdownit({ html: false, linkify: true, typographer: false, breaks: true })
    : null;

  // ── Drafts ─────────────────────────────────────────────────────────────────
  // annotations: { [annotId]: { block_id, type, selected_text, comment, images? } }
  let annotations = loadDrafts();

  // Submitted comments awaiting Claude's ack: event_id -> { blockId } for a
  // block/diagram/choice comment, or { general: true } for a page-level one.
  // The block "updating" overlay / composer status line resolves when the
  // matching event_id appears in /poll's consumed_events — the real done-signal,
  // which does NOT depend on Claude rewriting the commented block specifically.
  const pendingEvents = new Map();

  // CSS.escape fallback (older engines): block/step/annotate ids can be
  // Claude-authored, so a raw `[data-block-id="${id}"]` would throw on a quote.
  const cssEsc = (s) => (window.CSS && CSS.escape) ? CSS.escape(String(s)) : String(s).replace(/["\\\]]/g, "\\$&");

  function loadDrafts() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}"); }
    catch { return {}; }
  }

  function saveDrafts() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(annotations)); }
    catch {}
  }

  // ── Rendering ──────────────────────────────────────────────────────────────
  const PLACEHOLDER_TEXT = { reject: "Optional note…", comment: "Your comment…" };

  // Feather-style line icons, one per action. Distinct metaphors so reject and
  // dismiss can't be confused: speech-bubble (talk), thumbs-down (disagree),
  // trash (delete). Stroke/size come from CSS (.hover-actions button svg).
  const ICON = {
    comment: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
    reject: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3z"/><path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/></svg>',
    dismiss: '<svg viewBox="0 0 24 24" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>',
  };
  const ACTION_TYPES = [
    { id: "comment", title: "Comment" },
    { id: "reject",  title: "Reject"  },
  ];

  const HOVER_LINGER_MS = 500;

  function renderHoverActions() {
    // Wire hover-action buttons onto every <section class="block"> that's
    // NOT a sequence diagram. Markdown blocks get the hover strip; diagram
    // blocks use direct-click-on-step (wired in createBlockSection) so that
    // the click event's target is an SVG node and step_id resolves correctly.
    // We also have to skip SVG <g class="step-row"> elements — they carry
    // data-block-id too (for the submit payload) but are not block containers.
    const HEADING_TAGS = new Set(["H1", "H2", "H3", "H4", "H5", "H6"]);
    document.querySelectorAll("[data-block-id]").forEach(block => {
      if (block.tagName !== "SECTION") return;
      if (block.dataset.kind === "sequence") return;
      // Choice blocks are answered by picking an option, not by commenting —
      // suppress the comment/reject strip the same way diagrams do.
      if (block.dataset.kind === "choice") return;
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
        b.innerHTML = ICON[t.id];
        b.title = t.title;
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          show();
          onHoverAction(block, t.id, ev);
        });
        wrap.appendChild(b);
      }
      // Dismiss (delete-as-irrelevant). Unlike comment/reject it opens no
      // editor — it submits a dismiss event straight away.
      const del = document.createElement("button");
      del.type = "button";
      del.dataset.type = "dismiss";
      del.innerHTML = ICON.dismiss;
      del.title = "Remove section (irrelevant)";
      del.addEventListener("click", (ev) => {
        ev.stopPropagation();
        onDismiss(block);
      });
      wrap.appendChild(del);
      (block.querySelector(".card-body") || block).appendChild(wrap);
    });
  }

  function applyEngagedStyling() {
    document.querySelectorAll("[data-block-id][data-engaged-type]").forEach(b => {
      delete b.dataset.engagedType;
    });
    for (const a of Object.values(annotations)) {
      if (!a.block_id) continue;
      // querySelector returns the first match in document order — the
      // <section>. For sequence diagrams, the SVG <g class="step-row">
      // children also carry data-block-id, so an explicit second query is
      // needed to flag the specific step that has the draft.
      const block = document.querySelector(`[data-block-id="${cssEsc(a.block_id)}"]`);
      if (block) block.dataset.engagedType = a.type;
      if (a.step_id) {
        const step = document.querySelector(
          `[data-block-id="${cssEsc(a.block_id)}"][data-step-id="${cssEsc(a.step_id)}"]`
        );
        if (step) step.dataset.engagedType = a.type;
      }
    }
  }

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

  function blockSnippet(blockId) {
    if (!blockId) return "";
    const block = document.querySelector(`[data-block-id="${cssEsc(blockId)}"]`);
    if (!block) return "";
    const clone = block.cloneNode(true);
    for (const ha of clone.querySelectorAll(".hover-actions")) ha.remove();
    const text = (clone.textContent || "").replace(/\s+/g, " ").trim();
    if (!text) return "";
    return text.length > 60 ? text.slice(0, 59).trimEnd() + "…" : text;
  }

  function onDismiss(block) {
    // Guard: the lock states make dismiss a no-op while another submission is
    // in flight or an editor is open. (CSS also disables the button, but the
    // guard covers programmatic/edge calls.)
    if (document.body.classList.contains("is-busy") ||
        document.body.classList.contains("is-editing")) return;
    const blockId = block.dataset.blockId;
    if (!blockId) return;
    const payload = {
      block_id: blockId,
      step_id: null,
      type: "dismiss",
      text: "",
      selected_text: "",
      images: [],
    };
    WebCompanion.api.submit(payload).then((res) => {
      const eventId = res && res.event_id;
      if (eventId) pendingEvents.set(String(eventId), { blockId });
      // Immediate feedback; the block is actually removed by reconcile() once
      // Claude acks and /raw no longer lists it.
      startUpdatingOverlay(block);
    }).catch(() => { /* swallow — page stays usable */ });
  }

  function onHoverAction(block, type, event) {
    const sel = window.getSelection();
    let selectedText = "";
    if (sel && !sel.isCollapsed) {
      const range = sel.getRangeAt(0);
      const startBlock = closestBlock(range.startContainer);
      if (startBlock === block) {
        selectedText = sel.toString().split("\n")[0];
      }
    }
    // Sub-unit lookup: prefer the closest data-step-id (used by the
    // diagram renderer) and otherwise fall back to data-annotate-id
    // (the convention Claude uses inside free-HTML markdown blocks).
    let stepId = null;
    const stepNode = event?.target?.closest("[data-step-id]");
    if (stepNode && block.contains(stepNode)) {
      stepId = stepNode.dataset.stepId;
    } else {
      const annotNode = event?.target?.closest("[data-annotate-id]");
      if (annotNode && block.contains(annotNode)) {
        stepId = annotNode.dataset.annotateId;
      }
    }
    // Single input per target: if a draft already exists for this
    // (block, step), reuse it instead of stacking a second card. The 💬 / ✗
    // icons then just switch that one card's intent (comment ↔ reject) and
    // keep any text already typed. Without this, clicking both icons on a
    // block opens two separate inputs — confusing, and the submit intent is
    // ambiguous.
    const blockId = block.dataset.blockId;
    const norm = (s) => (s == null ? null : s);
    const existingId = Object.keys(annotations).find((k) => {
      const x = annotations[k];
      return x.block_id === blockId && norm(x.step_id) === norm(stepId);
    });

    // Single-flight: refuse to open a second editor while one is already open
    // for a different target, and refuse entirely while the page is BUSY.
    if (document.body.classList.contains("is-busy")) return;
    if (!existingId && Object.keys(annotations).length > 0) return;

    const id = existingId || `a-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
    const annot = annotations[id] || { block_id: blockId, step_id: stepId, comment: "" };
    annot.type = type;
    // A fresh selection re-scopes the card; otherwise keep the existing scope.
    if (selectedText) {
      annot.selected_text = selectedText;
      const blockText = block.textContent;
      delete annot.prefix;
      delete annot.suffix;
      if (occurrences(blockText, selectedText) > 1) {
        const idx = blockText.indexOf(selectedText);
        annot.prefix = blockText.slice(Math.max(0, idx - 20), idx);
        annot.suffix = blockText.slice(idx + selectedText.length, idx + selectedText.length + 20);
      }
    } else if (!existingId) {
      annot.selected_text = "";
    }
    annotations[id] = annot;
    saveDrafts();
    renderComments();
    applyEngagedStyling();
    if (sel) sel.removeAllRanges();
    focusComment(id);
  }

  // ── Block loading and rendering ────────────────────────────────────────────

  // Syntax-highlight fenced code via highlight.js. Returning a full
  // `<pre><code class="hljs …">` makes markdown-it use it verbatim (it only
  // wraps when the hook returns a non-<pre> string), so `code.hljs` gets the
  // theme background from code-theme.css. hljs.highlight() HTML-escapes its
  // input; an empty return falls back to markdown-it's own escaped rendering
  // (e.g. if highlight.min.js failed to load).
  function highlightFence(str, lang) {
    if (typeof window.hljs !== "object" || !window.hljs) return "";
    let inner;
    try {
      if (lang && hljs.getLanguage(lang)) {
        inner = hljs.highlight(str, { language: lang, ignoreIllegals: true }).value;
      } else if (str.length > 20000) {
        return ""; // skip ~35-grammar auto-detect on a huge untagged fence
      } else {
        inner = hljs.highlightAuto(str).value; // Claude often omits the lang tag
      }
    } catch (_) {
      return "";
    }
    const cls = "hljs" + (lang ? " language-" + lang.replace(/[^\w-]/g, "") : "");
    return '<pre><code class="' + cls + '">' + inner + "</code></pre>";
  }

  const blockMd = (typeof window.markdownit === "function")
    ? window.markdownit({ html: true, linkify: true, typographer: false,
                          breaks: false, highlight: highlightFence })
    : null;

  // Conservative sanitizer for HTML that lands in a block via markdown-it
  // (now html: true so Claude can emit free-form HTML).  Threat model is
  // "defend against accidents", not a hostile author — Claude is the only
  // writer of blocks.json — but we strip the obvious script/handler vectors
  // so a broken response can't break the page.
  const SAN_DISALLOWED_TAGS = new Set([
    "SCRIPT", "IFRAME", "OBJECT", "EMBED", "LINK", "META", "STYLE", "BASE", "FORM",
  ]);
  function sanitizeFreeHtml(root) {
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    const toRemove = [];
    let node;
    while ((node = walker.nextNode())) {
      // tagName is uppercase for HTML elements but lowercase for SVG
      // descendants (different namespace) — normalise before checking
      // so `<svg><script>` is caught the same as a top-level `<script>`.
      if (SAN_DISALLOWED_TAGS.has(node.tagName.toUpperCase())) {
        toRemove.push(node);
        continue;
      }
      for (const attr of [...node.attributes]) {
        const name = attr.name.toLowerCase();
        if (name.startsWith("on")) {
          node.removeAttribute(attr.name);
          continue;
        }
        if ((name === "href" || name === "src" || name === "xlink:href") &&
            /^\s*javascript:/i.test(attr.value)) {
          node.removeAttribute(attr.name);
        }
      }
    }
    for (const n of toRemove) n.remove();
  }

  async function loadAndRenderBlocks() {
    if (!proseEl || !blockMd) return;
    let data;
    try {
      const r = await fetch(BASE + "raw", { cache: "no-store" });
      if (!r.ok) return;
      data = await r.json();
    } catch (_) {
      return;
    }
    if (window.AnnotateGlossary) {
      window.AnnotateGlossary.setGlossary(data.glossary || []);
      // Seed the poll-loop's change-detector so the first tick doesn't see
      // undefined→[...] and fire a needless refreshAll() that collapses any
      // in-progress text selection.
      window.AnnotateGlossary._lastGlossary = data.glossary || [];
    }
    proseEl.replaceChildren();
    for (const blk of (data.blocks || [])) {
      const section = createBlockSection(blk);
      proseEl.appendChild(section);
    }
    renderHoverActions();
    renderComments();
    applyEngagedStyling();
    showFirstTimeToast();
  }

  // Header title for a block's card. Claude may author a `title`; otherwise we
  // derive one from the content (first heading, else first sentence/line).
  function blockTitle(blk) {
    if (blk.title && String(blk.title).trim()) return String(blk.title).trim();
    const k = blk.kind || "markdown";
    if (k === "sequence" || k === "diagram") {
      const t = blk.spec && blk.spec.title;
      return (t && String(t).trim()) || "Diagram";
    }
    if ((blk.kind || "markdown") === "choice") {
      const q = blk.spec && blk.spec.question;
      return (q && String(q).trim()) || "Decision";
    }
    const md = blk.markdown || "";
    const heading = md.match(/^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$/m);
    let t = heading
      ? heading[1]
      : (md.split(/\n/).map(s => s.replace(/^[#>*\-\s`]+/, "").trim()).find(Boolean) || "");
    t = t.replace(/[*_`]/g, "").replace(/\s+/g, " ").trim();
    if (t.length > 60) t = t.slice(0, 59).trimEnd() + "…";
    return t || "Section";
  }

  function setCardTitle(section, blk) {
    const el = section.querySelector(".card-title");
    if (el) el.textContent = blockTitle(blk);
  }

  // Render a choice block's interactive body: question, radio/checkbox
  // options, and a Submit button. On submit, POST the selection and show the
  // same "updating" overlay the comment path uses.
  function renderChoice(section, content, blk) {
    const spec = blk.spec || {};
    const multi = !!spec.multiSelect;
    const options = Array.isArray(spec.options) ? spec.options : [];
    const groupName = `choice-${blk.id}`;

    const wrap = document.createElement("div");
    wrap.className = "choice-block";

    // The question is shown in the card header (derived from spec.question by
    // blockTitle) — don't repeat it in the body.

    const list = document.createElement("div");
    list.className = "choice-options";
    const inputs = [];
    for (const opt of options) {
      const row = document.createElement("label");
      row.className = "choice-option";
      const input = document.createElement("input");
      input.type = multi ? "checkbox" : "radio";
      input.name = groupName;
      input.value = opt.id;
      inputs.push(input);
      const textWrap = document.createElement("span");
      textWrap.className = "choice-option-text";
      const label = document.createElement("span");
      label.className = "choice-option-label";
      label.textContent = opt.label || opt.id;
      textWrap.appendChild(label);
      if (opt.description) {
        const desc = document.createElement("span");
        desc.className = "choice-option-desc";
        desc.textContent = opt.description;
        textWrap.appendChild(desc);
      }
      row.append(input, textWrap);
      list.appendChild(row);
    }
    wrap.appendChild(list);

    const submitBtn = document.createElement("button");
    submitBtn.type = "button";
    submitBtn.className = "choice-submit-btn";
    submitBtn.textContent = "Submit";
    submitBtn.disabled = true;
    const refreshDisabled = () => {
      submitBtn.disabled = !inputs.some(i => i.checked);
    };
    inputs.forEach(i => i.addEventListener("change", refreshDisabled));

    submitBtn.addEventListener("click", () => {
      const selected = inputs.filter(i => i.checked).map(i => i.value);
      if (!selected.length) return;
      submitBtn.disabled = true;
      const payload = {
        block_id: blk.id,
        step_id: null,
        type: "choice",
        selected_options: selected,
        text: "",
        selected_text: "",
        images: [],
      };
      WebCompanion.api.submit(payload).then((res) => {
        const eventId = res && res.event_id;
        if (eventId) pendingEvents.set(String(eventId), { blockId: blk.id });
        startUpdatingOverlay(section);
      }).catch(() => {
        refreshDisabled();
      });
    });
    wrap.appendChild(submitBtn);
    content.appendChild(wrap);
  }

  // ── Mockup kind: full-fidelity HTML in a sandboxed iframe ───────────────────
  // Live registry of mockup iframes, so the single boot-level message handler
  // can match an inbound postMessage to the iframe that sent it by object
  // identity. The frame's origin is the string "null" and must NOT be trusted.
  const mockupFrames = new Set();

  window.addEventListener("message", (ev) => {
    const d = ev.data;
    if (!d || d.type !== "annotate:height") return;
    let target = null;
    for (const f of Array.from(mockupFrames)) {
      if (!f.isConnected) { mockupFrames.delete(f); continue; }  // prune stale
      if (f.contentWindow === ev.source) target = f;             // identity gate
    }
    if (!target) return;
    const h = Number(d.h);
    if (!Number.isFinite(h)) return;                             // ignore garbage
    target.style.height = Math.min(Math.max(h, 20), 20000) + "px"; // clamp
  });

  const MOCKUP_CSP =
    '<meta http-equiv="Content-Security-Policy" content="' +
    "default-src 'none'; img-src data:; style-src 'unsafe-inline'; " +
    "script-src 'unsafe-inline'; font-src data:; connect-src 'none'; " +
    "form-action 'none'; base-uri 'none'\">";

  // Trusted, host-injected. Reports content height up so the host can size the
  // iframe. Posts on observe, DOMContentLoaded, load, and late <img> loads.
  const MOCKUP_BRIDGE =
    "<scr" + "ipt>(function(){function p(){parent.postMessage(" +
    "{type:'annotate:height',h:document.documentElement.scrollHeight},'*');}" +
    "try{new ResizeObserver(p).observe(document.documentElement);}catch(e){}" +
    "document.addEventListener('DOMContentLoaded',p);" +
    "window.addEventListener('load',p,true);p();})();</scr" + "ipt>";

  function renderMockup(content, blk) {
    const html = (blk.spec && blk.spec.html) || "";
    if (!html) {
      content.innerHTML = '<div class="mockup-missing">mockup unavailable</div>';
      return;
    }
    const iframe = document.createElement("iframe");
    iframe.className = "mockup-frame";
    iframe.setAttribute("sandbox", "allow-scripts");   // NEVER allow-same-origin
    iframe.setAttribute("scrolling", "no");
    iframe.style.height = "60px";                       // placeholder until bridge reports
    iframe.srcdoc =
      '<!DOCTYPE html><html><head><meta charset="utf-8">' + MOCKUP_CSP +
      "<style>html,body{margin:0;padding:0}</style></head><body>" +
      html + MOCKUP_BRIDGE + "</body></html>";
    mockupFrames.add(iframe);
    content.appendChild(iframe);
  }

  function createBlockSection(blk) {
    const section = document.createElement("section");
    section.className = "block card";
    section.dataset.blockId = blk.id;
    section.dataset.version = String(blk.version ?? 1);
    const kind = blk.kind || "markdown";
    section.dataset.kind = kind;

    // Card header: collapse chevron + title (+ version chip, added by
    // renderVersionBadge). Clicking the header toggles the body.
    const head = document.createElement("div");
    head.className = "card-head";
    const chev = document.createElement("button");
    chev.type = "button";
    chev.className = "card-chevron";
    chev.setAttribute("aria-label", "Collapse section");
    chev.textContent = "▾";
    const title = document.createElement("span");
    title.className = "card-title";
    title.textContent = blockTitle(blk);
    const spacer = document.createElement("span");
    spacer.className = "card-head-spacer";
    head.append(chev, title, spacer);
    section.appendChild(head);

    const body = document.createElement("div");
    body.className = "card-body";
    const content = document.createElement("div");
    content.className = "block-content";
    if (kind === "sequence") {
      // Server pre-rendered the SVG; inject as-is.
      content.innerHTML = blk.svg || "";
      // Diagram blocks skip the hover-actions strip (it would overlap the
      // chart). Instead any click inside the SVG opens a comment scoped to
      // the step that was clicked; onHoverAction extracts step_id from
      // event.target.closest("[data-step-id]"). Listener lives on content
      // so updateBlockContent's innerHTML swap doesn't drop it.
      content.addEventListener("click", (ev) => {
        onHoverAction(section, "comment", ev);
      });
    } else if (kind === "diagram") {
      // Server pre-rendered the Mermaid SVG; inject as-is. This is trusted
      // server output and deliberately bypasses sanitizeFreeHtml so Mermaid's
      // inline <style> survives. v1 has no per-node hit targets, so there is
      // no step-click listener — whole-diagram comments come from the
      // hover-actions strip (renderHoverActions does not skip "diagram").
      content.innerHTML = blk.svg || "";
    } else if (kind === "choice") {
      renderChoice(section, content, blk);
    } else if (kind === "mockup") {
      // Trusted Claude HTML in a sandboxed iframe; deliberately bypasses
      // sanitizeFreeHtml (the sandbox is the isolation boundary instead).
      renderMockup(content, blk);
    } else {
      // Markdown path — markdown-it now allows inline HTML (`html: true`);
      // sanitize the rendered tree before glossary decoration.
      content.innerHTML = blockMd ? blockMd.render(blk.markdown || "") : (blk.markdown || "");
      sanitizeFreeHtml(content);
      if (window.AnnotateGlossary) window.AnnotateGlossary.decorate(content);
    }
    body.appendChild(content);
    section.appendChild(body);

    renderVersionBadge(section, blk.version ?? 1);
    setupCollapse(section, head, chev, blk);
    return section;
  }

  function collapseKey(blockId) {
    const rid = (document.body.dataset.responseId || "default");
    return `annotate.collapsed:${rid}:${blockId}`;
  }

  function setupCollapse(section, head, chev, blk) {
    let collapsed = false;
    try { collapsed = localStorage.getItem(collapseKey(blk.id)) === "1"; } catch (_) {}
    applyCollapsed(section, chev, collapsed);
    head.addEventListener("click", (ev) => {
      // Don't toggle when interacting with the version chip.
      if (ev.target && ev.target.closest && ev.target.closest(".section-pill")) return;
      const next = !section.classList.contains("collapsed");
      applyCollapsed(section, chev, next);
      try { localStorage.setItem(collapseKey(blk.id), next ? "1" : "0"); } catch (_) {}
    });
  }

  function applyCollapsed(section, chev, collapsed) {
    section.classList.toggle("collapsed", collapsed);
    if (chev) {
      chev.textContent = collapsed ? "▸" : "▾";
      chev.setAttribute("aria-label", collapsed ? "Expand section" : "Collapse section");
    }
  }

  function renderVersionBadge(section, version) {
    // Composite gutter pill: left = section number (parsed from the block id,
    // e.g. "section-3" → 3), right = version. Always visible; the version half
    // lights up accent only once the block has been rewritten (v > 1).
    const v = Math.max(1, parseInt(version, 10) || 1);
    const idMatch = String(section.dataset.blockId || "").match(/(\d+)$/);
    const sectionNo = idMatch ? idMatch[1] : "·";
    let pill = section.querySelector(".section-pill");
    if (!pill) {
      pill = document.createElement("span");
      pill.className = "section-pill";
      const sec = document.createElement("span");
      sec.className = "sp-sec";
      const ver = document.createElement("span");
      ver.className = "sp-ver";
      pill.append(sec, ver);
      (section.querySelector(".card-head") || section).appendChild(pill);
    }
    pill.querySelector(".sp-sec").textContent = sectionNo;
    pill.querySelector(".sp-ver").textContent = `v${v}`;
    pill.classList.toggle("bumped", v > 1);
    pill.title = v > 1 ? `Section ${sectionNo} · rewritten (v${v})` : `Section ${sectionNo}`;
  }

  // ── Comment cards ──────────────────────────────────────────────────────────

  // Resolve a diagram step (or free-HTML sub-unit) to its row node, display
  // label, and 1-based ordinal — so a comment card can name the row it targets.
  function stepContextFor(blockId, stepId) {
    if (!blockId || !stepId) return null;
    const section = document.querySelector(`section.block[data-block-id="${cssEsc(blockId)}"]`);
    if (!section) return null;
    let node = section.querySelector(`[data-step-id="${cssEsc(stepId)}"]`);
    let ordinal = null;
    if (node) {
      ordinal = [...section.querySelectorAll("[data-step-id]")].indexOf(node) + 1;
    } else {
      node = section.querySelector(`[data-annotate-id="${cssEsc(stepId)}"]`);
    }
    if (!node) return null;
    const labelNode = node.querySelector ? node.querySelector(".arrow-label") : null;
    let label = ((labelNode ? labelNode.textContent : node.textContent) || "")
      .replace(/\s+/g, " ").trim();
    if (label.length > 48) label = label.slice(0, 47).trimEnd() + "…";
    return { node, ordinal, label };
  }

  // Add the "updating" spinner overlay + timer to a block section. Idempotent:
  // a section already overlaid is left alone. Mirrors the inline logic the
  // comment-submit path uses.
  function startUpdatingOverlay(section) {
    if (!section) return;
    section.classList.add("is-updating");
    if (section.querySelector(".updating-overlay")) return;
    const overlay = document.createElement("div");
    overlay.className = "updating-overlay";
    overlay.setAttribute("role", "status");
    overlay.setAttribute("aria-live", "polite");
    const pill = document.createElement("div");
    pill.className = "updating-pill";
    const spinner = document.createElement("span");
    spinner.className = "updating-spinner";
    pill.appendChild(spinner);
    const label = document.createElement("span");
    label.className = "updating-label";
    label.textContent = "updating";
    pill.appendChild(label);
    const timer = document.createElement("span");
    timer.className = "updating-timer";
    timer.textContent = "0:00";
    pill.appendChild(timer);
    overlay.appendChild(pill);
    section.appendChild(overlay);
    const startedAt = Date.now();
    section._updatingTimerId = setInterval(() => {
      const elapsed = Math.floor((Date.now() - startedAt) / 1000);
      const m = Math.floor(elapsed / 60);
      const s = String(elapsed % 60).padStart(2, "0");
      timer.textContent = `${m}:${s}`;
    }, 1000);
  }

  function buildCard(id, a, onSubmitCb) {
    const card = document.createElement("div");
    card.className = "comment-card";
    card.dataset.id = id;
    card.dataset.type = a.type;

    // For diagram-row / sub-unit comments, head the card with the step it
    // targets, and wire a focus/hover link that highlights the matching row.
    const stepCtx = a.step_id ? stepContextFor(a.block_id, a.step_id) : null;

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

    if (a.step_id) {
      const head = document.createElement("div");
      head.className = "card-step-head";
      const chip = document.createElement("span");
      chip.className = "card-step-chip";
      chip.textContent = stepCtx && stepCtx.ordinal ? `STEP ${stepCtx.ordinal}` : a.step_id;
      head.appendChild(chip);
      if (stepCtx && stepCtx.label) {
        const lbl = document.createElement("span");
        lbl.className = "card-step-label";
        lbl.textContent = stepCtx.label;
        head.appendChild(lbl);
      }
      card.appendChild(head);

      // Card ↔ row link: focusing or hovering the card lights up its row.
      const row = stepCtx && stepCtx.node;
      if (row) {
        const on = () => { row.dataset.cardFocus = "1"; };
        const off = () => { delete row.dataset.cardFocus; };
        card.addEventListener("mouseenter", on);
        card.addEventListener("mouseleave", off);
        card.addEventListener("focusin", on);
        card.addEventListener("focusout", off);
      }
    }

    if (a.selected_text) {
      const quote = document.createElement("div");
      quote.className = "quote";
      quote.dataset.type = a.type;
      quote.textContent = a.selected_text;
      card.appendChild(quote);
    }

    const wrap = document.createElement("div");
    wrap.className = "editor-wrap";

    const ta = document.createElement("textarea");
    const pasteState = {
      pastes: (annotations[id].images || []).map(img => ({
        token: img.token,
        path: img.path,
        thumbUrl: null,
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

    const autoGrow = () => {
      if (wrap.dataset.userSized === "1") return;
      ta.style.height = "auto";
      const cap = Math.max(160, Math.round(window.innerHeight * 0.5));
      ta.style.height = Math.min(ta.scrollHeight + 2, cap) + "px";
    };

    ta.addEventListener("focus", autoGrow);

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
        handle.removeEventListener("pointercancel", up);
        try { handle.releasePointerCapture(e.pointerId); } catch (_) {}
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
      // pointercancel fires if capture is lost (e.g. the card is replaced by a
      // poll-driven update mid-drag); without this the move listener would leak
      // on a detached node, pinning the textarea/wrap closures.
      handle.addEventListener("pointercancel", up);
    });
    handle.addEventListener("dblclick", () => {
      delete wrap.dataset.userSized;
      ta.style.height = "";
      autoGrow();
    });

    wrap.appendChild(ta);
    wrap.appendChild(handle);
    card.appendChild(wrap);
    card.appendChild(pasteStrip);
    renderStrip();
    // Auto-grow once on initial render so a card with prior content shows it all.
    queueMicrotask(autoGrow);

    // ── Per-comment Submit button ──────────────────────────────────────────
    const submitRow = document.createElement("div");
    submitRow.className = "card-submit-row";
    const hint = document.createElement("span");
    hint.className = "card-submit-hint";
    hint.innerHTML = '<kbd>⌘</kbd><kbd>↩</kbd> to submit · paste an image to attach';
    submitRow.appendChild(hint);
    const submitBtn = document.createElement("button");
    submitBtn.type = "button";
    submitBtn.className = "card-submit-btn";
    submitBtn.textContent = "Submit";
    // ⌘/Ctrl+Enter submits from the textarea.
    ta.addEventListener("keydown", (ev) => {
      if ((ev.metaKey || ev.ctrlKey) && ev.key === "Enter") {
        ev.preventDefault();
        if (!submitBtn.disabled) submitBtn.click();
      }
    });
    submitBtn.addEventListener("click", () => {
      const text = annotations[id]?.comment || "";
      const images = annotations[id]?.images || [];
      const payload = {
        block_id: a.block_id || null,
        step_id: a.step_id ?? null,
        type: a.type || "comment",
        text,
        selected_text: a.selected_text || "",
        images,
      };
      if (a.prefix !== undefined) payload.prefix = a.prefix;
      if (a.suffix !== undefined) payload.suffix = a.suffix;
      if (a.block_id) {
        const snippet = blockSnippet(a.block_id);
        if (snippet) payload.block_snippet = snippet;
      }
      submitBtn.disabled = true;
      WebCompanion.api.submit(payload).then((res) => {
        // Remove the card; the block itself gets the updating overlay so the
        // user has visible feedback while Claude responds.
        const eventId = res && res.event_id;
        // Track the pending event so the overlay clears when Claude acks it,
        // regardless of which block (if any) gets rewritten in response.
        if (eventId) pendingEvents.set(String(eventId), { blockId: a.block_id });
        delete annotations[id];
        saveDrafts();
        document.body.classList.toggle("is-editing", Object.keys(annotations).length > 0);
        card.remove();
        applyEngagedStyling();
        const section = document.querySelector(`section.block[data-block-id="${cssEsc(a.block_id)}"]`);
        startUpdatingOverlay(section);
      }).catch(() => {
        submitBtn.disabled = false;
      });
    });
    submitRow.appendChild(submitBtn);
    card.appendChild(submitRow);

    // ── Image paste ────────────────────────────────────────────────────────
    ta.addEventListener("paste", async (ev) => {
      const items = ev.clipboardData?.items;
      if (!items) return;
      let imageItem = null;
      for (const it of items) {
        if (it.kind === "file" && it.type.startsWith("image/")) { imageItem = it; break; }
      }
      if (!imageItem) return;
      ev.preventDefault();
      const blob = imageItem.getAsFile();
      if (!blob) return;
      const token = `paste-${pasteState.nextIndex++}`;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const insertion = `![${token}]`;
      ta.value = ta.value.slice(0, start) + insertion + ta.value.slice(end);
      const caret = start + insertion.length;
      ta.setSelectionRange(caret, caret);
      annotations[id].comment = ta.value;
      saveDrafts();
      try {
        const result = await WebCompanion.api.pasteImage(blob);
        pasteState.pastes.push({ token, path: result.path, thumbUrl: URL.createObjectURL(blob) });
        persistImages();
        renderStrip();
      } catch (_) {
        showPasteError("upload failed");
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
    // Prune orphan drafts: a block-scoped draft whose block no longer exists
    // (Claude removed it) can never render its card — and thus can never be
    // closed — so it would linger in localStorage forever. Also drop any
    // legacy block_id-null drafts from the retired general-comments UI; the
    // page-level composer no longer renders cards for them.
    let pruned = false;
    for (const [id, a] of Object.entries(annotations)) {
      if (!a.block_id ||
          !document.querySelector(`section.block[data-block-id="${cssEsc(a.block_id)}"]`)) {
        delete annotations[id];
        pruned = true;
      }
    }
    if (pruned) saveDrafts();

    document.querySelectorAll(".inline-comments").forEach(el => el.remove());

    const byBlock = {};
    for (const [id, a] of Object.entries(annotations)) {
      (byBlock[a.block_id] ||= []).push([id, a]);
    }

    for (const [blockId, items] of Object.entries(byBlock)) {
      // Insert after the <section.block> that wraps the block.
      const section = document.querySelector(`section.block[data-block-id="${cssEsc(blockId)}"]`);
      if (!section) continue;
      const wrap = document.createElement("div");
      wrap.className = "inline-comments";
      wrap.dataset.forBlock = blockId;
      for (const [id, a] of items) wrap.appendChild(buildCard(id, a));
      section.insertAdjacentElement("afterend", wrap);
    }

    // EDITING lock: any open comment card means one editor is active.
    document.body.classList.toggle("is-editing", Object.keys(annotations).length > 0);
  }

  function focusComment(id) {
    const card = document.querySelector(`.comment-card[data-id="${id}"]`);
    if (!card) return;
    const ta = card.querySelector("textarea");
    if (ta) ta.focus({ preventScroll: true });
  }

  // ── Done button ────────────────────────────────────────────────────────────

  const doneBtn = document.getElementById("done-btn");
  if (doneBtn) {
    doneBtn.addEventListener("click", async () => {
      if (!window.confirm("Mark this annotation round as done? Claude will resume.")) return;
      doneBtn.disabled = true;
      const ok = await WebCompanion.api.finish();
      if (ok) {
        window.location.reload();
      } else {
        doneBtn.disabled = false;
      }
    });
  }

  // ── General composer (page-level, non-block comment) ────────────────────────
  // A persistent textarea that sends a block_id-null comment straight to Claude
  // Code. Unlike block comments it leaves no inline card; status is reported in
  // the composer's own status line and resolved when Claude acks the event.
  (function initGeneralComposer() {
    const input = document.getElementById("general-input");
    const sendBtn = document.getElementById("general-send");
    const statusEl = document.getElementById("general-status");
    if (!input || !sendBtn) return;

    const KEY = `annotate.general.${document.body.dataset.responseId || ""}`;
    try { input.value = localStorage.getItem(KEY) || ""; } catch {}

    const sync = () => {
      sendBtn.disabled = input.value.trim() === "";
      try { localStorage.setItem(KEY, input.value); } catch {}
    };
    sync();

    function send() {
      const text = input.value.trim();
      if (!text) return;
      sendBtn.disabled = true;
      const payload = { block_id: null, step_id: null, type: "comment", text, selected_text: "", images: [] };
      WebCompanion.api.submit(payload).then((res) => {
        const eventId = res && res.event_id;
        if (eventId) pendingEvents.set(String(eventId), { general: true });
        input.value = "";
        try { localStorage.removeItem(KEY); } catch {}
        sync();
        // The server queues events, so a send while Claude is mid-update is
        // safe — but say so, instead of implying an immediate response.
        if (statusEl) {
          statusEl.textContent = document.body.classList.contains("is-busy")
            ? "queued — Claude will get to it after the current update…"
            : "sent — Claude is responding…";
        }
      }).catch(() => {
        sendBtn.disabled = false;
        if (statusEl) statusEl.textContent = "send failed — try again";
      });
    }

    input.addEventListener("input", sync);
    // Same chord as the block cards: Enter is a newline, ⌘/Ctrl+Enter sends.
    // Plain-Enter-to-send once cost a user a multi-line answer mid-compose.
    input.addEventListener("keydown", (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "Enter") { e.preventDefault(); send(); }
    });
    sendBtn.addEventListener("click", send);
  })();

  // ── Polling / block refresh ────────────────────────────────────────────────

  function clearUpdatingOverlay(section) {
    section.classList.remove("is-updating");
    if (section._updatingTimerId) {
      clearInterval(section._updatingTimerId);
      section._updatingTimerId = null;
    }
    section.querySelector(".updating-overlay")?.remove();
  }

  // Clear the "updating" UI for every comment whose event Claude has acked.
  // This is the real done-signal: it fires whether Claude answered by
  // rewriting the commented block, a neighbour, a new block, or nothing —
  // none of which the old "same-block version bump" check could detect.
  function handleConsumedEvents(consumed) {
    if (!Array.isArray(consumed) || pendingEvents.size === 0) return;
    for (const eid of consumed) {
      const key = String(eid);
      const pend = pendingEvents.get(key);
      if (!pend) continue;
      pendingEvents.delete(key);
      if (pend.blockId) {
        const section = document.querySelector(`section.block[data-block-id="${cssEsc(pend.blockId)}"]`);
        if (section) clearUpdatingOverlay(section);
      } else if (pend.general) {
        const statusEl = document.getElementById("general-status");
        if (statusEl) statusEl.textContent = "responded";
      }
    }
  }

  // Caption the spinner with the live label the PostToolUse hook published
  // for each in-flight event ("Reading files…", "Editing the response…").
  // No entry → the label stays "updating". The label is one of a fixed
  // server-side allowlist, so nothing sensitive can land here.
  function applyProgress(progress) {
    if (!progress || pendingEvents.size === 0) return;
    for (const [eid, pend] of pendingEvents) {
      const label = progress[eid];
      if (!label) continue;
      if (pend.blockId) {
        const section = document.querySelector(
          `section.block[data-block-id="${cssEsc(pend.blockId)}"]`);
        const el = section && section.querySelector(".updating-label");
        if (el) el.textContent = label;
      } else if (pend.general) {
        const statusEl = document.getElementById("general-status");
        if (statusEl) statusEl.textContent = label;
      }
    }
  }

  // Server-authoritative page lock. `data.busy` is true while any submitted
  // event is unacked; reflect it as body.is-busy + a banner. Survives reload
  // and is consistent across devices because it is recomputed each poll.
  function setBusy(busy) {
    document.body.classList.toggle("is-busy", !!busy);
    let banner = document.getElementById("busy-banner");
    if (busy) {
      if (!banner) {
        banner = document.createElement("div");
        banner.id = "busy-banner";
        banner.className = "busy-banner";
        banner.setAttribute("role", "status");
        banner.setAttribute("aria-live", "polite");
        const spin = document.createElement("span");
        spin.className = "busy-spinner";
        const label = document.createElement("span");
        label.textContent = "Claude is updating the plan… the page is locked until it replies.";
        banner.append(spin, label);
        // Place the lock ribbon at the top of the content (just under the
        // header, above the composer) so it pins flush to the top of the
        // screen when the page scrolls — not buried below the composer.
        const header = document.querySelector(".page-header");
        if (header) header.insertAdjacentElement("afterend", banner);
        else (document.querySelector(".general-composer") || proseEl)
          ?.parentNode?.insertBefore(banner, document.querySelector(".general-composer") || proseEl);
      }
    } else if (banner) {
      banner.remove();
    }
  }

  // ── Statusline strip ───────────────────────────────────────────────────
  // Live mirror of the terminal statusline (context %, model, rate limits,
  // diff, cost), polled alongside the document. Source is /statusline, which
  // the server reads from a per-session snapshot statusline.sh writes each
  // render. Rebuilds only when the payload actually changes.
  let lastStatuslineJSON = null;

  function slTone(p) { return p >= 75 ? "tone-hot" : p >= 50 ? "tone-warn" : "tone-ok"; }

  function slFmtTok(n) {
    if (n >= 1e6) { const m = n / 1e6; return (m % 1 === 0 ? m : m.toFixed(1)) + "M"; }
    if (n >= 1000) { const k = n / 1000; return (n < 10000 ? k.toFixed(1) : Math.round(k)) + "k"; }
    return String(n);
  }

  function buildStatstrip(data) {
    const strip = document.getElementById("statstrip");
    if (!strip) return;
    if (!data || !data.ok) { strip.hidden = true; strip.replaceChildren(); return; }

    const frag = document.createDocumentFragment();
    const seg = (cls) => { const s = document.createElement("span"); s.className = "sl-seg" + (cls ? " " + cls : ""); return s; };
    const lbl = (t) => { const e = document.createElement("span"); e.className = "sl-lbl"; e.textContent = t; return e; };
    const val = (t) => { const e = document.createElement("span"); e.className = "sl-val"; e.textContent = t; return e; };

    if (data.context) {
      const c = data.context, s = seg(slTone(c.pct));
      const dot = document.createElement("span"); dot.className = "sl-dot";
      const bar = document.createElement("span"); bar.className = "sl-bar";
      const fill = document.createElement("i"); fill.style.width = Math.min(100, Math.max(0, c.pct)) + "%"; bar.appendChild(fill);
      const sub = document.createElement("span"); sub.className = "sl-sub"; sub.textContent = slFmtTok(c.used) + " / " + slFmtTok(c.total);
      s.append(dot, lbl("context"), bar, val(c.pct + "%"), sub);
      frag.appendChild(s);
    }
    if (data.model) {
      const s = seg();
      const m = document.createElement("span"); m.className = "sl-model"; m.textContent = data.model.label;
      s.append(lbl("model"), m);
      if (data.model.badge) { const b = document.createElement("span"); b.className = "sl-badge"; b.textContent = data.model.badge; s.appendChild(b); }
      frag.appendChild(s);
    }

    const spacer = document.createElement("span"); spacer.className = "sl-spacer"; frag.appendChild(spacer);

    if (data.rate_limits) {
      for (const [key, short] of [["five_hour", "5h"], ["seven_day", "7d"]]) {
        const p = data.rate_limits[key];
        if (typeof p === "number") {
          const s = seg(slTone(p));
          const dot = document.createElement("span"); dot.className = "sl-dot";
          s.append(dot, lbl(short), val(p + "%"));
          frag.appendChild(s);
        }
      }
    }
    if (data.diff && (typeof data.diff.added === "number" || typeof data.diff.removed === "number")) {
      const s = seg(); s.appendChild(lbl("diff"));
      if (typeof data.diff.added === "number") { const a = document.createElement("span"); a.className = "sl-add"; a.textContent = "+" + slFmtTok(data.diff.added); s.appendChild(a); }
      if (typeof data.diff.removed === "number") { const d = document.createElement("span"); d.className = "sl-del"; d.textContent = "−" + slFmtTok(data.diff.removed); s.appendChild(d); }
      frag.appendChild(s);
    }

    strip.replaceChildren(frag);
    strip.hidden = false;
  }

  function refreshStatusline() {
    fetch(BASE + "statusline", { cache: "no-store" })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        const j = JSON.stringify(data);
        if (j === lastStatuslineJSON) return;
        lastStatuslineJSON = j;
        buildStatstrip(data);
      })
      .catch(() => { /* swallow — next tick retries */ });
  }

  // A heartbeat older than this means the watcher (and the Claude session
  // that owns it) is dead, not slow — the watcher writes every ~1s, including
  // while it blocks on an ack.
  const WATCHER_DEAD_AFTER_S = 15;

  // The session behind this page died mid-event (crash, closed terminal).
  // Without this, the unacked event keeps busy=true forever and the page
  // stays locked with a spinner that lies. Show the truth and unlock.
  function setWatcherDead(dead) {
    let banner = document.getElementById("watcher-dead-banner");
    if (dead) {
      if (!banner) {
        banner = document.createElement("div");
        banner.id = "watcher-dead-banner";
        banner.className = "watcher-dead-banner";
        banner.setAttribute("role", "alert");
        banner.setAttribute("aria-live", "assertive");
        const label = document.createElement("span");
        label.textContent =
          "Claude's session is gone — your last submission was not processed. " +
          "Re-run annotate from a Claude session to pick this page back up.";
        banner.append(label);
        const header = document.querySelector(".page-header");
        if (header) header.insertAdjacentElement("afterend", banner);
        else document.body.insertBefore(banner, document.body.firstChild);
      }
    } else if (banner) {
      banner.remove();
    }
  }

  function onPollDelta(data) {
    const watcherDead = typeof data.watcher_age_s === "number"
      && data.watcher_age_s > WATCHER_DEAD_AFTER_S;
    setWatcherDead(watcherDead);
    // A dead watcher means no ack is ever coming — don't keep the page
    // locked on its behalf.
    setBusy(data.busy && !watcherDead);
    refreshStatusline();
    // 1. Clear spinners for comments Claude finished processing.
    handleConsumedEvents(data.consumed_events);
    // 1b. Caption any still-running spinner with the live progress label.
    applyProgress(data.progress);
    // 2. Reconcile the DOM against the full document. /raw carries everything
    //    (per-block markdown/svg + version + glossary), so one fetch covers
    //    structure, content, and glossary in a single pass.
    fetch(BASE + "raw", { cache: "no-store" })
      .then(r => r.ok ? r.json() : null)
      .then(doc => {
        if (!doc) return;
        reconcile(doc);
        syncGlossary(doc);
      })
      .catch(() => { /* swallow — next tick retries */ });
  }

  function syncGlossary(doc) {
    if (!window.AnnotateGlossary) return;
    const prev = JSON.stringify(window.AnnotateGlossary._lastGlossary || []);
    const next = JSON.stringify(doc.glossary || []);
    if (next !== prev) {
      window.AnnotateGlossary.setGlossary(doc.glossary || []);
      window.AnnotateGlossary._lastGlossary = doc.glossary || [];
      window.AnnotateGlossary.refreshAll();
    }
  }

  // Bring the rendered block list in line with the server document: insert
  // newly-added blocks (in order), drop removed ones, and refresh blocks whose
  // version bumped. Surgical on purpose — it touches comment wrappers only for
  // removed blocks, so a draft the user is mid-typing on an unchanged block is
  // never rebuilt out from under them.
  function reconcile(doc) {
    if (!proseEl) return;
    const serverBlocks = doc.blocks || [];
    const serverIds = new Set(serverBlocks.map(b => b.id));

    // Remove sections (and their inline-comments wrapper) for deleted blocks,
    // clearing any running updating-timer so it can't leak.
    proseEl.querySelectorAll("section.block").forEach(section => {
      if (!serverIds.has(section.dataset.blockId)) {
        clearUpdatingOverlay(section);
        const ic = section.nextElementSibling;
        if (ic && ic.classList.contains("inline-comments")) ic.remove();
        section.remove();
      }
    });

    // Walk server order; insert missing blocks at the right spot, refresh
    // version-bumped ones. `anchor` trails the last placed section (past its
    // comment wrapper) so an inserted block lands in document order.
    let anchor = null;
    for (const blk of serverBlocks) {
      let section = proseEl.querySelector(`section.block[data-block-id="${cssEsc(blk.id)}"]`);
      if (!section) {
        section = createBlockSection(blk);
        if (anchor) anchor.insertAdjacentElement("afterend", section);
        else proseEl.insertBefore(section, proseEl.firstChild);
      } else {
        const domVer = parseInt(section.dataset.version || "1", 10);
        const srvVer = parseInt(blk.version, 10) || 1;
        if (srvVer > domVer) section = updateBlockContent(section, blk, srvVer);
      }
      const ic = section.nextElementSibling;
      anchor = (ic && ic.classList.contains("inline-comments")) ? ic : section;
    }

    renderHoverActions();
    applyEngagedStyling();
  }

  // Refresh one block's content in place. Returns the section now in the DOM
  // (a fresh node when the block's kind flipped). Clears the updating overlay
  // as a fallback for the case where the refreshed block IS the commented one.
  function updateBlockContent(section, blk, srvVer) {
    const newKind = blk.kind || "markdown";
    const oldKind = section.dataset.kind || "markdown";
    // A kind flip (markdown↔sequence/diagram/choice) needs a fresh section:
    // the diagram click listener and hover wiring are bound at creation, so an
    // in-place innerHTML swap would leave them inconsistent with the new kind.
    if (newKind !== oldKind || newKind === "choice" || newKind === "mockup") {
      const fresh = createBlockSection(blk);
      clearUpdatingOverlay(section);
      section.replaceWith(fresh);
      return fresh;
    }
    const content = section.querySelector(".block-content");
    if (content) {
      if (newKind === "sequence" || newKind === "diagram") {
        content.innerHTML = blk.svg || "";
      } else if (blockMd) {
        content.innerHTML = blockMd.render(blk.markdown || "");
        sanitizeFreeHtml(content);
        if (window.AnnotateGlossary) window.AnnotateGlossary.decorate(content);
      }
    }
    section.dataset.kind = newKind;
    section.dataset.version = String(blk.version ?? srvVer);
    renderVersionBadge(section, blk.version ?? srvVer);
    setCardTitle(section, blk);
    clearUpdatingOverlay(section);
    return section;
  }

  // ── First-time toast ───────────────────────────────────────────────────────

  function showFirstTimeToast() {
    try {
      if (localStorage.getItem("annotate.welcomed") === "1") return;
    } catch (_) {}
    const toast = document.createElement("div");
    toast.className = "first-time-toast";
    toast.innerHTML = `
      <span>👋 Hover any block to comment, reject, or delete it. Unmarked blocks are approved.</span>
      <button type="button" class="dismiss" aria-label="Dismiss">×</button>
    `;
    document.body.appendChild(toast);
    toast.querySelector(".dismiss").addEventListener("click", () => {
      try { localStorage.setItem("annotate.welcomed", "1"); } catch (_) {}
      toast.remove();
    });
  }

  // ── Boot ───────────────────────────────────────────────────────────────────

  WebCompanion.init({ onPollDelta });
  loadAndRenderBlocks();
})();
