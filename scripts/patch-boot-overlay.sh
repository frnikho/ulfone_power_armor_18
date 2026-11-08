#!/usr/bin/env bash
# Add the boot-log overlay (custom/magisk-overlay) to a Magisk-patched boot image, on the phone (needs root),
# then write it to boot_<slot>. Output copy: stock/v12/boot-magisk-bootlog.img
# Usage: patch-boot-overlay.sh [magisk_boot.img] [slot]
set -euo pipefail

SRC="${1:-/lab/ulphone/stock/v12/boot-magisk.img}"
SLOT="${2:-a}"
OVL=/lab/ulphone/custom/magisk-overlay
MAGISKBOOT=/lab/ulphone/apks/magisk/ex/lib/arm64-v8a/libmagiskboot.so
OUT=/lab/ulphone/stock/v12/boot-magisk-bootlog.img
T=/data/local/tmp/mgo

adb shell "rm -rf $T; mkdir -p $T"
adb push "$MAGISKBOOT" "$T/magiskboot" >/dev/null
adb push "$SRC" "$T/boot.img" >/dev/null
adb push "$OVL/nico-bootlog.rc" "$OVL/nico-bootlog.sh" "$T/" >/dev/null
adb shell "su -c 'cd $T && chmod 755 magiskboot && ./magiskboot unpack boot.img >/dev/null 2>&1 \
  && ./magiskboot cpio ramdisk.cpio \"mkdir 0750 overlay.d\" \"mkdir 0750 overlay.d/sbin\" \
     \"add 0644 overlay.d/nico-bootlog.rc nico-bootlog.rc\" \"add 0755 overlay.d/sbin/nico-bootlog.sh nico-bootlog.sh\" \
  && ./magiskboot repack boot.img new.img >/dev/null 2>&1 \
  && dd if=new.img of=/dev/block/by-name/boot_$SLOT bs=4M && sync'"
adb pull "$T/new.img" "$OUT" >/dev/null
adb shell "rm -rf $T"
echo "boot_$SLOT updated ($OUT)"
