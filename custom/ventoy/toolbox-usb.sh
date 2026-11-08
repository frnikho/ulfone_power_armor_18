#!/system/bin/sh
# Notify the Toolbox app when a PC enumerates the phone (UDC state -> "configured").
# Short drops (Ventoy key toggle, USB function switch) are ignored: the PC must have been
# gone for at least UNPLUG_SECONDS before a new connection counts.
LOG=/data/ventoy/toolbox-usb.log
PKG=me.nico.toolbox
POLL_SECONDS=2
UNPLUG_SECONDS=4

PIDFILE=/dev/toolbox-usb.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDFILE"

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

udc_state() {
    cat /sys/class/udc/*/state 2>/dev/null | head -n1
}

send() {
    am broadcast -n "$PKG/.UsbConnectReceiver" -a "$PKG.$1" >/dev/null 2>&1 || log "broadcast $1 failed"
}

log "start pid $$"
# connected=1: PC present. unplugged=1: PC gone long enough for the next plug to count.
connected=0
unplugged=1
gone_since=$(date +%s)
if [ "$(udc_state)" = configured ]; then connected=1; unplugged=0; fi
while true; do
    now=$(date +%s)
    if [ "$(udc_state)" = configured ]; then
        if [ "$connected" = 0 ] && [ "$unplugged" = 1 ]; then
            log "PC connected"
            send PC_CONNECTED
        fi
        connected=1
        unplugged=0
    else
        if [ "$connected" = 1 ]; then
            connected=0
            gone_since=$now
        elif [ "$unplugged" = 0 ] && [ $((now - gone_since)) -ge "$UNPLUG_SECONDS" ]; then
            unplugged=1
            log "PC disconnected"
            send PC_DISCONNECTED
        fi
    fi
    sleep "$POLL_SECONDS"
done
