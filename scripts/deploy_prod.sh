#!/usr/bin/env bash
# Ship a .web release APK to prod (svipe.uz). Every step is checked; nothing is
# replaced until the copy on the server is proven byte-identical.
#
# Usage: ./deploy_prod.sh [version_name] [version_code] ["<changelog ASCII only>"]
# The version is read from the APK; passing it is optional and only used as a cross-check
# that refuses to publish on a mismatch (see the == 0 == step).
set -euo pipefail

VN="${1-}"; VC="${2-}"; CHANGELOG="${3-}"
HOST=root@23.88.110.173           # prod since the 2026-06-29 migration; 49.12.47.209 is dev now
KEY=~/.ssh/lavha_deploy
APK="C:/Users/99897/AndroidStudioProjects/Telegram/TMessagesProj_App/build/outputs/apk/afat/standalone/app.apk"
# The signing certificate every existing install trusts. A different key means
# "app not installed" for all of them, so this is checked, not assumed.
WANT_CERT="C0:BB:87:FF:64:DD:96:33:A3:0E:8F:89:83:8B:68:17:0B:A1:93:50:6A:FB:E5:96:36:E9:E3:D8:51:FD:3D:2C"

export MSYS_NO_PATHCONV=1
sh() { ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" "$@"; }

echo "== 0. version, read from the APK itself =="
# Never taken on trust: an afat APK carries versionCode = code*10 + abi (779) while
# gradle.properties and the Play bundle say 77, and the updater compares the server's number
# against the INSTALLED apk's own. Publishing the smaller one puts the release beyond every
# existing install's reach — it is simply never offered. Mirrors deploy_prod_mac.sh.
AAPT=$(ls /c/Users/99897/AppData/Local/Android/Sdk/build-tools/*/aapt2.exe 2>/dev/null | tail -1)
[ -n "$AAPT" ] || { echo "!! no aapt2 in the Android SDK build-tools"; exit 1; }
# Read it all, then take the first line: piping aapt2 into head closes the pipe early and, under
# `set -o pipefail`, that SIGPIPE kills the script before it can say why.
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
APKSIGNER=$(ls /c/Users/99897/AppData/Local/Android/Sdk/build-tools/*/apksigner.bat 2>/dev/null | tail -1)
CERT=$("$APKSIGNER" verify --print-certs "$APK" | grep -i "SHA-256 digest" | head -1 | grep -oiE "[0-9a-f]{64}" \
  | sed 's/../&:/g; s/:$//' | tr 'a-f' 'A-F')
echo "cert: $CERT"
if [ "$CERT" != "$WANT_CERT" ]; then
  echo "!! WRONG SIGNING KEY - refusing to deploy (existing users would see an app conflict)"; exit 1
fi

echo "== 2. local metadata =="
SIZE=$(stat -c %s "$APK")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
echo "size=$SIZE sha256=$SHA"

echo "== 3. changelog must be pure ASCII =="
# The container decodes .env as cp1252, so any non-ASCII byte reaches the update
# endpoint as mojibake. Caught here rather than in the user's update dialog.
#
# Written with python, not `grep -P`: this Git Bash build refuses -P outside a
# unibyte/UTF-8 locale and exits 2, which an `if` reads as "no match found" — the
# check silently passed everything it was meant to stop.
printf '%s' "$CHANGELOG" > /tmp/svipe_changelog.txt
python -c "open('/tmp/svipe_changelog.txt',encoding='ascii').read()" || {
  echo "!! changelog contains non-ASCII"; exit 1
}

echo "== 4. backup current release =="
sh "cd /var/www/svipe && cp -f svipe.apk svipe.apk.bak-\$(grep -oP '(?<=^LAVHA_APK_VERSION_CODE=).*' /home/main/svipe-prod/.env) 2>/dev/null || true; \
    cp -f /home/main/svipe-prod/.env /home/main/svipe-prod/.env.bak"

echo "== 5. upload =="
scp -i "$KEY" -o StrictHostKeyChecking=no "$APK" "$HOST:/var/www/svipe/svipe.apk.new"

echo "== 6. verify the copy on the server =="
REMOTE_SHA=$(sh "sha256sum /var/www/svipe/svipe.apk.new | cut -d' ' -f1")
if [ "$REMOTE_SHA" != "$SHA" ]; then
  echo "!! upload corrupted ($REMOTE_SHA != $SHA)"; sh "rm -f /var/www/svipe/svipe.apk.new"; exit 1
fi
sh "mv -f /var/www/svipe/svipe.apk.new /var/www/svipe/svipe.apk"

echo "== 7. metadata =="
# Done by a script on the server reading the changelog from a file, not by sed with
# the text on the ssh command line: the changelog is Uzbek and full of apostrophes,
# which closed the remote shell's quoting early and left .env still advertising the
# previous release while the new APK was already being served. That mismatch breaks
# the updater outright, because it verifies the download against the published
# sha256.
scp -i "$KEY" -o StrictHostKeyChecking=no /tmp/svipe_changelog.txt "$(dirname "$0")/update_env.py" "$HOST:/tmp/"
sh "python3 /tmp/update_env.py /home/main/svipe-prod/.env /tmp/svipe_changelog.txt '$VN' '$VC' '$SIZE' '$SHA'"

echo "== 8. restart (up -d alone does not re-read .env) =="
sh "cd /home/main/svipe-prod && docker compose up -d --force-recreate app" 2>&1 | tail -3

echo "== 9. verify =="
# Kept in step with deploy_prod_mac.sh, which is where these two defects were found for real on the
# 1.1.30 publish: a blind `sleep 5` checked before the recreated container was listening (every line
# printed "error code: 502"), and `curl -s` exits 0 on a 502 — so the script reported success for a
# publish that had not taken effect. Poll until the endpoint serves the version we just published,
# then assert, then fail loudly.
UPDATE_URL="https://svipe.uz/api/app/update"
fail() { echo "!! $*"; exit 1; }

echo "-- waiting for the app to serve the new metadata --"
OFFER=""
for i in $(seq 1 40); do
  OFFER=$(curl -s --max-time 10 "$UPDATE_URL?version_code=1" || true)
  if printf '%s' "$OFFER" | python -c "
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
printf '%s' "$OFFER" | python -c "
import json,sys
d=json.load(sys.stdin)
print(json.dumps(d, indent=1)[:600])
bad=[]
if not d.get('available'):            bad.append('available is false for an old client')
if d.get('version_code')!=$VC:        bad.append('version_code mismatch')
if d.get('version_name')!='$VN':      bad.append('version_name mismatch')
if d.get('sha256')!='$SHA':           bad.append('sha256 does not match the APK we just uploaded')
if d.get('size')!=$SIZE:              bad.append('size mismatch')
if bad: print('!! ' + '; '.join(bad)); sys.exit(1)
" || fail "the update offer does not describe the APK that was uploaded"

echo "-- offer to the current build (expect no update) --"
curl -s --max-time 10 "$UPDATE_URL?version_code=$VC" | python -c "
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
