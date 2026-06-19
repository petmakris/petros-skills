// Voice-to-text dictation for annotate comment boxes.
//
// Progressive enhancement, fully self-contained: it adds a 🎤 button to every
// comment card's submit row and streams the browser's built-in SpeechRecognition
// transcript into the textarea. No dependency, no server, no subscription.
//
// It never touches script.js internals — dictated text is written to the
// textarea and a synthetic `input` event is dispatched, so the existing draft-
// save / auto-grow listeners pick it up exactly as if the user had typed.
//
// Guards: if the API is missing entirely (Firefox), no button is rendered and
// typing is unaffected. If the API exists but the page isn't a secure context
// (e.g. opened over the http://hostname Tailscale URL rather than
// http://localhost), the button is shown disabled with a tooltip explaining
// how to enable it — discoverable rather than mysteriously absent.
(function () {
  "use strict";

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return;                          // unsupported → silent no-op
  const SECURE = window.isSecureContext;    // false over http://<hostname>
  const INSECURE_HINT =
    "Voice needs a secure context — open this page via http://localhost to dictate";

  // At most one recognition runs at a time across all cards.
  let active = null; // { rec, btn, ta }

  function stopActive() {
    if (active) {
      try { active.rec.stop(); } catch (_) {}
    }
  }

  function cleanup(btn) {
    btn.classList.remove("listening");
    btn.setAttribute("aria-pressed", "false");
    btn.title = "Dictate (speech to text)";
    if (active && active.btn === btn) active = null;
  }

  function start(ta, btn) {
    const rec = new SR();
    rec.lang = navigator.language || "en-US";
    rec.continuous = true;
    rec.interimResults = true;

    // Snapshot the textarea so dictation appends after existing text. Add a
    // separating space if the box already has content not ending in whitespace.
    let base = ta.value;
    if (base && !/\s$/.test(base)) base += " ";

    rec.onresult = (event) => {
      // If a poll-driven reconcile rebuilt this comment card mid-dictation, the
      // textarea we captured is detached — writing to it silently loses the
      // transcript. Stop instead so the mic button resets cleanly.
      if (!ta.isConnected) { try { rec.stop(); } catch (_) {} return; }
      // Rebuild the whole utterance each event (continuous keeps all results),
      // which is simplest and avoids double-counting finalized segments.
      let txt = "";
      for (const result of event.results) txt += result[0].transcript;
      ta.value = base + txt;
      ta.dispatchEvent(new Event("input", { bubbles: true }));
    };
    rec.onerror = (event) => {
      if (event.error === "not-allowed" || event.error === "service-not-allowed") {
        btn.disabled = true;
        btn.title = "Microphone blocked — allow mic access to dictate";
      }
    };
    rec.onend = () => cleanup(btn);

    active = { rec, btn, ta };
    btn.classList.add("listening");
    btn.setAttribute("aria-pressed", "true");
    btn.title = "Stop dictation";
    try {
      rec.start();
    } catch (_) {
      cleanup(btn); // start() throws if a prior session is still tearing down
    }
  }

  function toggle(ta, btn) {
    if (active && active.btn === btn) { stopActive(); return; }
    stopActive();      // hand the mic over from any other card
    start(ta, btn);
  }

  function makeMicButton(ta) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "voice-mic-btn";
    btn.setAttribute("aria-label", "Dictate comment");
    btn.setAttribute("aria-pressed", "false");
    btn.textContent = "🎤";
    if (!SECURE) {
      btn.disabled = true;
      btn.title = INSECURE_HINT;
      return btn;
    }
    btn.title = "Dictate (speech to text)";
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      toggle(ta, btn);
    });
    return btn;
  }

  // Attach a mic button to a submit row that doesn't have one yet, placed just
  // left of the Submit button so it reads as an input action.
  function attach(row) {
    if (row.querySelector(".voice-mic-btn")) return;
    const card = row.closest(".comment-card");
    const ta = card && card.querySelector("textarea");
    if (!ta) return;
    const mic = makeMicButton(ta);
    const submit = row.querySelector(".card-submit-btn");
    if (submit) row.insertBefore(mic, submit);
    else row.appendChild(mic);
  }

  function scan(root) {
    if (root.nodeType !== 1) return;
    if (root.matches && root.matches(".card-submit-row")) attach(root);
    root.querySelectorAll && root.querySelectorAll(".card-submit-row").forEach(attach);
  }

  // Comment cards are created dynamically; watch for them.
  const observer = new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const node of m.addedNodes) scan(node);
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
  scan(document.body); // catch anything already present
})();
