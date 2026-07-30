#!/bin/bash
# Reshape the running emulator's logical screen, for the tests that only fail on a particular size.
#
# Why this exists: at targetSdk 36 Android IGNORES setRequestedOrientation on any display whose
# smallest width is >= 600dp (see the comment in TMessagesProj/src/main/AndroidManifest.xml). The video
# player's fullscreen therefore MUST work without the orientation request being honoured, and that path
# is unreachable on a normal phone emulator. Lowering density turns the same AVD into a >=600dp display
# in one command — far cheaper than maintaining a tablet AVD, and reversible.
#
#   scripts/emu_screen.sh info      # current size / density / smallest-width dp
#   scripts/emu_screen.sh large     # force smallest width >= 600dp (orientation request gets ignored)
#   scripts/emu_screen.sh phone     # back to the AVD's own values
#
# NOTE: the orientation-ignoring behaviour needs an API 36 image. Use the `svipe_a16` AVD for the real
# check; on API 35 this only exercises the LAYOUT half (does fullscreen still look right at 600dp+).
set -eu
ADB="${ADB:-/Users/mymac/Library/Android/sdk/platform-tools/adb}"
TARGET_SW_DP=600

size() { $ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1; }
dens() { $ADB shell wm density | sed -n 's/.*: \([0-9]*\).*/\1/p' | tail -1; }

report() {
  read -r w h <<<"$(size)"
  d=$(dens)
  sw=$(( (w < h ? w : h) * 160 / d ))
  api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
  printf 'size=%sx%s density=%s smallestWidth=%sdp api=%s -> %s\n' \
    "$w" "$h" "$d" "$sw" "$api" \
    "$([ "$sw" -ge "$TARGET_SW_DP" ] && echo 'LARGE (orientation request ignored at targetSdk 36)' || echo 'phone')"
}

case "${1:-info}" in
  info) report ;;
  large)
    read -r w h <<<"$(size)"
    short=$(( w < h ? w : h ))
    # density that puts the short edge exactly at the target dp, rounded down to a sane step
    d=$(( short * 160 / TARGET_SW_DP ))
    d=$(( d / 20 * 20 ))
    [ "$d" -lt 120 ] && d=120
    echo "setting density to $d (short edge ${short}px -> $(( short * 160 / d ))dp)"
    $ADB shell wm density "$d"
    sleep 2 && report
    ;;
  phone)
    $ADB shell wm density reset
    $ADB shell wm size reset
    sleep 2 && report
    ;;
  *) echo "usage: $0 {info|large|phone}" >&2; exit 2 ;;
esac
