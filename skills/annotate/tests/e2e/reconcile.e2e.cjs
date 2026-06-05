#!/usr/bin/env node
/*
 * Playwright end-to-end regression for the annotate client reconciliation bug.
 *
 * Reproduces the exact reported scenario and asserts the fix:
 *   1. Page renders block b-0.
 *   2. User comments on b-0 and submits → an "updating" spinner overlays b-0.
 *   3. Claude responds by ADDING a new block (b-1) and leaving b-0 unchanged,
 *      then acks the event (writes consumed/<id>.ack).
 *   4. Assert (Defect A): the new block b-1 renders in the DOM.
 *   5. Assert (Defect B): b-0's updating spinner clears — even though b-0's own
 *      version never bumped — because clearing is keyed on the consumed event.
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/reconcile.e2e.cjs
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
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-e2e-home-"));
  const proc = spawn("python3",
    ["-m", "skills.annotate.server"],
    {
      cwd: REPO_ROOT,
      env: {
        ...process.env,
        PYTHONPATH: REPO_ROOT,
        HOME: fakeHome,
        ANNOTATE_PUBLIC_HOST: "localhost",
        ANNOTATE_SHUTDOWN_SECONDS: "120",
      },
    });
  return new Promise((resolve, reject) => {
    const rl = readline.createInterface({ input: proc.stdout });
    rl.on("line", (line) => {
      try {
        const info = JSON.parse(line);
        if (info.type === "server-started") resolve({ proc, info, rl, fakeHome });
      } catch (_) { /* http log lines — ignore, but keep draining */ }
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
      (res) => {
        let buf = "";
        res.on("data", (c) => (buf += c));
        res.on("end", () => resolve({ status: res.statusCode, body: buf }));
      });
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function writeBlocks(responseDir, blocks) {
  const doc = { response_id: "resp-e2e", title: "e2e", blocks };
  const tmp = path.join(responseDir, "blocks.json.tmp");
  fs.writeFileSync(tmp, JSON.stringify(doc));
  fs.renameSync(tmp, path.join(responseDir, "blocks.json"));
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
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-e2e-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    const responseDir = sess.response_dir;
    const eventsDir = sess.events_dir;
    const consumedDir = sess.consumed_dir;

    // Initial doc: a single markdown block.
    writeBlocks(responseDir, [{ id: "b-0", markdown: "# Original\n\nThe only block." }]);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });

    // 1. b-0 renders.
    await page.waitForSelector('section.block[data-block-id="b-0"]', { timeout: 8000 });
    log("✓ b-0 rendered");

    // 2. Comment on b-0 and submit.
    const b0 = page.locator('section.block[data-block-id="b-0"]');
    await b0.hover();
    await b0.locator('.hover-actions button[data-type="comment"]').click();
    const ta = page.locator('.comment-card textarea').first();
    await ta.fill("Please add a second block explaining the details.");
    await page.locator('.card-submit-btn').first().click();

    // Spinner overlay appears on b-0.
    await page.waitForSelector('section.block[data-block-id="b-0"].is-updating', { timeout: 5000 });
    log("✓ updating spinner shown on b-0 after submit");

    // 3. Simulate Claude: add b-1, leave b-0 untouched, then ack the event.
    const eventId = fs.readdirSync(eventsDir).filter(f => f.endsWith(".json"))[0].replace(/\.json$/, "");
    writeBlocks(responseDir, [
      { id: "b-0", markdown: "# Original\n\nThe only block." },         // UNCHANGED
      { id: "b-1", markdown: "## Details\n\nThe freshly added block." }, // NEW
    ]);
    fs.writeFileSync(path.join(consumedDir, eventId + ".ack"), "");

    // 4. Defect A: the new block renders (poll is 1s; allow margin).
    await page.waitForSelector('section.block[data-block-id="b-1"]', { timeout: 8000 });
    const b1text = await page.locator('section.block[data-block-id="b-1"]').innerText();
    if (!b1text.includes("freshly added block")) fail("b-1 rendered but content missing");
    log("✓ Defect A fixed: newly-added block b-1 rendered with its content");

    // 5. Defect B: b-0's spinner clears (its own version never bumped).
    await page.waitForSelector('section.block[data-block-id="b-0"].is-updating',
      { state: "detached", timeout: 8000 });
    const overlays = await page.locator('section.block[data-block-id="b-0"] .updating-overlay').count();
    if (overlays !== 0) fail("b-0 still has an updating overlay");
    log("✓ Defect B fixed: b-0 spinner cleared via consumed event (no version bump)");

    log("\nE2E PASSED");
    cleanup();
    process.exit(0);
  } catch (err) {
    log("\nE2E FAILED: " + (err && err.stack ? err.stack : err));
    cleanup();
    process.exit(1);
  }
})();
