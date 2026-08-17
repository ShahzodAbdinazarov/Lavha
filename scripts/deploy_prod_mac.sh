#!/usr/bin/env bash
# macOS twin of deploy_prod.sh. Ship a .web release APK to prod (svipe.uz).
# Every step is checked; nothing is replaced until the copy on the server is
# proven byte-identical. deploy_prod.sh is the Windows/Git-Bash original and hard-
# codes C:/ paths and apksigner.bat; this one autodetects the SDK on macOS and
# uses the BSD equivalents (stat -f %z, shasum -a 256).
#
# Usage:
#   ./scripts/deploy_prod_mac.sh [version_name] [version_code] ["<changelog ASCII only>"]
# The version is read from the APK; passing it is optional and only used as a cross-check
# that refuses to publish on a mismatch.
# Omit the changelog to keep the one already live. That is the correct mode for a
# same-version APK re-publish, where only the bytes change (so only SIZE + SHA256
# move) and the current changelog is legitimately non-ASCII Uzbek.
set -euo pipefail

VN="${1-}"; VC="${2-}"; CHANGELOG="${3-}"
HOST=root@169.58.191.228         # prod since the 2026-08-17 Contabo migration; dev is dev.svipe.uz on the same box
KEY=~/.ssh/lavha_deploy
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/TMessagesProj_App/build/outputs/apk/afat/standalone/app.apk"
# The signing certificate every existing install trusts. A different key means
# "app not installed" for all of them, so this is checked, not assumed.
WANT_CERT="C0:BB:87:FF:64:DD:96:33:A3:0E:8F:89:83:8B:68:17:0B:A1:93:50:6A:FB:E5:96:36:E9:E3:D8:51:FD:3D:2C"

