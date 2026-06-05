---
name: phone-cleanup
description: Clean up an Android phone (especially Xiaomi / MIUI / HyperOS) over adb to fix fast battery drain — diagnose background drain, debloat Xiaomi junk, trim the battery-optimization whitelist, restrict heavy apps, and tune battery settings. ONLY use when explicitly invoked ("clean up my phone", "/phone-cleanup", "debloat my phone", "phone battery draining"). Requires a USB-connected phone with USB debugging enabled. Every change is reversible and must be confirmed with the user first.
disable-model-invocation: true
allowed-tools:
  - Bash
  - Read
  - AskUserQuestion
---

# Phone Cleanup (Android / Xiaomi via adb)

Diagnose and fix fast battery drain on an Android phone over adb. Developed and tested
against a **Xiaomi POCO running HyperOS/MIUI (Android 14)** — the MIUI-specific notes
assume that family, but the core technique is generic adb.

**Iron rule: never change anything before the user confirms it.** Diagnose first,
show findings, get an explicit yes per category, then apply. Keep a list of exactly
what you changed so you can reverse it.

## Workflow

1. **Connect.** `adb devices -l` must show the phone as `device` (not `unauthorized`
   or empty). If not: enable Settings → Developer options → **USB debugging**, set USB
   mode to File Transfer, and accept the "Allow USB debugging?" prompt. On MIUI also
   enable **Install via USB**.
2. **Diagnose (read-only).** Run the scanner by absolute path (cwd is not guaranteed to
   be the skill dir, and it must be executable):
   `chmod +x <skill-dir>/scan.sh && <skill-dir>/scan.sh`. It changes nothing — it ranks
   per-app power use, lists apps exempt from battery optimization, shows running
   foreground services, and recent installs. Ignore whitelist entries it marks `[keep]`
   (known system/push providers — see NEVER touch).
3. **Classify the findings** into the three buckets below. Map any UID to a package
   with `cmd package list packages --uid <appid>` (appid = 10000 + the `u0aNNN` number).
4. **Confirm with the user**, category by category. Use AskUserQuestion if unsure of scope.
5. **Apply** the chosen fixes (commands below). `force-stop` after an *uninstall* or a
   *restrict* so it takes effect immediately. Whitelist removal needs no force-stop
   (it just stops future free wakeups).
6. **Record** every package you touched + the reversal command, and hand it to the user.
7. **Verify.** Have the user unplug, use the phone a few hours, then re-run `scan.sh` and
   compare — the targeted apps should drop down or vanish from the power ranking and the
   foreground-service list. Don't claim success without this.

## What to look for

| Bucket | Signal in scan | Action |
|--------|----------------|--------|
| **Xiaomi/MIUI bloat** | ads / store / telemetry / AI services running with no user benefit | uninstall for user 0 (strongest), or restrict |
| **Over-privileged whitelist** | sync/fitness/travel apps in the doze whitelist that wake freely | remove from whitelist (still works when opened) |
| **Heavy 3rd-party** | Facebook polling sensors, remote-access tools, sync clients with persistent foreground services | restrict background + restricted bucket, or uninstall if unwanted |

## Command reference (all reversible)

```bash
PKG=com.example.app
# Remove for current user — true "gone", survives reboot. STRONGEST.
adb shell pm uninstall --user 0 $PKG
adb shell pm install-existing --user 0 $PKG      # <-- reverse it

# Soft restrict (keep the app, stop background work)
adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore   # reverse: ... allow
adb shell am set-standby-bucket $PKG restricted              # reverse: ... active
adb shell dumpsys deviceidle whitelist -$PKG                 # reverse: whitelist +$PKG
adb shell am force-stop $PKG                                 # apply now
```

Note: on system apps, `set-standby-bucket restricted` may bounce back to `active` while
the process is alive — the durable lever is `RUN_ANY_IN_BACKGROUND ignore` (it sticks).

## Battery settings levers (display & radios)

Beyond app debloat, check these system settings — `scan.sh` prints their current values.
Split them by whether they change the UX:

**Pure wins (no visual change) — apply freely after a yes:**
```bash
adb shell settings put global wifi_scan_always_enabled 0   # stop 24/7 Wi-Fi location scanning (reverse: 1)
adb shell settings put global ble_scan_always_enabled 0    # stop 24/7 Bluetooth location scanning (reverse: 1)
```
These do NOT affect connecting to saved Wi-Fi / paired BT devices — only location scanning
while the radios are toggled off. Only downside: slightly looser indoor location.

**Visual-change wins — confirm because the user will see/feel them:**
```bash
adb shell cmd uimode night yes                  # dark theme — big AMOLED saving (reverse: no)
adb shell settings put secure doze_always_on 0  # turn OFF Always-On Display — steady 5–10%/day drain (reverse: 1)
```

**Already-good signals (don't fiddle if so):** low brightness + `screen_brightness_mode=1`
(adaptive) is already optimal; animations at `0.0` are already maxed.

**Ask, don't assume — per-user preferences:** Always-On Display and "stay awake while
charging" (`stay_on_while_plugged_in`) may be wanted ON for development. Confirm before
touching either.

**Not adb-flippable, worth mentioning:** if `scan.sh` shows weak cellular signal
(LTE RSRP < −110 dBm), suggest enabling **Wi-Fi Calling** in dialer settings — a weak-signal
modem is a top-3 drain.

## Known-safe on Xiaomi/MIUI (debloat targets)

Confirmed safe to `pm uninstall --user 0` on Xiaomi/MIUI — pure ads/telemetry/store:
`com.miui.msa.global` (MIUI ads) · `com.miui.analytics` (telemetry) ·
`com.xiaomi.mipicks` (GetApps) · `com.xiaomi.aicr` (AI). Other common debloat
candidates to *offer* (not assume): `com.xiaomi.discover`, `com.miui.weather2`,
`com.mi.globalminusscreen` (App Vault), `com.miui.android.fashiongallery` (Glance lockscreen).

## NEVER touch

System-critical — removing these breaks notifications, calls, or boot:
`com.xiaomi.xmsf` / `com.xiaomi.mipush` (push — kills notifications),
`com.miui.securitycenter`, `com.miui.powerkeeper`, `com.android.*`, `com.qualcomm.*`,
`vendor.qti.*`, `com.google.android.gms` / `gsf`, anything you can't identify.
If unsure what a package is, look it up before suggesting removal — don't guess.

## Common mistakes

- Uninstalling without user confirmation. Don't. Confirm per category first.
- Reading the power ranking while charging and trusting absolute numbers — counters
  reset on plug-in; use it for *relative* ranking and the foreground-service/whitelist
  lists instead.
- Removing a push/notification provider (`xmsf`) and wondering why messages stop.
- **Trying to "reclaim" app cache. Don't — this skill does not touch cache.** `pm trim-caches`
  is a no-op unless storage is nearly full, the cache is working data apps rebuild instantly,
  and clearing it yields zero battery benefit. Storage cache is out of scope; never present it
  as a win.
- Forgetting to record reversal commands. Always hand the user the undo list.
