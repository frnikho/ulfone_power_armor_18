#!/usr/bin/env bash
# Upgrade slot a firmware + vendor + kernel from V12 to the official V16 (20241107) while KEEPING the A17 GSI
# (system_a) and /data. Does NOT touch the preloader (already V16 on the phone) and does NOT wipe anything.
#
# Inputs (prepared beforehand):
#   stock/v16/                      official V16 ROM (scatter images)
#   out/vcmp/v16/vendor_a.img       vendor_a extracted from the V16 super (lpunpack)
#   stock/v16/boot-magisk-v16.img   V16 boot + Magisk + bootlog overlay + fstab without system AVB + /adb_keys
#   out/system_a-v16-spl.img        current GSI with security_patch 2023-11-05 -> 2024-09-05 (TEE identity)
# Rollback: stock/backup-20261104-a17/partitions (raw slot a partitions + full super).
# Usage: upgrade-v16-keep-gsi.sh   (phone booted with adb, or already in bootloader fastboot)
set -euo pipefail

ROOT=/lab/ulphone
FW=$ROOT/stock/v16
VENDOR=$ROOT/out/vcmp/v16/vendor_a.img
BOOT=$FW/boot-magisk-v16.img
SYSTEM=$ROOT/out/system_a-v16-spl.img
SLOT=a
# Stock V16 vbmeta set is flashed unmodified: never modify vbmeta/vbmeta_system on this phone (TEE root of trust).
FIRMWARE=(vbmeta vbmeta_system vbmeta_vendor md1img spmfw audio_dsp pi_img dpm scp sspm mcupm
          cam_vpu1 cam_vpu2 cam_vpu3 gz lk dtbo tee)

for f in "$VENDOR" "$BOOT" "$SYSTEM"; do [ -f "$f" ] || { echo "Missing $f" >&2; exit 1; }; done
for p in "${FIRMWARE[@]}"; do [ -f "$FW/$p.img" ] || { echo "Missing $FW/$p.img" >&2; exit 1; }; done

wait_fastboot() {   # $1 = yes (fastbootd) | no (bootloader)
    for _ in $(seq 1 60); do
        [ "$(fastboot getvar is-userspace 2>&1 | awk '/is-userspace/ {print $2}')" = "$1" ] && return 0
        sleep 2
    done
    echo "fastboot (userspace=$1) not reached" >&2; exit 1
}

read -rp "Flash V16 firmware/vendor/boot + patched system to slot $SLOT (no wipe)? [oui/N] " answer
[ "$answer" = "oui" ] || exit 1

if adb get-state >/dev/null 2>&1; then adb reboot bootloader; fi
wait_fastboot no

# 1. Bootloader (LK) fastboot: physical slot-a partitions
for p in "${FIRMWARE[@]}"; do
    fastboot flash "${p}_${SLOT}" "$FW/$p.img"
done
fastboot flash "boot_${SLOT}" "$BOOT"

# 2. fastbootd: logical partitions in super (LK can't resize them)
fastboot reboot fastboot
wait_fastboot yes
fastboot flash "vendor_${SLOT}" "$VENDOR"
fastboot flash "system_${SLOT}" "$SYSTEM"

# 3. Keep slot a active (set_active from fastbootd is what sticks on this phone; LK's doesn't)
fastboot set_active "$SLOT"
fastboot reboot
echo "Flashed. First boot may take a few minutes (keymaster key upgrade, dex re-optimization)."
