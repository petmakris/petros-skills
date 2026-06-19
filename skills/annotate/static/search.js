// Block search — client-side fuzzy filter over rendered annotate blocks.
// Zero server interaction: indexes section.block textContent, hides
// non-matches, highlights literal hits, rebuilds on DOM reconcile.
(function () {
  "use strict";

  const SEARCH_ID = "block-search";
  let fuse = null;
  let countEl = null;
  let observer = null;
  let indexDirty = false;

  function prose() { return document.querySelector("main.prose"); }

  function blockSections() {
    return Array.from(document.querySelectorAll("main.prose section.block[data-block-id]"));
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  function buildIndex() {
    const items = blockSections().map((s) => ({ id: s.dataset.blockId, text: s.textContent || "" }));
    fuse = new Fuse(items, {
      keys: ["text"],
      threshold: 0.3,
      ignoreLocation: true,
      includeMatches: true,
    });
  }

  function ensureCountEl() {
    const root = prose();
    if (!root) return null;
    if (!countEl || !countEl.isConnected) {
      countEl = document.createElement("div");
      countEl.className = "search-count";
      root.insertBefore(countEl, root.firstChild);
    }
    return countEl;
  }

  function removeCountEl() {
    if (countEl && countEl.isConnected) countEl.remove();
    countEl = null;
  }

  function clearHighlights(root) {
    root.querySelectorAll("mark.search-hit").forEach((m) => {
      const parent = m.parentNode;
      parent.replaceChild(document.createTextNode(m.textContent), m);
      parent.normalize();
    });
  }

  function highlight(section, terms) {
    if (!terms.length) return;
    const re = new RegExp("(" + terms.map(escapeRe).join("|") + ")", "gi");
    const walker = document.createTreeWalker(section, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
        const el = node.parentElement;
        if (!el) return NodeFilter.FILTER_REJECT;
        if (el.closest("svg")) return NodeFilter.FILTER_REJECT;        // skip diagram SVG text
        if (el.closest("mark.search-hit")) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    const targets = [];
    let n;
    while ((n = walker.nextNode())) targets.push(n);
    targets.forEach((node) => {
      const text = node.nodeValue;
      const local = new RegExp(re.source, "gi");
      if (!local.test(text)) return;
      local.lastIndex = 0;
      const frag = document.createDocumentFragment();
      let last = 0;
      let m;
      while ((m = local.exec(text))) {
        if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        const mark = document.createElement("mark");
        mark.className = "search-hit";
        mark.textContent = m[0];
        frag.appendChild(mark);
        last = m.index + m[0].length;
        if (m.index === local.lastIndex) local.lastIndex++; // guard against zero-width
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
  }

  function pauseObserver() { if (observer) observer.disconnect(); }
  function resumeObserver() {
    const root = prose();
    if (observer && root) observer.observe(root, { childList: true });
  }

  function applyFilter(query) {
    pauseObserver();
    try {
      const root = prose();
      if (root) clearHighlights(root);
      const sections = blockSections();
      const q = (query || "").trim();

      if (!q) {
        sections.forEach((s) => {
          s.classList.remove("search-hidden");
          const ic = s.nextElementSibling;
          if (ic && ic.classList.contains("inline-comments")) ic.classList.remove("search-hidden");
        });
        removeCountEl();
        return;
      }

      const matched = new Set((fuse ? fuse.search(q) : []).map((r) => r.item.id));
      sections.forEach((s) => {
        const hide = !matched.has(s.dataset.blockId);
        s.classList.toggle("search-hidden", hide);
        const ic = s.nextElementSibling;
        if (ic && ic.classList.contains("inline-comments")) ic.classList.toggle("search-hidden", hide);
      });

      const el = ensureCountEl();
      if (el) el.textContent = "Showing " + matched.size + " of " + sections.length + " blocks";

      const terms = q.split(/\s+/).filter(Boolean);
      sections.forEach((s) => { if (matched.has(s.dataset.blockId)) highlight(s, terms); });
    } finally {
      resumeObserver();
    }
  }

  function init() {
    const input = document.getElementById(SEARCH_ID);
    if (!input) return;
    buildIndex();

    // Apply the current query and reflect whether the box is non-empty, so the
    // clear (×) button and the "/" hint can swap via CSS.
    function refresh() {
      if (indexDirty) { buildIndex(); indexDirty = false; }
      applyFilter(input.value);
      const wrap = input.closest(".header-search");
      if (wrap) wrap.classList.toggle("has-query", input.value.trim().length > 0);
    }

    function clearSearch() {
      input.value = "";
      refresh();
    }

    input.addEventListener("input", refresh);

    const clearBtn = document.getElementById("block-search-clear");
    if (clearBtn) {
      clearBtn.addEventListener("click", () => {
        clearSearch();
        input.focus();
      });
    }

    document.addEventListener("keydown", (e) => {
      const active = document.activeElement;
      const inField = active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement;
      if (e.key === "/" && !inField) {
        e.preventDefault();
        input.focus();
      } else if (e.key === "Escape" && active === input) {
        clearSearch();
        input.blur();
      }
    });

    const root = prose();
    if (root) {
      let t = null;
      observer = new MutationObserver(() => {
        // The content changed, so the fuse index is stale. Mark it dirty and
        // rebuild lazily on the next keystroke. Only re-run the live filter
        // while a query is active — with an empty box there is nothing to
        // re-highlight, and rebuilding every poll tick would be wasted work
        // (and would wipe any in-progress text selection in a block).
        indexDirty = true;
        if (!input.value.trim()) return;
        clearTimeout(t);
        t = setTimeout(() => {
          buildIndex();
          indexDirty = false;
          applyFilter(input.value);
        }, 120);
      });
      observer.observe(root, { childList: true });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
