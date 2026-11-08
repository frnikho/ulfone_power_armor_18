package me.nico.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone as PC keyboard + mouse (USB HID): touchpad, mouse buttons, soft keyboard, special keys. */
public class KeyboardActivity extends Activity {
    private static final int BG = 0xFF14191C;
    private static final int KEY_BG = 0xFF2A3237;
    private static final int KEY_ON = 0xFFF2A33A;
    private static final int TEXT = 0xFFECEFF1;
    private static final int TEXT_DIM = 0xFF9AA6AD;
    private static final int ECHO_MAX = 400;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private HidKeyboard hid;
    private volatile HidMouse mouse;
    private LinearLayout keyRows;
    private boolean keyboardMode;
    private SharedPreferences prefs;
    private boolean azerty;
    private int stickyMods;
    private TextView status;
    private TextView echo;
    private Button layoutButton;
    private final Button[] modButtons = new Button[4];
    private static final int[] MOD_BITS = {HidKeyboard.MOD_CTRL, HidKeyboard.MOD_ALT,
            HidKeyboard.MOD_SUPER, HidKeyboard.MOD_SHIFT};
    private CaptureView capture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        azerty = prefs.getBoolean("azerty", true);
        setContentView(buildUi());
        status.setText("Connexion…");
        worker.execute(this::connect);
    }

    @Override
    protected void onDestroy() {
        if (hid != null) {
            hid.close();
        }
        if (mouse != null) {
            mouse.close();
        }
        worker.shutdown();
        super.onDestroy();
    }

    // ---- Connection -----------------------------------------------------

    private void connect() {
        boolean enabled = HidKeyboard.isEnabled();
        if (!enabled && UsbDisk.isOn()) {
            main.post(this::confirmVentoyDrop);
            return;
        }
        finishConnect(enabled);
    }

    private void confirmVentoyDrop() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Activer le clavier USB ?")
                .setMessage("La clé Ventoy va être déconnectée du PC quelques secondes.\n\n"
                        + "Démonte-la d'abord sur le PC (Éjecter).")
                .setPositiveButton("C'est démonté", (d, w) -> worker.execute(() -> finishConnect(false)))
                .setNegativeButton("Annuler", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void finishConnect(boolean alreadyEnabled) {
        if (!alreadyEnabled && !HidKeyboard.enable()) {
            main.post(() -> status.setText("Échec de l'activation (root ?)"));
            return;
        }
        HidKeyboard keyboard = new HidKeyboard();
        HidMouse pointer = new HidMouse();
        boolean ok = keyboard.open() && pointer.open();
        main.post(() -> {
            if (ok) {
                hid = keyboard;
                mouse = pointer;
                status.setText("Connecté au PC · clavier + souris");
            } else {
                status.setText("Échec de l'ouverture de /dev/hidg (root ?)");
            }
        });
    }

    // ---- Sending --------------------------------------------------------

    private void sendChar(char c) {
        int[][] keys = KeyLayouts.lookup(azerty, c);
        if (keys == null || hid == null) {
            return;
        }
        int mods = consumeSticky();
        if (keys.length == 1) {
            hid.tap(keys[0][0] | mods, keys[0][1]);
        } else {
            hid.tapSequence(keys);
        }
        appendEcho(String.valueOf(c));
    }

    private void sendKey(int usage, String label) {
        if (hid == null) {
            return;
        }
        hid.tap(consumeSticky(), usage);
        appendEcho(label);
    }

    private int consumeSticky() {
        int mods = stickyMods;
        if (mods != 0) {
            stickyMods = 0;
            renderMods();
        }
        return mods;
    }

    private void appendEcho(String s) {
        CharSequence cur = echo.getText();
        String next = cur + s;
        if (next.length() > ECHO_MAX) {
            next = next.substring(next.length() - ECHO_MAX);
        }
        echo.setText(next);
    }

    // ---- UI ---------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // Edge-to-edge (target 36): pad for status/navigation bars and the soft keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            v.setPadding(dp(14), bars.top + dp(8), dp(14), Math.max(bars.bottom, ime.bottom) + dp(8));
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Clavier & souris");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layoutButton = key(azerty ? "AZERTY" : "QWERTY", v -> toggleLayout());
        top.addView(key("⌨", v -> setKeyboardMode(!keyboardMode)));
        top.addView(layoutButton);
        top.addView(key("Couper", v -> disconnect()));
        root.addView(top);

        status = new TextView(this);
        status.setTextColor(TEXT_DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        keyRows = new LinearLayout(this);
        keyRows.setOrientation(LinearLayout.VERTICAL);
        keyRows.setVisibility(View.GONE);
        root.addView(keyRows);
        keyRows.addView(row(
                modKey(0, "Ctrl"), modKey(1, "Alt"), modKey(2, "Super"), modKey(3, "Maj"),
                key("Échap", v -> sendKey(KeyLayouts.ESC, "⎋")),
                key("Tab", v -> sendKey(KeyLayouts.TAB, "⇥")),
                key("Suppr", v -> sendKey(KeyLayouts.DELETE, "⌦")),
                key("Ctrl+Alt+Suppr", v -> {
                    if (hid != null) {
                        hid.tap(HidKeyboard.MOD_CTRL | HidKeyboard.MOD_ALT, KeyLayouts.DELETE);
                    }
                })));
        keyRows.addView(row(
                key("←", v -> sendKey(KeyLayouts.LEFT, "")),
                key("↓", v -> sendKey(KeyLayouts.DOWN, "")),
                key("↑", v -> sendKey(KeyLayouts.UP, "")),
                key("→", v -> sendKey(KeyLayouts.RIGHT, "")),
                key("Début", v -> sendKey(KeyLayouts.HOME, "")),
                key("Fin", v -> sendKey(KeyLayouts.END, "")),
                key("Pg↑", v -> sendKey(KeyLayouts.PAGE_UP, "")),
                key("Pg↓", v -> sendKey(KeyLayouts.PAGE_DOWN, ""))));
        Button[] fkeys = new Button[12];
        for (int i = 0; i < 12; i++) {
            int usage = KeyLayouts.F1 + i;
            fkeys[i] = key("F" + (i + 1), v -> sendKey(usage, ""));
        }
        keyRows.addView(row(fkeys));

        echo = new TextView(this);
        echo.setTextColor(TEXT_DIM);
        echo.setTypeface(Typeface.MONOSPACE);
        echo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        echo.setSingleLine(true);
        echo.setEllipsize(android.text.TextUtils.TruncateAt.START);
        echo.setPadding(dp(4), dp(6), dp(4), dp(6));
        root.addView(echo);

        capture = new CaptureView(this);
        capture.setFocusable(true);
        capture.setFocusableInTouchMode(true);
        root.addView(capture, new LinearLayout.LayoutParams(1, 1));

        TouchpadView pad = new TouchpadView(this, () -> mouse);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.topMargin = dp(4);
        root.addView(pad, plp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(mouseButton("Gauche", HidMouse.LEFT), weight());
        buttons.addView(mouseButton("Milieu", HidMouse.MIDDLE), weight());
        buttons.addView(mouseButton("Droit", HidMouse.RIGHT), weight());
        root.addView(buttons);
        return root;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        return lp;
    }

    /** Mouse button: pressed while the finger is down (allows click-and-drag on the pad). */
    @SuppressWarnings("ClickableViewAccessibility")
    private Button mouseButton(String label, int bit) {
        Button b = key(label, null);
        b.setOnTouchListener((v, e) -> {
            HidMouse m = mouse;
            if (m == null) {
                return true;
            }
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                m.press(bit);
                v.setBackground(keyBg(true));
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                m.release(bit);
                v.setBackground(keyBg(false));
            }
            return true;
        });
        return b;
    }

    private void setKeyboardMode(boolean on) {
        keyboardMode = on;
        keyRows.setVisibility(on ? View.VISIBLE : View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (on) {
            capture.requestFocus();
            imm.showSoftInput(capture, 0);
        } else {
            imm.hideSoftInputFromWindow(capture.getWindowToken(), 0);
        }
    }

    private View row(Button... keys) {
        LinearLayout row = new LinearLayout(this);
        for (Button b : keys) {
            row.addView(b);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private Button key(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinWidth(dp(52));
        b.setMinimumWidth(dp(52));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(keyBg(false));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private Button modKey(int index, String label) {
        Button b = key(label, v -> {
            stickyMods ^= MOD_BITS[index];
            renderMods();
        });
        modButtons[index] = b;
        return b;
    }

    private void renderMods() {
        for (int i = 0; i < modButtons.length; i++) {
            boolean on = (stickyMods & MOD_BITS[i]) != 0;
            modButtons[i].setBackground(keyBg(on));
            modButtons[i].setTextColor(on ? 0xFF1B2125 : TEXT);
        }
    }

    private GradientDrawable keyBg(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(on ? KEY_ON : KEY_BG);
        d.setCornerRadius(dp(12));
        return d;
    }

    private void toggleLayout() {
        azerty = !azerty;
        prefs.edit().putBoolean("azerty", azerty).apply();
        layoutButton.setText(azerty ? "AZERTY" : "QWERTY");
    }

    private void disconnect() {
        worker.execute(() -> {
            HidKeyboard.disable();
            main.post(this::finish);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- Soft keyboard capture --------------------------------------------

    /** Invisible editor: receives the soft keyboard input and forwards it to the PC. */
    private final class CaptureView extends View {
        private String composing = "";

        CaptureView(Context context) {
            super(context);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    replaceComposing(text.toString());
                    composing = "";
                    return true;
                }

                @Override
                public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    replaceComposing(text.toString());
                    composing = text.toString();
                    return true;
                }

                @Override
                public boolean finishComposingText() {
                    composing = "";
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) {
                        sendKey(KeyLayouts.BACKSPACE, "⌫");
                    }
                    for (int i = 0; i < after; i++) {
                        sendKey(KeyLayouts.DELETE, "⌦");
                    }
                    return true;
                }

                @Override
                public boolean performEditorAction(int actionCode) {
                    sendKey(KeyLayouts.ENTER, "\n");
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        handleKeyCode(event.getKeyCode(), event.getUnicodeChar());
                    }
                    return true;
                }
            };
        }

        /** Sends the difference between the previous composing text and the new one. */
        private void replaceComposing(String next) {
            int common = 0;
            while (common < composing.length() && common < next.length()
                    && composing.charAt(common) == next.charAt(common)) {
                common++;
            }
            for (int i = common; i < composing.length(); i++) {
                sendKey(KeyLayouts.BACKSPACE, "⌫");
            }
            for (int i = common; i < next.length(); i++) {
                sendChar(next.charAt(i));
            }
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return handleKeyCode(keyCode, event.getUnicodeChar()) || super.onKeyDown(keyCode, event);
        }

        private boolean handleKeyCode(int keyCode, int unicode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DEL: sendKey(KeyLayouts.BACKSPACE, "⌫"); return true;
                case KeyEvent.KEYCODE_FORWARD_DEL: sendKey(KeyLayouts.DELETE, "⌦"); return true;
                case KeyEvent.KEYCODE_ENTER: sendKey(KeyLayouts.ENTER, "\n"); return true;
                case KeyEvent.KEYCODE_TAB: sendKey(KeyLayouts.TAB, "⇥"); return true;
                case KeyEvent.KEYCODE_ESCAPE: sendKey(KeyLayouts.ESC, "⎋"); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: sendKey(KeyLayouts.LEFT, ""); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: sendKey(KeyLayouts.RIGHT, ""); return true;
                case KeyEvent.KEYCODE_DPAD_UP: sendKey(KeyLayouts.UP, ""); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: sendKey(KeyLayouts.DOWN, ""); return true;
                default:
                    if (unicode > 0) {
                        sendChar((char) unicode);
                        return true;
                    }
                    return false;
            }
        }
    }
}
