#!/usr/bin/env bash
# Verify a Play AAB against CLAUDE.md rule 3 by reading the BUILT ARTIFACT, never the source.
#
#   scripts/verify-play-aab.sh [path/to/app.aab]
#
# Defaults to the bundleBundleAfatRelease output. Checks the merged manifest inside the bundle for:
#   1. no READ_CALL_LOG / no <uses-permission CALL_PHONE>  (Play rejects the afat variant, which has both)
#   2. targetSdkVersion >= 36
#   3. the applicationId is uz.svipe.app
# Downloads bundletool on first run; aapt2 cannot read an .aab and there is no system copy.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAB="${1:-$REPO/TMessagesProj_App/build/outputs/bundle/bundleAfatRelease/TMessagesProj_App-bundleAfat-release.aab}"
BT_VERSION=1.18.1
BT="$HOME/.gradle/tools/bundletool-all-$BT_VERSION.jar"

[ -f "$AAB" ] || { echo "FAIL: no AAB at $AAB — run ./gradlew :TMessagesProj_App:bundleBundleAfatRelease"; exit 1; }

if [ ! -f "$BT" ]; then
  echo "· fetching bundletool $BT_VERSION"
  mkdir -p "$(dirname "$BT")"
  curl -fsSL -o "$BT.tmp" \
    "https://github.com/google/bundletool/releases/download/$BT_VERSION/bundletool-all-$BT_VERSION.jar"
  mv "$BT.tmp" "$BT"
fi

# Use the JDK the build itself runs on, not whatever JAVA_HOME points at (it may be an IDE JBR that
# is too new — that is exactly what broke this build once).
JAVA=java
DAEMON_JDK="$(awk -F= '/^toolchainVersion=/{print $2}' "$REPO/gradle/gradle-daemon-jvm.properties" 2>/dev/null || true)"
if [ -n "${DAEMON_JDK:-}" ]; then
  CAND="$(ls -d "$HOME"/.gradle/jdks/*/jdk-"$DAEMON_JDK"*/Contents/Home 2>/dev/null | tail -1 || true)"
  [ -n "$CAND" ] && JAVA="$CAND/bin/java"
fi

MF="$("$JAVA" -jar "$BT" dump manifest --bundle "$AAB")"

fail=0
check() { # name  pattern  expect-absent|expect-present
  if [ "$3" = absent ]; then
    if grep -qE "$2" <<<"$MF"; then echo "  ✗ $1 — PRESENT (Play will reject)"; fail=1
    else echo "  ✓ $1 — absent"; fi
  else
    if grep -qE "$2" <<<"$MF"; then echo "  ✓ $1"; else echo "  ✗ $1 — MISSING"; fail=1; fi
  fi
}

echo "AAB: $AAB"
echo "     $(du -h "$AAB" | cut -f1), built $(date -r "$AAB" '+%Y-%m-%d %H:%M')"
check "READ_CALL_LOG"                 'uses-permission[^>]*READ_CALL_LOG' absent
check "uses-permission CALL_PHONE"    'uses-permission[^>]*CALL_PHONE'    absent
check "package uz.svipe.app"          'package="uz\.svipe\.app"'          present

TSDK="$(grep -oE 'targetSdkVersion="[0-9]+"' <<<"$MF" | grep -oE '[0-9]+' | head -1)"
if [ -n "$TSDK" ] && [ "$TSDK" -ge 36 ]; then echo "  ✓ targetSdkVersion=$TSDK (>= 36)"
else echo "  ✗ targetSdkVersion=${TSDK:-unknown} (< 36)"; fail=1; fi

