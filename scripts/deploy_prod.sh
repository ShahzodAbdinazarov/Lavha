#!/usr/bin/env bash
# Ship a .web release APK to prod (svipe.uz). Every step is checked; nothing is
# replaced until the copy on the server is proven byte-identical.
#
# Usage: ./deploy_prod.sh <version_name> <version_code> "<changelog ASCII only>"
set -euo pipefail

VN="$1"; VC="$2"; CHANGELOG="$3"
HOST=root@23.88.110.173           # prod since the 2026-06-29 migration; 49.12.47.209 is dev now
KEY=~/.ssh/lavha_deploy
APK="C:/Users/99897/AndroidStudioProjects/Telegram/TMessagesProj_App/build/outputs/apk/afat/standalone/app.apk"
# The signing certificate every existing install trusts. A different key means
# "app not installed" for all of them, so this is checked, not assumed.
WANT_CERT="C0:BB:87:FF:64:DD:96:33:A3:0E:8F:89:83:8B:68:17:0B:A1:93:50:6A:FB:E5:96:36:E9:E3:D8:51:FD:3D:2C"

export MSYS_NO_PATHCONV=1
sh() { ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" "$@"; }

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
sleep 5
curl -s "https://svipe.uz/api/app/update?version_code=1"; echo
curl -sI https://svipe.uz/dl/svipe.apk | grep -iE "^HTTP|content-length"
