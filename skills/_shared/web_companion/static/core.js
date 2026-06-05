// Shared web_companion client core.  Polling loop, composer, submit, finish.
(function () {
  const BASE = (() => {
    const p = window.location.pathname;
    return p.endsWith("/") ? p : p + "/";
  })();

  const pollIntervalMs = 1000;
  let lastVersions = {};
  let onPollDelta = () => {};
  let pollTimer = null;

  const api = {
    BASE,
    async fetchJSON(path, opts) {
      const r = await fetch(BASE + path, opts || {});
      if (!r.ok) throw new Error(`${path}: ${r.status}`);
      return await r.json();
    },
    async fetchText(path) {
      const r = await fetch(BASE + path);
      if (!r.ok) throw new Error(`${path}: ${r.status}`);
      return await r.text();
    },
    async submit(payload) {
      const r = await fetch(BASE + "api/submit", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!r.ok) throw new Error("submit failed: " + r.status);
      return await r.json();
    },
    async finish() {
      const r = await fetch(BASE + "api/finish", { method: "POST" });
      return r.ok;
    },
    async cancel() {
      const r = await fetch(BASE + "api/cancel", { method: "POST" });
      return r.ok;
    },
    async pasteImage(blob) {
      const r = await fetch(BASE + "api/upload", {
        method: "POST", headers: { "Content-Type": blob.type || "image/png" }, body: blob,
      });
      if (!r.ok) throw new Error("upload failed: " + r.status);
      return await r.json();
    },
  };

  async function pollOnce() {
    try {
      const data = await api.fetchJSON("poll");
      if (data.finished) {
        document.body.classList.add("session-finished");
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
      }
      onPollDelta(data, lastVersions);
      lastVersions = { ...(data.blocks || {}), ...(data.threads || {}) };
    } catch (e) {
      console.warn("poll failed", e);
    }
  }

  function startPolling() {
    pollOnce();
    pollTimer = setInterval(pollOnce, pollIntervalMs);
  }

  window.WebCompanion = {
    api,
    init({ onPollDelta: handler }) {
      onPollDelta = handler || (() => {});
      startPolling();
    },
  };
})();
