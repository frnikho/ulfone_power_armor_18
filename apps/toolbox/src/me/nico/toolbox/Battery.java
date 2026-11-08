package me.nico.toolbox;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.util.Locale;

/** Battery status line, charge limit (root script toolbox-battery.sh) and battery saver. */
final class Battery {
    static final int LIMIT_PERCENT = 80;
    private static final String LIMIT_FILE = "/data/adb/ventoy/charge_limit";

    private Battery() {}

    /** One-line summary from the sticky battery broadcast (no root needed). */
    static String summary(Context context) {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) {
            return "";
        }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = scale > 0 ? Math.round(level * 100f / scale) : level;
        float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int cycles = b.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);

        BatteryManager bm = context.getSystemService(BatteryManager.class);
        int microAmps = Math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));

        String state;
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            state = String.format(Locale.FRANCE, "en charge %.2f A", microAmps / 1e6f);
        } else if (plugged) {
            state = "branchée, charge en pause";
        } else {
            state = String.format(Locale.FRANCE, "décharge %.2f A", microAmps / 1e6f);
        }
        String line = String.format(Locale.FRANCE, "%d %% · %s · %.1f °C", percent, state, temp);
        return cycles > 0 ? line + " · " + cycles + " cycles" : line;
    }

    static boolean isLimitOn() {
        Root.Result r = Root.run("cat " + LIMIT_FILE);
        return r.ok() && !r.output.isEmpty();
    }

    /** Writes the config; the root script applies it within 30 s, applied now for "off". */
    static boolean setLimit(boolean on) {
        String cmd = on
                ? "echo " + LIMIT_PERCENT + " > " + LIMIT_FILE
                : "rm -f " + LIMIT_FILE + " && echo '0 0' > /proc/mtk_battery_cmd/current_cmd";
        return Root.run(cmd).ok();
    }

    static boolean isSaverOn(Context context) {
        return context.getSystemService(PowerManager.class).isPowerSaveMode();
    }

    static boolean setSaver(boolean on) {
        return Root.run("cmd power set-mode " + (on ? 1 : 0)).ok();
    }
}
