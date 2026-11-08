#!/system/bin/sh
# Expose /data/ventoy/ventoy.img as a USB mass-storage LUN next to adb.
# Usage: usbdisk.sh on|off|toggle|status
set -u

GADGET=/config/usb_gadget/g1
FUNC="$GADGET/functions/mass_storage.usb0"
LUN="$FUNC/lun.0"
LINK="$GADGET/configs/b.1/ventoy"
IMG=/data/ventoy/ventoy.img
LOG=/data/ventoy/usbdisk.log

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

udc_name() {
    local name
    name=$(getprop sys.usb.controller)
    [ -n "$name" ] || name=$(ls /sys/class/udc | head -n1)
    echo "$name"
}

is_on() { [ -L "$LINK" ] && [ -n "$(cat "$LUN/file" 2>/dev/null)" ]; }

do_on() {
    if is_on; then log "on: already on"; return 0; fi
    if [ ! -f "$IMG" ]; then log "on: missing $IMG"; echo "missing $IMG" >&2; return 1; fi
    # The kernel "file-storage" thread does the I/O: it cannot read system_data_root_file.
    chcon u:object_r:magisk_file:s0 "$IMG" 2>/dev/null
    local udc
    udc=$(udc_name)
    echo "" > "$GADGET/UDC"
    echo 1 > "$LUN/removable"
    echo 0 > "$LUN/cdrom"
    echo 0 > "$LUN/ro"
    echo 1 > "$LUN/nofua"
    echo "Ulefone PA18 Ventoy" > "$LUN/inquiry_string" 2>/dev/null
    if ! echo "$IMG" > "$LUN/file"; then
        log "on: cannot attach image"; echo "$udc" > "$GADGET/UDC"; return 1
    fi
    [ -L "$LINK" ] || ln -s "$FUNC" "$LINK"
    echo "$udc" > "$GADGET/UDC"
    log "on: attached $IMG via $udc"
}

do_off() {
    local udc
    udc=$(udc_name)
    echo "" > "$GADGET/UDC"
    [ -L "$LINK" ] && rm "$LINK"
    echo "" > "$LUN/file" 2>/dev/null
    sync
    echo "$udc" > "$GADGET/UDC"
    log "off: detached"
}

case "${1:-status}" in
    on)     do_on ;;
    off)    do_off ;;
    toggle) if is_on; then do_off; else do_on; fi ;;
    status) if is_on; then echo on; else echo off; fi ;;
    *)      echo "usage: $0 on|off|toggle|status" >&2; exit 2 ;;
esac
