#!/usr/bin/env node
// Stamp package.json with version "0.1.<git-rev-list-count>".
// Matches the IntelliJ plugin's commit-count convention so the same commit
// produces matching version numbers on both surfaces.
const { execSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const pkgPath = path.join(__dirname, "..", "package.json");
const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
const count = execSync("git rev-list --count HEAD", { encoding: "utf8" }).trim();
const next = `0.1.${count}`;
if (pkg.version !== next) {
  pkg.version = next;
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + "\n", "utf8");
  console.log(`version stamped: ${next}`);
} else {
  console.log(`version already ${next}`);
}
