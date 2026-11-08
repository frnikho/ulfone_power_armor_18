package me.nico.toolbox;

import static me.nico.toolbox.HidKeyboard.MOD_ALTGR;
import static me.nico.toolbox.HidKeyboard.MOD_SHIFT;

import java.util.HashMap;
import java.util.Map;

/**
 * Character -> HID key sequence for the PC's keyboard layout. A USB keyboard sends key
 * positions, so the mapping must match the layout configured on the PC.
 * Each entry is a list of {modifiers, usage} taps (dead keys = 2 taps).
 */
final class KeyLayouts {
    static final int ENTER = 0x28;
    static final int ESC = 0x29;
    static final int BACKSPACE = 0x2a;
    static final int TAB = 0x2b;
    static final int SPACE = 0x2c;
    static final int DELETE = 0x4c;
    static final int RIGHT = 0x4f;
    static final int LEFT = 0x50;
    static final int DOWN = 0x51;
    static final int UP = 0x52;
    static final int HOME = 0x4a;
    static final int END = 0x4d;
    static final int PAGE_UP = 0x4b;
    static final int PAGE_DOWN = 0x4e;
    static final int F1 = 0x3a; // F1..F12 = 0x3a..0x45

    private static final Map<Character, int[][]> FR = new HashMap<>();
    private static final Map<Character, int[][]> US = new HashMap<>();

    private KeyLayouts() {}

    static int[][] lookup(boolean azerty, char c) {
        return (azerty ? FR : US).get(c);
    }

    private static int letter(char c) {
        return 0x04 + (c - 'a');
    }

    private static void put(Map<Character, int[][]> m, char c, int mod, int usage) {
        m.put(c, new int[][] {{mod, usage}});
    }

    private static void dead(Map<Character, int[][]> m, char c, int deadMod, int deadUsage, int mod, int usage) {
        m.put(c, new int[][] {{deadMod, deadUsage}, {mod, usage}});
    }

    static {
        // ---- US QWERTY ----
        for (char c = 'a'; c <= 'z'; c++) {
            put(US, c, 0, letter(c));
            put(US, Character.toUpperCase(c), MOD_SHIFT, letter(c));
        }
        String digits = "1234567890";
        String shifted = "!@#$%^&*()";
        for (int i = 0; i < 10; i++) {
            put(US, digits.charAt(i), 0, 0x1e + i);
            put(US, shifted.charAt(i), MOD_SHIFT, 0x1e + i);
        }
        String punct = "-=[]\\;'`,./";
        String punctShift = "_+{}|:\"~<>?";
        int[] punctUsage = {0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
        for (int i = 0; i < punct.length(); i++) {
            put(US, punct.charAt(i), 0, punctUsage[i]);
            put(US, punctShift.charAt(i), MOD_SHIFT, punctUsage[i]);
        }
        put(US, ' ', 0, SPACE);
        put(US, '\n', 0, ENTER);
        put(US, '\t', 0, TAB);

        // ---- French AZERTY (xkb "fr") ----
        // Letter positions: a<->q, z<->w, m on ';' (0x33), ',' on 'm' (0x10).
        for (char c = 'a'; c <= 'z'; c++) {
            int usage;
            switch (c) {
                case 'a': usage = letter('q'); break;
                case 'q': usage = letter('a'); break;
                case 'z': usage = letter('w'); break;
                case 'w': usage = letter('z'); break;
                case 'm': usage = 0x33; break;
                default: usage = letter(c);
            }
            put(FR, c, 0, usage);
            put(FR, Character.toUpperCase(c), MOD_SHIFT, usage);
        }
        String frRow = "&é\"'(-è_çà";
        String frAltGr = "\0~#{[|`\\^@";
        for (int i = 0; i < 10; i++) {
            put(FR, frRow.charAt(i), 0, 0x1e + i);
            put(FR, digits.charAt(i), MOD_SHIFT, 0x1e + i);
            if (frAltGr.charAt(i) != '\0') {
                put(FR, frAltGr.charAt(i), MOD_ALTGR, 0x1e + i);
            }
        }
        put(FR, ')', 0, 0x2d);
        put(FR, '°', MOD_SHIFT, 0x2d);
        put(FR, ']', MOD_ALTGR, 0x2d);
        put(FR, '=', 0, 0x2e);
        put(FR, '+', MOD_SHIFT, 0x2e);
        put(FR, '}', MOD_ALTGR, 0x2e);
        put(FR, '$', 0, 0x30);
        put(FR, '£', MOD_SHIFT, 0x30);
        put(FR, 'ù', 0, 0x34);
        put(FR, '%', MOD_SHIFT, 0x34);
        put(FR, '*', 0, 0x32);
        put(FR, 'µ', MOD_SHIFT, 0x32);
        put(FR, ',', 0, 0x10);
        put(FR, '?', MOD_SHIFT, 0x10);
        put(FR, ';', 0, 0x36);
        put(FR, '.', MOD_SHIFT, 0x36);
        put(FR, ':', 0, 0x37);
        put(FR, '/', MOD_SHIFT, 0x37);
        put(FR, '!', 0, 0x38);
        put(FR, '§', MOD_SHIFT, 0x38);
        put(FR, '<', 0, 0x64);
        put(FR, '>', MOD_SHIFT, 0x64);
        put(FR, '²', 0, 0x35);
        put(FR, '€', MOD_ALTGR, letter('e'));
        put(FR, ' ', 0, SPACE);
        put(FR, '\n', 0, ENTER);
        put(FR, '\t', 0, TAB);
        // Dead keys on 0x2f: ^ (circumflex), shift = ¨ (diaeresis).
        String circ = "âêîôû";
        String diae = "äëïöüÿ";
        String base = "aeiouy";
        for (int i = 0; i < circ.length(); i++) {
            int[] b = FR.get(base.charAt(i))[0];
            dead(FR, circ.charAt(i), 0, 0x2f, b[0], b[1]);
        }
        for (int i = 0; i < diae.length(); i++) {
            int[] b = FR.get(base.charAt(i))[0];
            dead(FR, diae.charAt(i), MOD_SHIFT, 0x2f, b[0], b[1]);
        }
    }
}
