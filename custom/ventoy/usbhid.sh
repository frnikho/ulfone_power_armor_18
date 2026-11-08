#!/system/bin/sh
# Expose a USB HID boot keyboard + boot mouse next to adb (and Ventoy if enabled).
# Needs the kernel patch that drops MediaTek's f_hid HACK (see memory kernel-hid-patch,
# /lab/ulphone/stock/backup-boot-hid-20261106), otherwise the host only sees a "None" device.
# Usage: usbhid.sh on|off|status|node|mousenode
set -u

GADGET=/config/usb_gadget/g1
KBD="$GADGET/functions/hid.gs0"
MOUSE="$GADGET/functions/hid.mouse"
KBD_LINK="$GADGET/configs/b.1/keyboard"
MOUSE_LINK="$GADGET/configs/b.1/mouse"
LOG=/data/ventoy/usbhid.log

# Boot keyboard: 8-byte input report [mods, 0, k1..k6], 1-byte LED output.
KBD_DESC='\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x03\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x03\x95\x06\x75\x08\x15\x00\x25\x65\x05\x07\x19\x00\x29\x65\x81\x00\xc0'
# Boot-compatible mouse: 4-byte report [buttons(5 bits), dx, dy, wheel], relative int8.
MOUSE_DESC='\x05\x01\x09\x02\xa1\x01\x09\x01\xa1\x00\x05\x09\x19\x01\x29\x05\x15\x00\x25\x01\x95\x05\x75\x01\x81\x02\x95\x01\x75\x03\x81\x01\x05\x01\x09\x30\x09\x31\x09\x38\x15\x81\x25\x7f\x75\x08\x95\x03\x81\x06\xc0\xc0'

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

udc_name() {
    local name
    name=$(getprop sys.usb.controller)
    [ -n "$name" ] || name=$(ls /sys/class/udc | head -n1)
    echo "$name"
}

node_of() { echo "/dev/hidg$(cut -d: -f2 "$1/dev" 2>/dev/null)"; }

is_on() {
    [ -L "$KBD_LINK" ] && [ -L "$MOUSE_LINK" ] \
        && [ -c "$(node_of "$KBD")" ] && [ -c "$(node_of "$MOUSE")" ]
}

configure() {
    # $1 = function dir, $2 = protocol, $3 = report length, $4 = descriptor
    [ -d "$1" ] || mkdir "$1"
    echo 1 > "$1/subclass"
    echo "$2" > "$1/protocol"
    echo "$3" > "$1/report_length"
    printf "$4" > "$1/report_desc"
}

do_on() {
    if is_on; then return 0; fi
    local udc i=0
    udc=$(udc_name)
    echo "" > "$GADGET/UDC"
    configure "$KBD" 1 8 "$KBD_DESC"
    configure "$MOUSE" 2 4 "$MOUSE_DESC"
    [ -L "$KBD_LINK" ] || ln -s "$KBD" "$KBD_LINK"
    [ -L "$MOUSE_LINK" ] || ln -s "$MOUSE" "$MOUSE_LINK"
    echo "$udc" > "$GADGET/UDC"
    while ! is_on && [ $i -lt 20 ]; do sleep 0.1; i=$((i + 1)); done
    if is_on; then
        log "on via $udc: kbd $(node_of "$KBD") mouse $(node_of "$MOUSE")"
    else
        log "on: hid nodes missing"; return 1
    fi
}

do_off() {
    local udc
    udc=$(udc_name)
    echo "" > "$GADGET/UDC"
    [ -L "$KBD_LINK" ] && rm "$KBD_LINK"
    [ -L "$MOUSE_LINK" ] && rm "$MOUSE_LINK"
    echo "$udc" > "$GADGET/UDC"
    log "off"
}

case "${1:-status}" in
    on)        do_on ;;
    off)       do_off ;;
    status)    if is_on; then echo on; else echo off; fi ;;
    node)      node_of "$KBD" ;;
    mousenode) node_of "$MOUSE" ;;
    *)         echo "usage: $0 on|off|status|node|mousenode" >&2; exit 2 ;;
esac
