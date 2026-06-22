#!/usr/bin/env node
/*
 * Playwright e2e: per-region annotation of a `mockup` block (Phase 2).
 *
 * Clicks a [data-annotate-id] region INSIDE the sandboxed iframe and asserts:
 *   1. The bridge forwards the click; the host opens a comment editor.
 *   2. Submitting records an event whose step_id is the region's slug.
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup-region.e2e.cjs
 * (requires the global `playwright` package + an installed chromium)
 */
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const readline = require("readline");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..", "..");

function log(msg) { process.stdout.write(msg + "\n"); }
function fail(msg) { throw new Error("ASSERTION FAILED: " + msg); }

function startServer() {
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-region-home-"));
  const proc = spawn("python3", ["-m", "skills.annotate.server"], {
    cwd: REPO_ROOT,
    env: { ...process.env, PYTHONPATH: REPO_ROOT, HOME: fakeHome,
           ANNOTATE_PUBLIC_HOST: "localhost", ANNOTATE_SHUTDOWN_SECONDS: "120" },
  });
  return new Promise((resolve, reject) => {
    const rl = readline.createInterface({ input: proc.stdout });
    rl.on("line", (line) => {
      try {
        const info = JSON.parse(line);
        if (info.type === "server-started") resolve({ proc, info, rl, fakeHome });
      } catch (_) { /* http log lines — ignore */ }
    });
    proc.stderr.on("data", () => {});
    proc.on("exit", (code) => reject(new Error("server exited early: " + code)));
    setTimeout(() => reject(new Error("server start timeout")), 8000);
  });
}

function postJSON(port, urlPath, body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const req = http.request(
      { host: "localhost", port, path: urlPath, method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": data.length } },
      (res) => { let buf = ""; res.on("data", (c) => (buf += c)); res.on("end", () => resolve({ status: res.statusCode, body: buf })); });
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function writeBlocks(responseDir, blocks) {
  const doc = { response_id: "resp-region-e2e", title: "region-e2e", blocks };
  const tmp = path.join(responseDir, "blocks.json.tmp");
  fs.writeFileSync(tmp, JSON.stringify(doc));
  fs.renameSync(tmp, path.join(responseDir, "blocks.json"));
}

async function waitForEvent(dir, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const files = fs.readdirSync(dir).filter((f) => f.endsWith(".json"));
    if (files.length) return JSON.parse(fs.readFileSync(path.join(dir, files[0]), "utf8"));
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error("no event recorded within " + ms + "ms");
}

(async () => {
  const { proc, info, fakeHome } = await startServer();
  let browser;
  const cleanup = () => {
    try { browser && browser.close(); } catch (_) {}
    try { proc.kill(); } catch (_) {}
    try { fs.rmSync(fakeHome, { recursive: true, force: true }); } catch (_) {}
  };
  try {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-region-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    const responseDir = sess.response_dir;
    const eventsDir = sess.events_dir;

    writeBlocks(responseDir, [
      { id: "b-0", kind: "mockup", version: 1, spec: { title: "Mock",
        html: "<div data-annotate-id='hero' style='height:140px;background:#e7eaf2;" +
              "display:flex;align-items:center;justify-content:center'>HERO REGION</div>" } },
    ]);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });

    await page.waitForSelector("section[data-block-id='b-0'] iframe.mockup-frame", { timeout: 8000 });
    log("✓ mockup iframe rendered");

    // The first-time toast overlays the page; remove it so it can't intercept
    // the click (a real user's toast auto-dismisses on first interaction).
    await page.evaluate(() => { const t = document.querySelector(".first-time-toast"); if (t) t.remove(); });

    // Click the tagged region INSIDE the sandboxed iframe.
    const fl = page.frameLocator("section[data-block-id='b-0'] iframe.mockup-frame");
    await fl.locator("[data-annotate-id='hero']").click({ timeout: 8000 });
    log("✓ clicked [data-annotate-id='hero'] inside the iframe");

    // The bridge forwarded the click; the host opened a comment editor.
    await page.waitForSelector(".comment-card textarea", { timeout: 8000 });
    log("✓ host opened a comment editor from the forwarded click");

    await page.locator(".comment-card textarea").first().fill("make the hero taller");
    await page.locator(".card-submit-btn").first().click();

    // The submitted event is scoped to the region slug.
    const evt = await waitForEvent(eventsDir, 8000);
    if (evt.block_id !== "b-0") fail("event block_id wrong: " + evt.block_id);
    if (evt.step_id !== "hero") fail("event step_id must be 'hero', got: " + evt.step_id);
    if (!String(evt.text).includes("hero taller")) fail("event text missing");
    log("✓ event recorded with step_id='hero' (per-region scope round-tripped)");

    log("PASS: per-region annotation of a mockup works end to end");
  } finally {
    cleanup();
  }
})().catch((e) => { log(e.stack || String(e)); process.exit(1); });
