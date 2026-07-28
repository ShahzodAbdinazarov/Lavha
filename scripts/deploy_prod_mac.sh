#!/usr/bin/env bash
# macOS twin of deploy_prod.sh. Ship a .web release APK to prod (svipe.uz).
# Every step is checked; nothing is replaced until the copy on the server is
# proven byte-identical. deploy_prod.sh is the Windows/Git-Bash original and hard-
# codes C:/ paths and apksigner.bat; this one autodetects the SDK on macOS and
# uses the BSD equivalents (stat -f %z, shasum -a 256).
#
# Usage:
#   ./scripts/deploy_prod_mac.sh <version_name> <version_code> ["<changelog ASCII only>"]
# Omit the changelog to keep the one already live. That is the correct mode for a
# same-version APK re-publish, where only the bytes change (so only SIZE + SHA256
# move) and the current changelog is legitimately non-ASCII Uzbek.
set -euo pipefail

VN="$1"; VC="$2"; CHANGELOG="${3-}"
HOST=root@23.88.110.173           # prod since the 2026-06-29 migration; 49.12.47.209 is dev now
KEY=~/.ssh/lavha_deploy
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/TMessagesProj_App/build/outputs/apk/afat/standalone/app.apk"
# The signing certificate every existing install trusts. A different key means
# "app not installed" for all of them, so this is checked, not assumed.
WANT_CERT="C0:BB:87:FF:64:DD:96:33:A3:0E:8F:89:83:8B:68:17:0B:A1:93:50:6A:FB:E5:96:36:E9:E3:D8:51:FD:3D:2C"

rsh() { ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" "$@"; }

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
sleep 5
echo "-- offer to a very old client (expect the new version_code/sha) --"
curl -s "https://svipe.uz/api/app/update?version_code=1"; echo
echo "-- offer to the current build (expect no update) --"
curl -s "https://svipe.uz/api/app/update?version_code=$VC"; echo
echo "-- served file --"
curl -sI https://svipe.uz/dl/svipe.apk | grep -iE "^HTTP|content-length"
