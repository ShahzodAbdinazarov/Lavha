#!/usr/bin/env bash
# Let Google Play Protect see a .web release before any user installs it.
#
#   scripts/play_protect_prescan.sh [path/to/app.apk]
#
# Play Protect asks "send this app for scanning?" for an APK it has never seen, and every .web release
# is a file it has never seen: few people install it, and the first ones get the prompt. Telegram's
# direct APK is seen by millions within hours, so nobody does. This installs the release, through the
# package verifier, on an emulator that has the real Play Store and consented to sending unknown
# apps, so Google has scanned the file before it is published.
#
# Best-effort by design: it reports what the verifier said and never stops a release. Pick the
# emulator with SVIPE_PLAY_EMULATOR=<serial>; otherwise the first device with the real Play Store
# (the stub on google_apis images lives in /product/app) is used.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/TMessagesProj_App/build/outputs/apk/afat/standalone/app.apk}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
[ -x "$ADB" ] || ADB=adb

serial="${SVIPE_PLAY_EMULATOR:-}"
if [ -z "$serial" ]; then
  for s in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
    case "$s" in emulator-*) ;; *) continue ;; esac   # never a real phone: it is somebody's
    path=$("$ADB" -s "$s" shell pm path com.android.vending 2>/dev/null | tr -d '\r')
    case "$path" in *"/data/app/"*) serial=$s; break ;; esac
  done
fi
if [ -z "$serial" ]; then
  echo "· prescan skipped: no running emulator with the real Play Store (start one, e.g. qazo_test_play)"
  exit 0
fi
a() { "$ADB" -s "$serial" "$@"; }
echo "· prescan on $serial"

# Verify adb installs too, and send unknown apps without asking (the "Improve harmful app detection"
# consent), so the install itself is the upload.
a shell settings put global verifier_verify_adb_installs 1
a shell settings put global package_verifier_user_consent 1
a shell settings put global upload_apk_enable 1
a shell pm trim-caches 4G >/dev/null 2>&1 || true

a logcat -c >/dev/null 2>&1 || true
out=$(a install -r "$APK" 2>&1 | tr -d '\r')
echo "  install: $(echo "$out" | grep -E "Success|Failure" | head -1)"
sleep 15
echo "  verifier:"
a logcat -d 2>/dev/null | grep -iE "PackageVerifier|VerifyApps|uploadApk|Play Protect|harmful" | grep -iE "uz.svipe|upload|verdict|result|scan" | tail -8 | sed 's/^/    /'
exit 0
