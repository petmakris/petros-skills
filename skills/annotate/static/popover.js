// Glossary decoration and hover popover.
// Exposes window.AnnotateGlossary = { setGlossary, decorate, refreshAll }.

(function () {
  let glossary = [];
  let popoverEl = null;

  function ensurePopover() {
    if (popoverEl) return popoverEl;
    popoverEl = document.createElement("div");
    popoverEl.id = "gloss-popover";
    popoverEl.innerHTML =
      '<span class="gp-term"></span><span class="gp-def"></span><span class="gp-role"></span>';
    document.body.appendChild(popoverEl);
    return popoverEl;
  }

  function escapeForRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function buildPattern(terms) {
    if (!terms.length) return null;
    // Sort longest-first so multi-word terms match before any substring would.
    const sorted = terms.slice().sort((a, b) => b.length - a.length);
    const alts = sorted.map(escapeForRegex).join("|");
    // Word boundaries that treat [A-Za-z0-9_] as word chars.
    return new RegExp("(?<![A-Za-z0-9_])(" + alts + ")(?![A-Za-z0-9_])");
  }

  function isInsideSkipped(node) {
    let n = node.parentNode;
    while (n && n !== document.body) {
      const tag = n.nodeName;
      if (tag === "CODE" || tag === "PRE") return true;
      if (n.classList && n.classList.contains("gloss-term")) return true;
      n = n.parentNode;
    }
    return false;
  }

  function decorateTextNode(node, pattern, termSet) {
    const text = node.nodeValue;
    pattern.lastIndex = 0;
    if (!pattern.test(text)) return;
    // Build replacement nodes.
    const frag = document.createDocumentFragment();
    // Re-run with /g for iteration.
    const gPattern = new RegExp(pattern.source, "g");
    let lastIdx = 0;
    let m;
    while ((m = gPattern.exec(text)) !== null) {
      const term = m[1];
      if (!termSet.has(term)) continue;
      if (m.index > lastIdx) {
        frag.appendChild(document.createTextNode(text.slice(lastIdx, m.index)));
      }
      const span = document.createElement("span");
      span.className = "gloss-term";
      span.setAttribute("data-term", term);
      span.textContent = term;
      frag.appendChild(span);
      lastIdx = m.index + term.length;
    }
    if (lastIdx < text.length) {
      frag.appendChild(document.createTextNode(text.slice(lastIdx)));
    }
    if (lastIdx > 0) node.parentNode.replaceChild(frag, node);
  }

  function decorate(rootEl) {
    if (!rootEl || !glossary.length) return;
    const terms = glossary.map((g) => g.term).filter(Boolean);
    if (!terms.length) return;
    const pattern = buildPattern(terms);
    const termSet = new Set(terms);
    const walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) =>
        isInsideSkipped(n) || !n.nodeValue.trim()
          ? NodeFilter.FILTER_REJECT
          : NodeFilter.FILTER_ACCEPT,
    });
    const targets = [];
    let n;
    while ((n = walker.nextNode())) targets.push(n);
    targets.forEach((tn) => decorateTextNode(tn, pattern, termSet));
  }

  function refreshAll() {
    document.querySelectorAll(".block-content").forEach((el) => {
      // Strip prior decorations (gloss-term spans → plain text) before redecorating.
      el.querySelectorAll(".gloss-term").forEach((span) => {
        span.replaceWith(document.createTextNode(span.textContent));
      });
      el.normalize();
      decorate(el);
    });
  }

  function setGlossary(newGlossary) {
    glossary = Array.isArray(newGlossary) ? newGlossary : [];
  }

  function lookup(term) {
    return glossary.find((g) => g.term === term) || null;
  }

  function showPopover(target) {
    const term = target.getAttribute("data-term");
    const entry = lookup(term);
    if (!entry) return;
    const el = ensurePopover();
    el.querySelector(".gp-term").textContent = entry.term;
    el.querySelector(".gp-def").textContent = entry.definition || "";
    el.querySelector(".gp-role").textContent = entry.role || "";
    const rect = target.getBoundingClientRect();
    el.style.display = "block";
    el.style.left = (window.scrollX + rect.left) + "px";
    el.style.top = (window.scrollY + rect.bottom + 6) + "px";
  }

  function hidePopover() {
    if (popoverEl) popoverEl.style.display = "none";
  }

  document.addEventListener("mouseover", (e) => {
    const t = e.target;
    if (t && t.classList && t.classList.contains("gloss-term")) showPopover(t);
  });
  document.addEventListener("mouseout", (e) => {
    const t = e.target;
    if (t && t.classList && t.classList.contains("gloss-term")) hidePopover();
  });

  window.AnnotateGlossary = { setGlossary, decorate, refreshAll, lookup };
})();
