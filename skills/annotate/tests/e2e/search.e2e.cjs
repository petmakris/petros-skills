#!/usr/bin/env node
/*
 * Playwright e2e for the annotate block-search feature.
 *
 * Seeds 3 blocks (one mentions "proposal", two do not), then drives the
 * header search box and asserts:
 *   - typing "proposal" hides the two non-matching blocks and keeps the match
 *   - the count line reads "Showing 1 of 3 blocks"
 *   - the matched term is wrapped in <mark class="search-hit">
 *   - "/" focuses the box from anywhere; "Escape" clears + restores all blocks
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/search.e2e.cjs
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
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-search-home-"));
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

function writeBlocks(responseDir, blocks) {
  const doc = { response_id: "resp-search", title: "search-e2e", blocks };
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
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-search-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    writeBlocks(sess.response_dir, [
      { id: "b-0", markdown: "# Proposal validation\n\nChecks the advisor proposal draft." },
      { id: "b-1", markdown: "# Docker builds\n\nDo not build images without tests." },
      { id: "b-2", markdown: "# Parallel tests\n\nEnable parallel integration tests." },
    ]);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('section.block[data-block-id="b-0"]', { timeout: 8000 });
    log("✓ blocks rendered");

    // Type the query.
    await page.fill("#block-search", "proposal");

    // Matching block stays visible; non-matching blocks hidden.
    // Wait for the filter to settle on its stable end-state. We poll the DOM
    // directly (rather than waitForSelector, which would wait for a
    // display:none element to become "visible" and never resolve) so the
    // assertion survives the page's live block-reconcile poll.
    await page.waitForFunction(() => {
      const at = (id) => document.querySelector('section.block[data-block-id="' + id + '"]');
      const b0 = at("b-0"), b1 = at("b-1"), b2 = at("b-2");
      return b0 && b1 && b2 &&
        !b0.classList.contains("search-hidden") &&
        b1.classList.contains("search-hidden") &&
        b2.classList.contains("search-hidden");
    }, { timeout: 5000 });
    const b0hidden = await page.locator('section.block[data-block-id="b-0"].search-hidden').count();
    if (b0hidden !== 0) fail("matching block b-0 was hidden");
    const b2hidden = await page.locator('section.block[data-block-id="b-2"].search-hidden').count();
    if (b2hidden !== 1) fail("non-matching block b-2 not hidden");
    log("✓ non-matching blocks hidden, match kept");

    // Count line.
    const countText = (await page.locator(".search-count").innerText()).trim();
    if (countText !== "Showing 1 of 3 blocks") fail("count line wrong: " + JSON.stringify(countText));
    log("✓ count line correct");

    // Highlight present in the matching block.
    const marks = await page.locator('section.block[data-block-id="b-0"] mark.search-hit').count();
    if (marks < 1) fail("matched term not highlighted");
    log("✓ matched term highlighted");

    // The × clear button is visible while a query is active; clicking it restores.
    if (!(await page.locator("#block-search-clear").isVisible())) {
      fail("clear (×) button not visible while query active");
    }
    await page.click("#block-search-clear");
    const afterClearHidden = await page.locator("section.block.search-hidden").count();
    if (afterClearHidden !== 0) fail("clear button did not restore hidden blocks");
    if ((await page.locator(".search-count").count()) !== 0) fail("count line not removed after × clear");
    if ((await page.inputValue("#block-search")) !== "") fail("× clear did not empty the input");
    if (await page.locator("#block-search-clear").isVisible()) fail("× clear still visible after clearing");
    log("✓ × clear button restores all blocks");

    // Escape clears and restores all blocks (re-type first so Escape is exercised).
    await page.fill("#block-search", "proposal");
    await page.waitForFunction(() => {
      const b1 = document.querySelector('section.block[data-block-id="b-1"]');
      return b1 && b1.classList.contains("search-hidden");
    }, { timeout: 5000 });
    await page.focus("#block-search");
    await page.keyboard.press("Escape");
    const stillHidden = await page.locator("section.block.search-hidden").count();
    if (stillHidden !== 0) fail("Escape did not restore hidden blocks");
    const countAfter = await page.locator(".search-count").count();
    if (countAfter !== 0) fail("count line not removed after clear");
    log("✓ Escape clears query and restores all blocks");

    // "/" focuses the box from the body.
    await page.locator("body").click();
    await page.keyboard.press("/");
    const focusedId = await page.evaluate(() => document.activeElement && document.activeElement.id);
    if (focusedId !== "block-search") fail('"/" did not focus the search box');
    log('✓ "/" focuses the search box');

    log("\nSEARCH E2E PASSED");
    cleanup();
    process.exit(0);
  } catch (err) {
    log("\nSEARCH E2E FAILED: " + (err && err.stack ? err.stack : err));
    cleanup();
    process.exit(1);
  }
})();
