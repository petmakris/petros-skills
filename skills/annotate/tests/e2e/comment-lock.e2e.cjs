#!/usr/bin/env node
/*
 * Playwright e2e: comment submit must clear is-editing so hover-actions
 * remain interactive after the first round-trip.
 *
 * Scenario:
 *  1. Seed 2 markdown blocks (section-1, section-2).
 *  2. Open a comment editor on section-1.
 *  3. Assert body.is-editing is true.
 *  4. Type a comment and submit.
 *  5. Assert busy-banner appears (page is BUSY).
 *  6. Simulate Claude acking the event (no block change needed).
 *  7. Assert banner clears, is-editing is FALSE, is-busy is FALSE.
 *  8. Assert a NEW comment editor can be opened on section-2 (would time out
 *     on buggy code because hover-actions are pointer-events:none).
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/comment-lock.e2e.cjs
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
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-comment-lock-home-"));
  const proc = spawn("python3", ["-m", "skills.annotate.server"], {
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
      } catch (_) { /* http log lines */ }
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

function getJSON(port, urlPath) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: "localhost", port, path: urlPath, method: "GET" },
      (res) => {
        let buf = "";
        res.on("data", (c) => (buf += c));
        res.on("end", () => resolve({ status: res.statusCode, body: buf }));
      });
    req.on("error", reject);
    req.end();
  });
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
    // Step 1: Seed 2 markdown blocks.
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-comment-lock-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    const responseDir = sess.response_dir;
    const eventsDir = sess.events_dir;
    const consumedDir = sess.consumed_dir;

    const doc = {
      response_id: "r-comment-lock",
      title: "Comment lock test",
      blocks: [
        { id: "section-1", title: "Alpha", markdown: "First paragraph." },
        { id: "section-2", title: "Beta",  markdown: "Second paragraph." },
      ],
    };
    const tmp = path.join(responseDir, "blocks.json.tmp");
    fs.writeFileSync(tmp, JSON.stringify(doc));
    fs.renameSync(tmp, path.join(responseDir, "blocks.json"));

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('section.block[data-block-id="section-1"]', { timeout: 8000 });
    log("✓ blocks rendered");

    // Step 2: Open a comment editor on section-1.
    await page.hover('section.block[data-block-id="section-1"]');
    await page.click('section.block[data-block-id="section-1"] .hover-actions button[data-type="comment"]');
    await page.waitForSelector(".comment-card textarea", { timeout: 5000 });
    log("✓ comment editor opened on section-1");

    // Step 3: Assert body.is-editing is true.
    const isEditing = await page.evaluate(() => document.body.classList.contains("is-editing"));
    if (!isEditing) fail("body does not have is-editing class after opening comment editor");
    log("✓ body.is-editing is true");

    // Step 4: Type a comment and submit.
    await page.fill(".comment-card textarea", "please clarify");
    await page.click(".comment-card .card-submit-btn");
    log("✓ comment submitted");

    // Step 5: Assert the page goes BUSY.
    await page.waitForSelector(".busy-banner", { timeout: 5000 });
    log("✓ busy-banner visible after submit");

    // Step 6: Simulate Claude acking WITHOUT removing any block.
    const eventFiles = fs.readdirSync(eventsDir).filter(f => f.endsWith(".json"));
    if (eventFiles.length === 0) fail("no event files found in events_dir after comment submit");
    const eventId = eventFiles[0].replace(/\.json$/, "");

    // Leave blocks.json as-is (2 blocks unchanged); just write the .ack.
    fs.writeFileSync(path.join(consumedDir, eventId + ".ack"), "");
    log("✓ ack written for event " + eventId);

    // Step 7: Assert unlock + editing cleared.
    await page.waitForSelector(".busy-banner", { state: "detached", timeout: 8000 });
    const isEditingAfter = await page.evaluate(() => document.body.classList.contains("is-editing"));
    const isBusyAfter = await page.evaluate(() => document.body.classList.contains("is-busy"));
    if (isEditingAfter) fail("body.is-editing not cleared after comment submit ack");
    if (isBusyAfter) fail("body.is-busy not cleared after ack");
    log("✓ busy-banner gone, body.is-editing cleared, body.is-busy cleared");

    // Step 8: Assert the page is usable again — open a new editor on section-2.
    // On buggy code this times out because hover-actions are pointer-events:none.
    await page.hover('section.block[data-block-id="section-2"]');
    await page.click('section.block[data-block-id="section-2"] .hover-actions button[data-type="comment"]');
    await page.waitForSelector(".comment-card textarea", { timeout: 5000 });
    log("✓ new comment editor opened on section-2 — page is interactive again");

    log("\nCOMMENT-LOCK E2E PASSED");
    cleanup();
    process.exit(0);
  } catch (err) {
    log("\nCOMMENT-LOCK E2E FAILED: " + (err && err.stack ? err.stack : err));
    cleanup();
    process.exit(1);
  }
})();
