# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

## Android phone: POCO X5 Pro 5G (model 22101320G, MIUI, Android 14)

Connects via adb (`adb devices` → transport works when USB debugging is on). Build Android with raw SDK tools (aapt2/javac/d8/apksigner), NOT Gradle — JDK 25 is installed and breaks AGP.

**MIUI gotchas (learned the hard way):**
- **Never `adb uninstall` then reinstall.** Fresh adb installs fail with `INSTALL_FAILED_USER_RESTRICTED` unless "Install via USB" is on (needs Mi account + SIM). In-place update (`adb install -r`) is allowed. Recovery for a fresh install: `adb push` the APK to `/sdcard/Download` and tap it in the Files app.
- **`adb_wifi_enabled` (wireless debugging) is locked by MIUI** — writes rejected even from a privileged adb shell. App-level WiFi-debugging toggle is impossible on this device (works on AOSP/Pixel).

**Debug Toggles app** (`~/projects/env/apps/android-debug-toggle`): single home-screen icon "USB Debug" that flips `Settings.Global.adb_enabled` via `WRITE_SECURE_SETTINGS`. After any fresh install, re-grant once: `adb shell pm grant com.petros.debugtoggle android.permission.WRITE_SECURE_SETTINGS` (persists across reboots + updates, not fresh installs). See its README.md for build/install steps.


