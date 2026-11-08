#!/system/bin/sh
# Left key (KEY_F1 on mtk-kpd) handler. Replaces Key Mapper.
#   short press (< LONG_PRESS s) -> open the Toolbox panel (wakes the screen)
#   long press                   -> toggle the torch (screen stays off), short vibration
# Logs to /data/ventoy/toolbox-key.log.
LOG=/data/ventoy/toolbox-key.log
PKG=me.nico.toolbox
LONG_PRESS=0.45
STATE=/dev/toolbox-key.state
WAKE_LOCK=toolbox_key

# Single instance.
PIDFILE=/dev/toolbox-key.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDFILE"

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

find_device() {
    for d in /sys/class/input/event*; do
        if [ "$(cat "$d/device/name" 2>/dev/null)" = "mtk-kpd" ]; then
            echo "/dev/input/${d##*/}"; return 0
        fi
    done
    return 1
}

open_panel() {
    if ! dumpsys power | grep -q 'mWakefulness=Awake'; then
        input keyevent KEYCODE_WAKEUP
    fi
    am start -n "$PKG/.ToolboxActivity" --activity-single-top >/dev/null 2>&1
}

toggle_torch() {
    echo toolbox_torch > /sys/power/wake_lock
    cmd vibrator_manager synced oneshot 40 >/dev/null 2>&1
    am start-foreground-service -n "$PKG/.TorchService" -a "$PKG.TORCH_TOGGLE" >/dev/null 2>&1 \
        || log "torch: am start-foreground-service failed"
    echo toolbox_torch > /sys/power/wake_unlock
}

long_press_timer() {
    sleep "$LONG_PRESS"
    if [ "$(cat "$STATE")" = "down $1" ]; then
        echo "long $1" > "$STATE"
        toggle_torch
    fi
}

log "start pid $$"
while true; do
    dev=$(find_device) || { sleep 5; continue; }
    # STATE holds "down <n>", "long <n>" or "up". The long-press timer never gets killed
    # (killing a background job here takes the whole loop down); it checks STATE instead.
    press=0
    echo up > "$STATE"
    getevent -ql "$dev" | while read -r type code value _; do
        [ "$code" = "KEY_F1" ] || continue
        case "$value" in
            DOWN)
                echo "$WAKE_LOCK" > /sys/power/wake_lock
                press=$((press + 1))
                echo "down $press" > "$STATE"
                long_press_timer "$press" &
                ;;
            UP)
                case "$(cat "$STATE")" in
                    long*) ;;
                    *) open_panel ;;
                esac
                echo up > "$STATE"
                echo "$WAKE_LOCK" > /sys/power/wake_unlock
                ;;
        esac
    done
    log "getevent exited, restarting"
    sleep 2
done
