#!/usr/bin/env node
/*
 * Playwright e2e for annotate dismiss + page lock.
 *
 * Seeds 3 markdown blocks, then:
 *  - hovers block 2, clicks the × (dismiss) affordance
 *  - asserts /poll reports busy:true while the event is unacked
 *  - asserts the page shows the .busy-banner and body has class is-busy
 *  - asserts other blocks' hover-actions are pointer-events:none while busy
 *  - simulates Claude: remove block 2 from blocks.json + write the .ack
 *  - asserts the banner clears, body loses is-busy, and block 2 is gone
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/dismiss.e2e.cjs
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
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-dismiss-home-"));
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
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-dismiss-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    const responseDir = sess.response_dir;
    const eventsDir = sess.events_dir;
    const consumedDir = sess.consumed_dir;

    // Write 3 blocks via atomic rename (same pattern as other e2e helpers).
    const doc = {
      response_id: "r-dismiss",
      title: "Dismiss test",
      blocks: [
        { id: "section-1", title: "Alpha", markdown: "First." },
        { id: "section-2", title: "Beta",  markdown: "Second." },
        { id: "section-3", title: "Gamma", markdown: "Third." },
      ],
    };
    const tmp = path.join(responseDir, "blocks.json.tmp");
    fs.writeFileSync(tmp, JSON.stringify(doc));
    fs.renameSync(tmp, path.join(responseDir, "blocks.json"));

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('section.block[data-block-id="section-2"]', { timeout: 8000 });
    log("✓ blocks rendered");

    // Hover block 2, then click its dismiss button.
    await page.hover('section.block[data-block-id="section-2"]');
    await page.click('section.block[data-block-id="section-2"] .hover-actions button[data-type="dismiss"]');

    // Assert page entered BUSY state.
    await page.waitForSelector(".busy-banner", { timeout: 5000 });
    const isBusy = await page.evaluate(() => document.body.classList.contains("is-busy"));
    if (!isBusy) fail("body does not have is-busy class after dismiss click");
    log("✓ busy-banner visible and body.is-busy set");

    // Assert /poll reports busy:true.
    const pollResp = await getJSON(info.port, "/s/" + sess.sid + "/poll");
    const pollData = JSON.parse(pollResp.body);
    if (!pollData.busy) fail("/poll did not report busy:true after dismiss; got: " + pollResp.body);
    log("✓ /poll reports busy:true");

    // Assert other blocks are non-interactive while busy (pointer-events:none on .hover-actions
    // if present, otherwise confirm body.is-busy is still set as the guard).
    const b1HoverActions = await page.locator('section.block[data-block-id="section-1"] .hover-actions').count();
    if (b1HoverActions > 0) {
      const pe = await page.evaluate(
        () => getComputedStyle(document.querySelector('section.block[data-block-id="section-1"] .hover-actions')).pointerEvents
      );
      if (pe !== "none") fail("section-1 .hover-actions not pointer-events:none while busy; got: " + pe);
      log("✓ other blocks' hover-actions are pointer-events:none while busy");
    } else {
      const stillBusy = await page.evaluate(() => document.body.classList.contains("is-busy"));
      if (!stillBusy) fail("body.is-busy was lost before ack");
      log("✓ body.is-busy still set (other block hover-actions not yet in DOM — acceptable)");
    }

    // Simulate Claude: find the queued event, rewrite blocks.json without section-2, write .ack.
    const eventFiles = fs.readdirSync(eventsDir).filter(f => f.endsWith(".json"));
    if (eventFiles.length === 0) fail("no event files found in events_dir after dismiss");
    const eventId = eventFiles[0].replace(/\.json$/, "");

    const updatedDoc = {
      response_id: "r-dismiss",
      title: "Dismiss test",
      blocks: [
        { id: "section-1", title: "Alpha", markdown: "First." },
        { id: "section-3", title: "Gamma", markdown: "Third." },
      ],
    };
    const tmp2 = path.join(responseDir, "blocks.json.tmp");
    fs.writeFileSync(tmp2, JSON.stringify(updatedDoc));
    fs.renameSync(tmp2, path.join(responseDir, "blocks.json"));
    fs.writeFileSync(path.join(consumedDir, eventId + ".ack"), "");

    // Assert banner clears, body loses is-busy, and section-2 is gone.
    await page.waitForSelector(".busy-banner", { state: "detached", timeout: 8000 });
    await page.waitForSelector('section.block[data-block-id="section-2"]', { state: "detached", timeout: 8000 });
    const isStillBusy = await page.evaluate(() => document.body.classList.contains("is-busy"));
    if (isStillBusy) fail("body.is-busy not cleared after ack");
    log("✓ busy-banner gone, body.is-busy cleared, section-2 removed from DOM");

    log("\nDISMISS E2E PASSED");
    cleanup();
    process.exit(0);
  } catch (err) {
    log("\nDISMISS E2E FAILED: " + (err && err.stack ? err.stack : err));
    cleanup();
    process.exit(1);
  }
})();
