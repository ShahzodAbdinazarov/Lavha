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

[ "$fail" -eq 0 ] && echo "PASS — safe to upload to Play" || { echo "FAIL"; exit 1; }
