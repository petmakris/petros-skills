// annotate skill — granular sub-unit marks + batched review rounds.
//
// Decorates rendered markdown blocks with per-unit hover strips
// (✓ agree / ✕ dismiss / 💬 comment). Marks are LOCAL — stored in
// localStorage, nothing wakes Claude — until the user hits the round
// dock's Submit, which sends ONE {type:"round", reactions:[...]} event.
// Claude applies the whole round in a single pass and acks once.
//
// Unit identity on the wire is the existing span format: the unit's
// plain text as selected_text, plus prefix/suffix disambiguation when
// that text occurs more than once in the block.
(function () {
  const RID = document.body.dataset.responseId || "";
  const KEY = `annotate.round.${RID}`;

  // marks: { [markKey]: {block_id, kind, selected_text, prefix?, suffix?, text?} }
  // markKey = `${block_id}::${text}` (content, not offsets) —
  // so a re-render (reconcile/version bump) re-applies surviving marks.
  let marks = loadMarks();
  // event_id of the in-flight submitted round, if any.
  let pendingRound = null;

  function loadMarks() {
    try { return JSON.parse(localStorage.getItem(KEY) || "{}"); }
    catch { return {}; }
  }
  function saveMarks() {
    try { localStorage.setItem(KEY, JSON.stringify(marks)); } catch {}
  }

  const cssEsc = (s) => (window.CSS && CSS.escape)
    ? CSS.escape(String(s))
    : String(s).replace(/["\\\]]/g, "\\$&");

  function unitText(el) {
    return (el.textContent || "").replace(/\s+/g, " ").trim();
  }
  function markKey(blockId, text) { return `${blockId}::${text}`; }

  function occurrences(haystack, needle) {
    if (!needle) return 0;
    let n = 0, i = 0;
    while ((i = haystack.indexOf(needle, i)) !== -1) { n++; i += needle.length; }
    return n;
  }

  // The four sub-unit types (spec decision: all four, one DOM walk).
  // Top-level only: a nested list belongs to its parent bullet's unit.
  const UNIT_SELECTOR = [
    ":scope > ul > li",
    ":scope > ol > li",
    ":scope > p",
    ":scope > pre",
    ":scope > table tbody tr",
    // markdown-it wraps tables bare (no wrapper div); some blocks nest the
    // table under a figure/div via free HTML — cover one level down too.
    ":scope > div table tbody tr",
  ].join(", ");

  function decorate(content, section) {
    const blockId = section && section.dataset ? section.dataset.blockId : null;
    if (!blockId || !content) return;
    let units;
    try { units = content.querySelectorAll(UNIT_SELECTOR); }
    catch { return; }
    units.forEach((el) => {
      // Authored sub-units keep their existing immediate-comment path.
      if (el.closest("[data-annotate-id]")) return;
      if (el.classList.contains("sub-unit")) { applyMarkState(el, blockId); return; }
      const text = unitText(el);
      if (!text) return;
      el.classList.add("sub-unit");
      const strip = document.createElement("span");
      strip.className = "unit-strip";
      strip.setAttribute("aria-hidden", "true");
      for (const [kind, glyph, title] of [
        ["agree", "✓", "Agree (no rewrite)"],
        ["dismiss", "✕", "Remove this item"],
        ["comment", "💬", "Comment on this item"],
      ]) {
        const b = document.createElement("button");
        b.type = "button";
        b.dataset.kind = kind;
        b.textContent = glyph;
        b.title = title;
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          ev.preventDefault();
          if (document.body.classList.contains("is-busy")) return;
          if (kind === "comment") openComposer(el, blockId);
          else toggleMark(el, blockId, kind);
        });
        strip.appendChild(b);
      }
      el.appendChild(strip);
      applyMarkState(el, blockId);
    });
    renderDock();
  }

  function toggleMark(el, blockId, kind) {
    const text = unitText(stripClone(el));
    const key = markKey(blockId, text);
    const existing = marks[key];
    if (existing && existing.kind === kind) {
      delete marks[key];                      // undo
    } else {
      marks[key] = buildMark(el, blockId, kind,
        existing && existing.kind === "comment" ? existing.text : "");
    }
    saveMarks();
    applyMarkState(el, blockId);
    renderDock();
  }

  // textContent of the unit minus our own UI (strip, chip, composer).
  function stripClone(el) {
    const c = el.cloneNode(true);
    c.querySelectorAll(".unit-strip, .unit-chip, .unit-composer")
      .forEach(n => n.remove());
    return c;
  }

  function buildMark(el, blockId, kind, text) {
    const selected = unitText(stripClone(el));
    const mark = { block_id: blockId, kind, selected_text: selected };
    if (text) mark.text = text;
    const section = el.closest("section.block");
    if (section) {
      const blockText = unitText(stripClone(section));
      if (occurrences(blockText, selected) > 1) {
        const idx = blockText.indexOf(selected);
        mark.prefix = blockText.slice(Math.max(0, idx - 20), idx);
        mark.suffix = blockText.slice(idx + selected.length,
                                      idx + selected.length + 20);
      }
    }
    return mark;
  }

  function applyMarkState(el, blockId) {
    const text = unitText(stripClone(el));
    const m = marks[markKey(blockId, text)];
    if (m) el.dataset.mark = m.kind;
    else delete el.dataset.mark;
    const chip = el.querySelector(".unit-chip");
    if (m && m.kind === "comment" && m.text) {
      if (chip) { chip.textContent = "💬 " + m.text; }
      else {
        const c = document.createElement("span");
        c.className = "unit-chip";
        c.textContent = "💬 " + m.text;
        el.appendChild(c);
      }
    } else if (chip) chip.remove();
  }

  // ── Inline per-unit composer (local pin, not a submit) ────────────────────
  let openComposerEl = null;

  function openComposer(el, blockId) {
    closeComposer();
    const text = unitText(stripClone(el));
    const existing = marks[markKey(blockId, text)];
    const wrap = document.createElement("span");
    wrap.className = "unit-composer";
    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = "Ask or push back on this item…";
    input.value = (existing && existing.kind === "comment" && existing.text) || "";
    const pin = document.createElement("button");
    pin.type = "button";
    pin.textContent = "Pin";
    const commit = () => {
      const v = input.value.trim();
      const key = markKey(blockId, text);
      if (v) marks[key] = Object.assign(buildMark(el, blockId, "comment", v));
      else delete marks[key];
      saveMarks();
      closeComposer();
      applyMarkState(el, blockId);
      renderDock();
    };
    pin.addEventListener("click", commit);
    input.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter") { ev.preventDefault(); commit(); }
      if (ev.key === "Escape") { ev.preventDefault(); closeComposer(); }
    });
    wrap.append(input, pin);
    el.appendChild(wrap);
    openComposerEl = wrap;
    input.focus();
  }

  function closeComposer() {
    if (openComposerEl) { openComposerEl.remove(); openComposerEl = null; }
  }

  // ── Round dock ─────────────────────────────────────────────────────────────
  function renderDock() {
    let dock = document.getElementById("round-dock");
    const count = Object.keys(marks).length;
    if (!count) { if (dock) dock.remove(); return; }
    if (!dock) {
      dock = document.createElement("div");
      dock.id = "round-dock";
      const btn = document.createElement("button");
      btn.type = "button";
      btn.id = "round-submit";
      btn.addEventListener("click", submitRound);
      dock.appendChild(btn);
      document.body.appendChild(dock);
    }
    const btn = dock.querySelector("#round-submit");
    btn.textContent = pendingRound
      ? "Applying round…"
      : `Submit round (${count})`;
    btn.disabled = !!pendingRound || document.body.classList.contains("is-busy");
  }

  function submitRound() {
    const reactions = Object.values(marks).map((m) => {
      const r = { kind: m.kind, block_id: m.block_id,
                  selected_text: m.selected_text,
                  text: m.text || "", images: [] };
      if (m.prefix !== undefined) r.prefix = m.prefix;
      if (m.suffix !== undefined) r.suffix = m.suffix;
      return r;
    });
    if (!reactions.length || pendingRound) return;
    WebCompanion.api.submit({ type: "round", reactions }).then((res) => {
      pendingRound = res && res.event_id ? String(res.event_id) : null;
      renderDock();
    }).catch(() => { renderDock(); });
  }

  function clearRound() {
    marks = {};
    pendingRound = null;
    saveMarks();
    document.querySelectorAll(".sub-unit[data-mark]").forEach(el => {
      delete el.dataset.mark;
      el.querySelector(".unit-chip")?.remove();
    });
    renderDock();
  }

  // Poll integration: disable the dock while busy; clear marks when our
  // round's event id shows up acked in consumed_events (dismissed units
  // disappear via the normal reconcile pass — version bump / block rewrite).
  function onPoll(data) {
    if (pendingRound && Array.isArray(data.consumed_events) &&
        data.consumed_events.map(String).includes(pendingRound)) {
      clearRound();
      return;
    }
    renderDock();
  }

  window.AnnotateSubunits = { decorate, onPoll };
})();