rsh() { ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" "$@"; }

echo "== 0. version, read from the APK itself =="
# The version code is NOT taken on trust. An afat APK carries versionCode = code*10 + abi (779),
# while gradle.properties and the Play bundle say 77 — and the updater compares the server's number
# against the INSTALLED apk's own. Publishing 77 once meant every existing install was already
# "newer" than the release, so nobody would ever have been offered it. The artifact is the only
# thing that knows its real version, so it is the only thing asked.
AAPT=$(ls "$HOME"/Library/Android/sdk/build-tools/*/aapt2 2>/dev/null | tail -1)
[ -n "$AAPT" ] || { echo "!! no aapt2 in the Android SDK build-tools"; exit 1; }
# Read it all, then take the first line in the shell. Piping aapt2 into head closes the pipe early,
# and with `set -o pipefail` that SIGPIPE takes the whole script down with exit 141.
BADGING=$(printf '%s\n' "$("$AAPT" dump badging "$APK")" | sed -n '1p')
APK_VC=$(echo "$BADGING" | grep -oE "versionCode='[0-9]+'" | grep -oE "[0-9]+")
APK_VN=$(echo "$BADGING" | grep -oE "versionName='[^']+'" | sed "s/versionName='//; s/'//")
[ -n "$APK_VC" ] && [ -n "$APK_VN" ] || { echo "!! could not read the version out of $APK"; exit 1; }
echo "apk says: $APK_VN (vc $APK_VC)"
if [ -n "$VC" ] && [ "$VC" != "$APK_VC" ]; then
  echo "!! passed vc $VC but the APK is $APK_VC - refusing (a lower number is offered to nobody)"; exit 1
fi
if [ -n "$VN" ] && [ "$VN" != "$APK_VN" ]; then
  echo "!! passed $VN but the APK is $APK_VN - refusing"; exit 1
fi
VN="$APK_VN"; VC="$APK_VC"

echo "== 1. signature =="
APKSIGNER=$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
CERT=$("$APKSIGNER" verify --print-certs "$APK" | grep -i "SHA-256 digest" | head -1 \
  | grep -oiE "[0-9a-f]{64}" | sed 's/../&:/g; s/:$//' | tr 'a-f' 'A-F')
echo "cert: $CERT"
if [ "$CERT" != "$WANT_CERT" ]; then
  echo "!! WRONG SIGNING KEY - refusing to deploy (existing users would see an app conflict)"; exit 1
fi

echo "== 2. local metadata =="
SIZE=$(stat -f %z "$APK")
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
echo "size=$SIZE sha256=$SHA"

echo "== 3. backup current release =="
# The live apk is backed up under its outgoing version code plus an epoch suffix so a
# repeat deploy of the same version never overwrites an earlier backup.
rsh "cd /var/www/svipe && cp -f svipe.apk svipe.apk.bak-\$(grep -oP '(?<=^LAVHA_APK_VERSION_CODE=).*' /home/main/svipe-prod/.env)-\$(date +%s) 2>/dev/null || true; \
     cp -f /home/main/svipe-prod/.env /home/main/svipe-prod/.env.bak"

echo "== 4. upload =="
scp -i "$KEY" -o StrictHostKeyChecking=no "$APK" "$HOST:/var/www/svipe/svipe.apk.new"

echo "== 5. verify the copy on the server =="
REMOTE_SHA=$(rsh "sha256sum /var/www/svipe/svipe.apk.new | cut -d' ' -f1")
if [ "$REMOTE_SHA" != "$SHA" ]; then
  echo "!! upload corrupted ($REMOTE_SHA != $SHA)"; rsh "rm -f /var/www/svipe/svipe.apk.new"; exit 1
fi
rsh "mv -f /var/www/svipe/svipe.apk.new /var/www/svipe/svipe.apk"

echo "== 6. metadata =="
scp -i "$KEY" -o StrictHostKeyChecking=no "$ROOT/scripts/update_env.py" "$HOST:/tmp/"
if [ -n "$CHANGELOG" ]; then
  # The container decodes .env as cp1252, so any non-ASCII byte reaches the update
  # endpoint as mojibake. Caught here rather than in the user's update dialog.
  printf '%s' "$CHANGELOG" > /tmp/svipe_changelog.txt
  python3 -c "open('/tmp/svipe_changelog.txt',encoding='ascii').read()" || {
    echo "!! changelog contains non-ASCII"; exit 1
  }
  scp -i "$KEY" -o StrictHostKeyChecking=no /tmp/svipe_changelog.txt "$HOST:/tmp/"
  rsh "python3 /tmp/update_env.py /home/main/svipe-prod/.env /tmp/svipe_changelog.txt '$VN' '$VC' '$SIZE' '$SHA'"
else
  echo "(keeping the live changelog; updating VERSION_NAME/CODE/SIZE/SHA256 only)"
  rsh "python3 /tmp/update_env.py /home/main/svipe-prod/.env - '$VN' '$VC' '$SIZE' '$SHA'"
fi

echo "== 7. restart (up -d alone does not re-read .env) =="
rsh "cd /home/main/svipe-prod && docker compose up -d --force-recreate app" 2>&1 | tail -3

echo "== 8. verify =="
# Two defects this replaces, both seen for real on the 1.1.30 publish:
#
#  1. A blind `sleep 5` after --force-recreate. The app container needs tens of seconds, so every
#     check below hit nginx before uvicorn was listening and printed "error code: 502".
#  2. Worse: `curl -s` exits 0 on a 502, so the script printed the errors and still reported success.
#     A publish that never took effect was indistinguishable from one that did.
#
# So: poll until the endpoint actually serves the version we just published (that is the readiness
# gate AND the first assertion in one), then assert the rest and FAIL loudly on any mismatch.
UPDATE_URL="https://svipe.uz/api/app/update"
fail() { echo "!! $*"; exit 1; }

echo "-- waiting for the app to serve the new metadata --"
OFFER=""
for i in $(seq 1 40); do
  OFFER=$(curl -s --max-time 10 "$UPDATE_URL?version_code=1" || true)
  if printf '%s' "$OFFER" | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: sys.exit(1)
sys.exit(0 if d.get('version_code')==$VC else 1)
" 2>/dev/null; then
    echo "   ready after ${i}0s"
    break
  fi
  [ "$i" = 40 ] && fail "the endpoint never served version_code=$VC (last response: ${OFFER:0:200})"
  sleep 10
done

echo "-- offer to a very old client (expect available + the new version_code/sha) --"
printf '%s' "$OFFER" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(json.dumps(d, indent=1)[:600])
bad=[]
if not d.get('available'):            bad.append('available is false for an old client')
if d.get('version_code')!=$VC:        bad.append(f\"version_code {d.get('version_code')} != $VC\")
if d.get('version_name')!='$VN':      bad.append(f\"version_name {d.get('version_name')} != $VN\")
if d.get('sha256')!='$SHA':           bad.append('sha256 does not match the APK we just uploaded')
if d.get('size')!=$SIZE:              bad.append(f\"size {d.get('size')} != $SIZE\")
if bad: print('!! ' + '; '.join(bad)); sys.exit(1)
" || fail "the update offer does not describe the APK that was uploaded"

echo "-- offer to the current build (expect no update) --"
curl -s --max-time 10 "$UPDATE_URL?version_code=$VC" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(json.dumps(d))
sys.exit(1 if d.get('available') else 0)
" || fail "the app offers itself an update — every user would loop on the same version"

echo "-- served file --"
HEAD=$(curl -sI --max-time 20 https://svipe.uz/dl/svipe.apk)
printf '%s' "$HEAD" | grep -iE "^HTTP|content-length"
printf '%s' "$HEAD" | grep -qE "^HTTP/[0-9.]+ 200" || fail "the APK is not being served (see the status above)"
SERVED=$(printf '%s' "$HEAD" | grep -i "^content-length" | grep -oE "[0-9]+" | tail -1)
[ "$SERVED" = "$SIZE" ] || fail "served content-length $SERVED != the uploaded $SIZE"

echo "== published: $VN (vc $VC), verified end to end =="
