#!/usr/bin/env bash
# Restore the Ulefone stock firmware on slot a via the bootloader (LK) fastboot.
# Mirrors the factory scatter (slot a only); the preloader is deliberately NOT flashed.
# Requires: unlocked bootloader, phone in bootloader fastboot (not fastbootd).
# Usage: restore-stock.sh [firmware_dir]   (default: /lab/ulphone/stock/v12)
# WIPES ALL USER DATA.
set -euo pipefail

FW="${1:-/lab/ulphone/stock/v12}"
SLOT=a
SLOTTED=(vbmeta vbmeta_system vbmeta_vendor md1img spmfw audio_dsp pi_img dpm scp sspm mcupm
         cam_vpu1 cam_vpu2 cam_vpu3 gz lk boot dtbo tee)

[ -f "$FW/MT6877_Android_scatter.txt" ] || { echo "No scatter in $FW" >&2; exit 1; }
for p in "${SLOTTED[@]}" super; do [ -f "$FW/$p.img" ] || { echo "Missing $p.img" >&2; exit 1; }; done
fastboot devices | grep -q fastboot || { echo "Phone not in fastboot" >&2; exit 1; }
[ "$(fastboot getvar is-userspace 2>&1 | awk '/is-userspace/ {print $2}')" = "no" ] \
    || { echo "Phone is in fastbootd; reboot to the bootloader (fastboot reboot bootloader)" >&2; exit 1; }

for p in "${SLOTTED[@]}"; do
    fastboot flash "${p}_${SLOT}" "$FW/$p.img"
done
fastboot flash logo "$FW/logo.bin"
fastboot flash super "$FW/super.img"
fastboot erase metadata
fastboot flash userdata "$FW/userdata.img"   # factory f2fs template, as SP Flash Tool does
fastboot set_active "$SLOT"
echo "Done. Active slot: $(fastboot getvar current-slot 2>&1 | awk '/current-slot/ {print $2}')"
echo "Reboot with: fastboot reboot"
