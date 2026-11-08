package me.nico.toolbox;

/** USB HID boot-compatible mouse: 4-byte reports [buttons, dx, dy, wheel] on {@link HidPipe}. */
final class HidMouse {
    static final int LEFT = 0x01;
    static final int RIGHT = 0x02;
    static final int MIDDLE = 0x04;

    private final HidPipe pipe = new HidPipe(4);
    private int buttons;

    boolean open() {
        return pipe.open("mousenode");
    }

    void close() {
        pipe.close();
    }

    /** Relative move; large deltas are split into int8 steps. */
    void move(int dx, int dy) {
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx);
            int sy = clamp(dy);
            report(sx, sy, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    /** Wheel steps, positive = up. */
    void scroll(int steps) {
        while (steps != 0) {
            int s = clamp(steps);
            report(0, 0, s);
            steps -= s;
        }
    }

    void click(int button) {
        press(button);
        release(button);
    }

    void press(int button) {
        buttons |= button;
        report(0, 0, 0);
    }

    void release(int button) {
        buttons &= ~button;
        report(0, 0, 0);
    }

    boolean isPressed(int button) {
        return (buttons & button) != 0;
    }

    private void report(int dx, int dy, int wheel) {
        pipe.send(new byte[] {(byte) buttons, (byte) dx, (byte) dy, (byte) wheel});
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }
}
