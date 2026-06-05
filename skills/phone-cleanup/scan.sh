#!/usr/bin/env bash
# Read-only Android battery/bloat diagnostic over adb.
# Makes NO changes — safe to run anytime. Produces a report you interpret
# before deciding what (if anything) to restrict or remove.
#
# Usage: ./scan.sh            (uses the only connected device)
#        ANDROID_SERIAL=XXXX ./scan.sh   (pick a device when several are attached)

set -uo pipefail
A() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

echo "===== DEVICE ====="
if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Plug in, enable USB debugging, accept the 'Allow USB debugging?' prompt."
  echo "Check with: adb devices -l"
  exit 1
fi
adb devices -l | tr -d '\r'
echo "Model:   $(A getprop ro.product.model)"
echo "Android: $(A getprop ro.build.version.release)   HyperOS/MIUI: $(A getprop ro.mi.os.version.name)"
echo "Battery: $(A dumpsys battery | grep -i 'level:' | xargs)  (charging resets the on-battery counters below)"

echo
echo "===== TOP ESTIMATED POWER USE (per app, since last unplug) ====="
# Pull the ranked UID list and resolve each UID to its package name(s).
A dumpsys batterystats | grep -E 'UID [0-9a-z]+:' | head -20 | while read -r line; do
  uid=$(echo "$line" | sed -E 's/.*UID (u0a[0-9]+|[0-9]+):.*/\1/')
  appid=$uid
  case "$uid" in u0a*) appid=$((10000 + ${uid#u0a}));; esac
  pkgs=$(A "cmd package list packages --uid $appid" | sed 's/package://; s/ uid:.*//' | paste -sd, -)
  mah=$(echo "$line" | sed -E 's/.*: ([0-9.]+).*/\1/')
  printf '  %-7s %6s mAh  %s\n' "$uid" "$mah" "${pkgs:-<unknown>}"
done

echo
echo "===== USER APPS EXEMPT FROM BATTERY OPTIMIZATION (doze whitelist) ====="
echo "  (can wake the phone at will — trim anything that doesn't need it)"
echo "  [keep] = system/push provider, leave it alone (removing breaks notifications/alarms)"
# Flag known-critical providers so nobody trims the push service by mistake.
KEEP='xmsf|mipush|securitycenter|powerkeeper|deskclock|com.android|com.google.android.gms|com.google.android.gsf|milink'
A dumpsys deviceidle whitelist | grep '^user,' | sed 's/^user,//; s/,[0-9]*$//' | while read -r p; do
  if echo "$p" | grep -qE "$KEEP"; then echo "  [keep] $p"; else echo "        $p"; fi
done

echo
echo "===== RUNNING FOREGROUND SERVICES (non-system) ====="
A dumpsys activity services | grep -E 'ServiceRecord' \
  | grep -ivE 'com.android|com.google|com.qualcomm|vendor.qti|org.codeaurora' \
  | sed -E 's/.*ServiceRecord\{[^ ]+ u0 ([^}]+)\}.*/  \1/' | sort -u | head -30

echo
echo "===== 15 MOST RECENTLY INSTALLED/UPDATED THIRD-PARTY APPS ====="
A "dumpsys package | grep -E 'Package \[|lastUpdateTime='" | paste - - \
  | grep -vE 'com.android|com.google|com.qualcomm|com.miui|com.xiaomi|miui|com.qti' \
  | sed -E 's/.*Package \[([^]]+)\].*lastUpdateTime=([0-9-]+ [0-9:]+)/  \2  \1/' \
  | sort -r | head -15

echo
echo "===== BATTERY SETTINGS LEVERS (see SKILL.md 'Battery settings levers') ====="
echo "  brightness (0-255):       $(A settings get system screen_brightness)  (adaptive: $(A settings get system screen_brightness_mode), 1=auto)"
echo "  dark theme:               $(A cmd uimode night | sed 's/Night mode: //')   <- 'yes' saves AMOLED power"
echo "  always-on display (AOD):  $(A settings get secure doze_always_on)   <- 1=on (steady drain; ask before disabling)"
echo "  Wi-Fi scan always-on:     $(A settings get global wifi_scan_always_enabled)   <- 1=24/7 location scan (pure win to set 0)"
echo "  BLE scan always-on:       $(A settings get global ble_scan_always_enabled)   <- 1=24/7 location scan (pure win to set 0)"
echo "  stay awake while charging:$(A settings get global stay_on_while_plugged_in)   <- may be wanted ON for dev; ask"
echo "  cellular LTE RSRP (dBm):  $(A dumpsys telephony.registry | grep -oE 'rsrp=-?[0-9]+' | grep -v 2147483647 | head -1 | sed 's/rsrp=//')   <- < -110 = weak (suggest Wi-Fi calling)"
echo
echo "Done. Nothing was changed. See SKILL.md for how to act on this."
