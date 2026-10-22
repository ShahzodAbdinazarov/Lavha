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
  # Nothing running: boot the Play emulator ourselves, headless, and shut it down afterwards. A skipped
  # prescan (1.3.12) means the first users get the prompt.
  AVD="${SVIPE_PLAY_AVD:-qazo_test_play}"
  EMU="$HOME/Library/Android/sdk/emulator/emulator"
  echo "· prescan: booting $AVD headless"
  before=$("$ADB" devices | awk 'NR>1 && /^emulator-/ {print $1}')
  "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save >/dev/null 2>&1 &
  started=1
  for _ in $(seq 1 90); do
    sleep 5
    for s in $("$ADB" devices | awk 'NR>1 && $2=="device" && /^emulator-/ {print $1}'); do
      echo "$before" | grep -qx "$s" && continue
      [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && serial=$s
    done
    [ -n "$serial" ] && break
  done
  if [ -z "$serial" ]; then
    echo "· prescan skipped: $AVD did not boot in 7.5 minutes"
    exit 0
  fi
  sleep 30   # let Play services settle before the install
fi
trap '[ "${started:-}" = 1 ] && "$ADB" -s "$serial" emu kill >/dev/null 2>&1' EXIT
a() { "$ADB" -s "$serial" "$@"; }
echo "· prescan on $serial"

# Verify adb installs too, and send unknown apps without asking (the "Improve harmful app detection"
# consent), so the install itself is the upload.
a shell settings put global verifier_verify_adb_installs 1
a shell settings put global package_verifier_user_consent 1
a shell settings put global upload_apk_enable 1
a shell pm trim-caches 4G >/dev/null 2>&1 || true

# A fresh install each time, as a new user's is; the previous release would only take up the space the
# update needs (a 6 GB emulator ran out at 1.3.14).
a uninstall uz.svipe.app.web >/dev/null 2>&1 || true
a logcat -c >/dev/null 2>&1 || true
out=$(a install "$APK" 2>&1 | tr -d '\r')
echo "  install: $(echo "$out" | grep -E "Success|Failure" | head -1)"
sleep 15
echo "  verifier:"
a logcat -d 2>/dev/null | grep -iE "PackageVerifier|VerifyApps|uploadApk|Play Protect|harmful" | grep -iE "uz.svipe|upload|verdict|result|scan" | tail -8 | sed 's/^/    /'
a uninstall uz.svipe.app.web >/dev/null 2>&1 || true
exit 0
