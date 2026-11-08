#!/usr/bin/env bash
# Install a system image as a single-boot DSU (Dynamic System Update) from rooted stock, then reboot into it.
# The next reboot (or the bootlog watchdog after 300 s) returns to stock. Stock is never modified.
# Usage: test-dsu.sh <system.img>
# Back to stock from a DSU boot: `adb shell am start-foreground-service -n
#   com.android.dynsystem/.DynamicSystemInstallationService -a com.android.dynsystem.ACTION_REBOOT_TO_NORMAL`
# or, if stuck: fastboot mode -> `fastboot reboot fastboot` -> `fastboot gsi disable` -> `fastboot reboot`.
set -euo pipefail

IMG="${1:?Usage: $0 <system.img>}"
[ -f "$IMG" ] || { echo "No such image: $IMG" >&2; exit 1; }
adb get-state >/dev/null || { echo "Phone not on adb" >&2; exit 1; }
[ "$(adb shell getprop ro.gsid.image_running | tr -d '\r')" != "1" ] || { echo "Already in a DSU boot; go back to stock first" >&2; exit 1; }

size=$(stat -c %s "$IMG")
gz="${IMG%.img}.raw.gz"
[ "$gz" -nt "$IMG" ] || gzip -1 -c "$IMG" > "$gz"

adb shell 'su -c "gsi_tool wipe"' 2>&1 | grep -v ServiceManager || true
adb shell rm -f /sdcard/Download/system.raw.gz
adb push "$gz" /sdcard/Download/system.raw.gz
adb shell "am start-activity -n com.android.dynsystem/com.android.dynsystem.VerificationActivity \
  -a android.os.image.action.START_INSTALL -d file:///storage/emulated/0/Download/system.raw.gz \
  --el KEY_SYSTEM_SIZE $size --el KEY_USERDATA_SIZE 8589934592" >/dev/null

for _ in $(seq 1 120); do
    sleep 5
    [ "$(adb shell 'su -c "gsi_tool status"' 2>/dev/null | head -1 | tr -d '\r')" = "installed" ] && break
done
[ "$(adb shell 'su -c "gsi_tool status"' 2>/dev/null | head -1 | tr -d '\r')" = "installed" ] \
    || { echo "DSU install did not finish" >&2; exit 1; }

adb shell 'su -c "gsi_tool enable --single-boot"' 2>&1 | grep -v ServiceManager
adb reboot
echo "Rebooting into the DSU image (single boot). Logs of every boot: /metadata/nico/<n>/ (read from stock as root)."
