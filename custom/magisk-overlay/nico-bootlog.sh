#!/system/bin/sh
# Persist boot logs to /metadata/nico/<n>/ so a failed boot (DSU/GSI) can be read back from stock.
# /metadata is only ~24 MB: keep the last 2 boots; the early log (first ~2.5 MB) is never rotated away,
# the rolling log is capped at 3 x 512 KB; logging stops 30 s after boot completes.
# Watchdog: a GSI boot (ro.gsid.image_running=1) that hasn't completed after 300 s is rebooted, so a
# single-boot DSU test (gsi_tool enable -s) falls back to stock on its own.
D=/metadata/nico
WATCHDOG_S=300
EARLY_MAX_KB=2560
until mkdir -p "$D" 2>/dev/null && touch "$D/.w" 2>/dev/null; do sleep 1; done
n=$(( $(cat "$D/last" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$D/last"
rm -rf "$D/$((n - 2))"
B="$D/$n"
mkdir -p "$B"
cat /proc/cmdline > "$B/cmdline.txt"

( until logcat -b all -v time -r 512 -n 3 -f "$B/logcat.txt" 2>/dev/null; do sleep 2; done ) &
roll_pid=$!
( until logcat -b all -v time -f "$B/logcat-early.txt" 2>/dev/null; do sleep 2; done ) &
early_pid=$!

elapsed=0
done_for=0
while [ "$done_for" -lt 30 ]; do
    dmesg > "$B/dmesg.txt" 2>&1
    getprop > "$B/getprop.txt" 2>&1
    if [ -n "$early_pid" ] && [ "$(du -k "$B/logcat-early.txt" 2>/dev/null | cut -f1)" -ge "$EARLY_MAX_KB" ] 2>/dev/null; then
        pkill -P "$early_pid" logcat; kill "$early_pid" 2>/dev/null; early_pid=""
    fi
    if [ "$(getprop sys.boot_completed)" = "1" ]; then
        done_for=$((done_for + 5))
    elif [ "$(getprop ro.gsid.image_running)" = "1" ] && [ "$elapsed" -ge "$WATCHDOG_S" ]; then
        echo "watchdog: GSI boot not completed after ${elapsed}s, rebooting" > "$B/status.txt"
        sync
        reboot
    fi
    sleep 5
    elapsed=$((elapsed + 5))
done
pkill -P "$roll_pid" logcat; kill "$roll_pid" 2>/dev/null
[ -n "$early_pid" ] && { pkill -P "$early_pid" logcat; kill "$early_pid" 2>/dev/null; }
echo "stopped after boot (${elapsed}s)" > "$B/status.txt"
