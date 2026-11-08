#!/system/bin/sh
# Charge limit for the Power Armor 18 (MediaTek charger).
# Enabled when /data/adb/ventoy/charge_limit exists; it holds the stop level (e.g. 80).
# At >= limit: "0 1" > current_cmd (charger off, phone runs from the charger, battery at 0 mA).
# At <= limit - HYSTERESIS: "0 0" (charging resumes). Disabled: always "0 0".
LOG=/data/ventoy/toolbox-battery.log
CONF=/data/adb/ventoy/charge_limit
CMD=/proc/mtk_battery_cmd/current_cmd
CAPACITY=/sys/class/power_supply/battery/capacity
HYSTERESIS=5
POLL_SECONDS=30

PIDFILE=/dev/toolbox-battery.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDFILE"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

set_charging() {
    # $1 = 1 to allow charging, 0 to stop it.
    local want current
    if [ "$1" = 1 ]; then want="0 0"; else want="0 1"; fi
    current=$(cat "$CMD" 2>/dev/null)
    [ "$current" = "$want" ] && return 0
    echo "$want" > "$CMD" && log "charging=$1 at $(cat "$CAPACITY")%"
}

log "start pid $$"
while true; do
    level=$(cat "$CAPACITY" 2>/dev/null)
    limit=$(cat "$CONF" 2>/dev/null)
    case "$limit" in
        ''|*[!0-9]*) set_charging 1 ;;
        *)
            if [ "$level" -ge "$limit" ]; then
                set_charging 0
            elif [ "$level" -le $((limit - HYSTERESIS)) ]; then
                set_charging 1
            fi
            ;;
    esac
    sleep "$POLL_SECONDS"
done
