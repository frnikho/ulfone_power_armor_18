#!/usr/bin/env bash
# Flash the customized LineageOS 24 GSI to slot a of the Power Armor 18 for good. WIPES ALL USER DATA.
# Run with the phone booted on (rooted) stock with adb, or already in bootloader fastboot.
# Keeps boot_a (Magisk + bootlog overlay), vendor, firmware. Back to stock: scripts/restore-stock.sh.
# Usage: flash-a17.sh <system.img>
set -euo pipefail

# DISABLED 2026-11-02: on this phone a flashed GSI cannot pass AVB. A disabled top-level vbmeta breaks the TEE root of
# trust (/data won't mount), and vbmeta_system is signed by a private Ulefone key (a patched copy -> dm-verity corruption).
# Both looped even with the stock system. Use DSU instead (scripts/test-dsu.sh, or gsi_tool enable without --single-boot).
echo "flash-a17.sh is disabled: flashing a GSI bootloops this phone. Use DSU." >&2
exit 1

IMG="${1:?Usage: $0 <system.img>}"
VBMETA=/lab/ulphone/stock/v12/vbmeta.img
VBMETA_SYSTEM=/lab/ulphone/custom/vbmeta/vbmeta_system-noverity.img
SLOT=a
[ -f "$IMG" ] && [ -f "$VBMETA" ] || { echo "Missing $IMG or $VBMETA" >&2; exit 1; }

read -rp "Flash $(basename "$IMG") to system_$SLOT and WIPE the phone? [oui/N] " answer
[ "$answer" = "oui" ] || exit 1

wait_fastboot() {   # $1 = yes (fastbootd) | no (bootloader)
    for _ in $(seq 1 60); do
        [ "$(fastboot getvar is-userspace 2>&1 | awk '/is-userspace/ {print $2}')" = "$1" ] && return 0
        sleep 2
    done
    echo "fastboot (userspace=$1) not reached" >&2; exit 1
}

if adb get-state >/dev/null 2>&1; then adb reboot bootloader; fi
wait_fastboot no
fastboot set_active "$SLOT"
# Keep the STOCK top-level vbmeta: with verification disabled there (flags 2/3) LK hands the TrustKernel TEE no root
# of trust, keymaster fails, /data metadata encryption fails and init reboots to "Can't load Android system".
# Only vbmeta_system (stock copy, flags=3) skips verifying the GSI system; the Magisk boot is allowed on unlocked.
fastboot flash "vbmeta_$SLOT" "$VBMETA"
fastboot flash "vbmeta_system_$SLOT" "$VBMETA_SYSTEM"

fastboot reboot fastboot
wait_fastboot yes
fastboot set_active "$SLOT"
# Keep stock product_a: vendor fstab mounts it at first stage, and the DSU tests that worked ran with it
fastboot flash "system_$SLOT" "$IMG"

# fastbootd can't see userdata on this phone: wipe from the bootloader instead
fastboot reboot bootloader
wait_fastboot no
fastboot erase metadata
fastboot flash userdata /lab/ulphone/stock/v12/userdata.img
fastboot reboot
echo "Flashed. First boot can take several minutes."
