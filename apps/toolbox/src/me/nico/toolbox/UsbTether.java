package me.nico.toolbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * USB tethering through the RNDIS gadget function. Tethering starts on its own as soon as
 * RNDIS is configured by the host, so switching the USB function is enough (needs root).
 */
final class UsbTether {
    private UsbTether() {}

    /** Reads sys.usb.config without root. */
    static boolean isOn() {
        try {
            Process p = new ProcessBuilder("getprop", "sys.usb.config").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line != null && (line.contains("rndis") || line.contains("ncm"));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns false when the root command failed. */
    static boolean setOn(boolean on) {
        return Root.run(on ? "svc usb setFunctions rndis" : "svc usb setFunctions").ok();
    }
}
