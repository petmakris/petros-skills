#!/usr/bin/env node
/*
 * Playwright end-to-end smoke for the annotate `mockup` block kind.
 *
 * Asserts:
 *   1. A kind=mockup block renders into an <iframe class="mockup-frame">.
 *   2. The sandbox is exactly "allow-scripts" (never allow-same-origin).
 *   3. The mockup HTML is present inside the frame document.
 *   4. The host-injected bridge sizes the iframe to its content via postMessage.
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup.e2e.cjs
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
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-mockup-home-"));
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
  const doc = { response_id: "resp-mockup-e2e", title: "mockup-e2e", blocks };
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
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-mockup-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    const responseDir = sess.response_dir;

    writeBlocks(responseDir, [
      { id: "b-0", kind: "mockup", version: 1, spec: {
          title: "Mock",
          html: "<div data-annotate-id='hero' style='height:240px'>HELLO_MOCKUP</div>" } },
    ]);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });

    // 1. The iframe renders inside the mockup block.
    const frame = page.locator("section[data-block-id='b-0'] iframe.mockup-frame");
    await frame.waitFor({ state: "attached", timeout: 8000 });
    log("✓ mockup iframe rendered");

    // 2. Sandbox is exactly allow-scripts; never allow-same-origin.
    const sandbox = await frame.getAttribute("sandbox");
    if (sandbox !== "allow-scripts") fail("sandbox must be exactly 'allow-scripts', got: " + sandbox);
    log("✓ sandbox is exactly 'allow-scripts'");

    // 3. The mockup HTML is inside the frame document.
    const fl = page.frameLocator("section[data-block-id='b-0'] iframe.mockup-frame");
    await fl.locator("text=HELLO_MOCKUP").waitFor({ timeout: 8000 });
    log("✓ mockup HTML present inside the sandboxed frame");

    // 4. The bridge sized the frame to its content (>200px; our hero is 240px).
    await page.waitForFunction(() => {
      const f = document.querySelector("section[data-block-id='b-0'] iframe.mockup-frame");
      return f && parseInt(f.style.height || "0", 10) > 200;
    }, { timeout: 8000 });
    log("✓ height bridge sized the iframe to its content");

    // 5. Rewrite the mockup's spec.html — the spec edit bumps the version, the
    //    client refetches and the fresh iframe shows the new content.
    writeBlocks(responseDir, [
      { id: "b-0", kind: "mockup", version: 1, spec: {
          title: "Mock",
          html: "<div data-annotate-id='hero' style='height:240px'>REWRITTEN_MOCKUP</div>" } },
    ]);
    const fl2 = page.frameLocator("section[data-block-id='b-0'] iframe.mockup-frame");
    await fl2.locator("text=REWRITTEN_MOCKUP").waitFor({ timeout: 10000 });
    log("✓ version-bump re-render swapped the iframe to the new content");

    log("PASS: mockup renders in sandboxed iframe and is sized by the bridge");
  } finally {
    cleanup();
  }
})().catch((e) => { log(e.stack || String(e)); process.exit(1); });
