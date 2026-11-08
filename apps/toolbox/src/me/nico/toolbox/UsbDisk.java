package me.nico.toolbox;

/** Wrapper around /data/adb/ventoy/usbdisk.sh (USB mass-storage LUN backed by ventoy.img). */
final class UsbDisk {
    private static final String SCRIPT = "/data/adb/ventoy/usbdisk.sh";

    private UsbDisk() {}

    static boolean isOn() {
        Root.Result r = Root.run(SCRIPT + " status");
        return r.ok() && "on".equals(r.output);
    }

    /** Returns the new state, or null when the script failed. */
    static Boolean setOn(boolean on) {
        Root.Result r = Root.run(SCRIPT + (on ? " on" : " off"));
        if (!r.ok()) {
            return null;
        }
        return isOn();
    }
}