VC="$(grep -oE 'versionCode="[0-9]+"' <<<"$MF" | grep -oE '[0-9]+' | head -1)"
echo "  · versionCode=$VC  versionName=$(grep -oE 'versionName="[^"]+"' <<<"$MF" | head -1 | cut -d'"' -f2)"
# The Play variant stamps versionCode as-is; the forbidden afat variant stamps versionCode*10+9.
if [ -n "$VC" ] && [ "$((VC % 10))" -eq 9 ] && [ "$VC" -gt 99 ]; then
  echo "  ✗ versionCode ends in 9 and is large — this smells like the afat variant, not bundleBundleAfat"; fail=1
fi

# --- 4. every permission the bundle asks for must be disclosed on the published privacy policy ---
# Play's scanner rejected 1.1.43 with "LOCATION data is accessed by the app but not disclosed" —
# it reads this exact permission list and the page at PRIVACY_URL. So does this check, which fails
# the release instead of the review. A permission in neither table below is an unreviewed one: put
# it in the policy (and here), or in NO_DATA if it reaches no user data at all.
PRIVACY_URL="${PRIVACY_URL:-https://svipe.uz/privacy}"

# permission-regex -> a phrase that must appear on the policy page for it
COVER=(
  'ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION|FOREGROUND_SERVICE_LOCATION§<h2>7. Location</h2>'
  'ACCESS_MEDIA_LOCATION§coordinates of where it was taken'
  'CAMERA§<strong>Camera</strong>'
  'RECORD_AUDIO|FOREGROUND_SERVICE_MICROPHONE§<strong>Microphone</strong>'
  'READ_MEDIA_(IMAGES|VIDEO|AUDIO)|(READ|WRITE)_EXTERNAL_STORAGE§Photos, videos, music and files'
  'FOREGROUND_SERVICE_MEDIA_PROJECTION§<strong>Screen sharing</strong>'
  '(READ|WRITE)_CONTACTS|GET_ACCOUNTS|(AUTHENTICATE|MANAGE)_ACCOUNTS|READ_PROFILE|(READ|WRITE)_SYNC_SETTINGS§<h2>9. Contacts</h2>'
  'MANAGE_OWN_CALLS|READ_PHONE_(STATE|NUMBERS)§<h2>10. Calls</h2>'
  'vending\.BILLING§<h2>11. Payments</h2>'
  'USE_BIOMETRIC|USE_FINGERPRINT|READ_CLIPBOARD§<h2>12. What stays on your device</h2>'
  'POST_NOTIFICATIONS|c2dm\.permission\.RECEIVE§push notification token'
)
# reaches no user data: plumbing, launcher badges, wake/vibrate, install/update, window flags
NO_DATA='INTERNET|ACCESS_(NETWORK|WIFI)_STATE|VIBRATE|WAKE_LOCK|FOREGROUND_SERVICE$|FOREGROUND_SERVICE_(DATA_SYNC|MEDIA_PLAYBACK|CAMERA)|RECEIVE_BOOT_COMPLETED|SCHEDULE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW|INSTALL_SHORTCUT|UNINSTALL_SHORTCUT|REQUEST_INSTALL_PACKAGES|BLUETOOTH|BLUETOOTH_CONNECT|MODIFY_AUDIO_SETTINGS|READ_APP_BADGE|[Bb][Aa][Dd][Gg][Ee]|UPDATE_COUNT|UPDATE_SHORTCUT|READ_SETTINGS|WRITE_SETTINGS|CHANGE_BADGE|PROVIDER_INSERT_BADGE|BROADCAST_BADGE|READ_GSERVICES|MAPS_RECEIVE|DYNAMIC_RECEIVER_NOT_EXPORTED'

echo "policy: $PRIVACY_URL"
if POLICY="$(curl -fsS --max-time 20 "$PRIVACY_URL")"; then
  PERMS="$(grep -oE 'uses-permission[^>]*android:name="[^"]+"' <<<"$MF" | grep -oE '"[^"]+"$' | tr -d '"' | sort -u)"
  for perm in $PERMS; do
    matched=0
    for row in "${COVER[@]}"; do
      pat="${row%%§*}"; phrase="${row#*§}"
      grep -qE "$pat" <<<"$perm" || continue
      matched=1
      if ! grep -qF "$phrase" <<<"$POLICY"; then
        echo "  ✗ $perm — policy does not disclose it (missing: \"$phrase\")"; fail=1
      fi
      break
    done
    if [ "$matched" -eq 0 ] && ! grep -qE "$NO_DATA" <<<"$perm"; then
      echo "  ✗ $perm — NEW permission, never reviewed. Disclose it in app/privacy.py (svipe-backend)"
      echo "      and add it to COVER here, or to NO_DATA if it touches no user data."; fail=1
    fi
  done
  [ "$fail" -eq 0 ] && echo "  ✓ all $(wc -w <<<"$PERMS" | tr -d ' ') permissions covered by the published policy"
else
  echo "  ✗ could not fetch $PRIVACY_URL — cannot prove the policy covers this build"; fail=1
fi

[ "$fail" -eq 0 ] && echo "PASS — safe to upload to Play" || { echo "FAIL"; exit 1; }
