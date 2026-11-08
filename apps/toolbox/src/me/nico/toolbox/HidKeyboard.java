package me.nico.toolbox;

/** USB HID boot keyboard (8-byte reports) on top of {@link HidPipe}. */
final class HidKeyboard {
    static final int MOD_CTRL = 0x01;
    static final int MOD_SHIFT = 0x02;
    static final int MOD_ALT = 0x04;
    static final int MOD_SUPER = 0x08;
    static final int MOD_ALTGR = 0x40;

    private final HidPipe pipe = new HidPipe(8);

    static boolean isEnabled() {
        Root.Result r = Root.run(HidPipe.SCRIPT + " status");
        return r.ok() && "on".equals(r.output);
    }

    /** Enables keyboard + mouse gadget functions (re-enumerates USB). Off the main thread. */
    static boolean enable() {
        return Root.run(HidPipe.SCRIPT + " on").ok();
    }

    static boolean disable() {
        return Root.run(HidPipe.SCRIPT + " off").ok();
    }

    boolean open() {
        return pipe.open("node");
    }

    void close() {
        pipe.close();
    }

    /** Press then release one key (usage 0 = modifiers only). */
    void tap(int modifiers, int usage) {
        pipe.send(new byte[] {(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0});
        pipe.send(new byte[8]);
    }

    /** A dead key followed by a base key (e.g. ^ then e = ê on AZERTY). */
    void tapSequence(int[][] keys) {
        for (int[] k : keys) {
            tap(k[0], k[1]);
        }
    }
}
