package me.nico.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Power-menu style panel opened by the left hardware key (via Key Mapper) or the launcher. */
public class ToolboxActivity extends Activity {
    private static final int COLOR_PANEL = 0xF21B2125;
    private static final int COLOR_TILE = 0xFF2A3237;
    private static final int COLOR_TILE_ON = 0xFFF2A33A;
    private static final int COLOR_TEXT = 0xFFECEFF1;
    private static final int COLOR_TEXT_DIM = 0xFF9AA6AD;
    private static final int COLOR_TEXT_ON = 0xFF1B2125;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Tile usbTile;
    private Tile torchTile;
    private Tile dataTile;
    private Tile tetherTile;
    private Tile awakeTile;
    private Tile saverTile;
    private Tile limitTile;
    private Tile keyboardTile;
    private TextView batteryLine;

    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn;
    private int contentWidth;
    private boolean usbDiskOn;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) {
                torchOn = enabled;
                torchTile.setState(enabled, enabled ? "Allumée" : "Éteinte");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        // Opened from the hardware key on a locked phone: ask for unlock, then show the panel.
        android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        if (keyguard.isKeyguardLocked()) {
            keyguard.requestDismissKeyguard(this, null);
        }
        cameraManager = getSystemService(CameraManager.class);
        torchCameraId = findTorchCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStates();
        if (torchCameraId != null) {
            cameraManager.registerTorchCallback(torchCallback, main);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (torchCameraId != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }
    }

    /** Pressing the hardware key again while the panel is open closes it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ---- UI -------------------------------------------------------------

    private View buildRoot() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.min(screen - dp(24), dp(420));
        contentWidth = panelWidth - dp(40);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setBackground(rounded(COLOR_PANEL, dp(28)));
        panel.setElevation(dp(12));
        panel.setClickable(true);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, 0);
        panel.addView(title);

        batteryLine = new TextView(this);
        batteryLine.setTextColor(COLOR_TEXT_DIM);
        batteryLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        batteryLine.setPadding(dp(4), dp(2), 0, dp(14));
        panel.addView(batteryLine);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        usbTile = new Tile(R.drawable.ic_usb, "Clé USB", v -> toggleUsb());
        torchTile = new Tile(R.drawable.ic_torch, "Lampe", v -> toggleTorch());
        dataTile = new Tile(R.drawable.ic_refresh, "Données mobiles", v -> restartMobileData());
        tetherTile = new Tile(R.drawable.ic_tether, "Partage USB", v -> toggleTether());
        awakeTile = new Tile(R.drawable.ic_awake, "Écran allumé", v -> toggleAwake());
        saverTile = new Tile(R.drawable.ic_saver, "Économie", v -> toggleSaver());
        limitTile = new Tile(R.drawable.ic_battery_limit,
                "Charge " + Battery.LIMIT_PERCENT + " %", v -> toggleLimit());
        keyboardTile = new Tile(R.drawable.ic_keyboard, "Clavier & souris", v -> {
            startActivity(new Intent(this, KeyboardActivity.class));
            finish();
        });
        for (Tile tile : new Tile[] {usbTile, tetherTile, keyboardTile, torchTile, awakeTile,
                limitTile, saverTile, dataTile}) {
            grid.addView(tile.view);
        }
        panel.addView(grid);

        View divider = new View(this);
        divider.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.setMargins(dp(8), dp(14), dp(8), 0);
        panel.addView(divider, dlp);
        panel.addView(new AppShortcuts(this, contentWidth / 4).view());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, lp);
        return root;
    }

    private final class Tile {
        final LinearLayout view;
        final ImageView icon;
        final TextView label;
        final TextView state;

        Tile(int iconRes, String text, View.OnClickListener onClick) {
            view = new LinearLayout(ToolboxActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(16), dp(16), dp(16), dp(14));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = contentWidth / 2 - dp(10);
            lp.height = dp(104);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            view.setLayoutParams(lp);
            view.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onClick.onClick(v);
            });

            icon = new ImageView(ToolboxActivity.this);
            icon.setImageResource(iconRes);
            view.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

            View spacer = new View(ToolboxActivity.this);
            view.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

            label = new TextView(ToolboxActivity.this);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.addView(label);

            state = new TextView(ToolboxActivity.this);
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            view.addView(state);

            setState(false, "…");
        }

        void setState(boolean on, String text) {
            int bg = on ? COLOR_TILE_ON : COLOR_TILE;
            int fg = on ? COLOR_TEXT_ON : COLOR_TEXT;
            view.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(on ? 0x33000000 : 0x33FFFFFF),
                    rounded(bg, dp(22)), null));
            icon.setImageTintList(ColorStateList.valueOf(fg));
            label.setTextColor(fg);
            state.setTextColor(on ? COLOR_TEXT_ON : COLOR_TEXT_DIM);
            state.setText(text);
        }
    }

    // ---- Actions --------------------------------------------------------

    private void refreshStates() {
        renderTether(UsbTether.isOn());
        keyboardTile.setState(false, "Piloter le PC");
        batteryLine.setText(Battery.summary(this));
        renderAwake(KeepAwakeService.isRunning());
        renderSaver(Battery.isSaverOn(this));
        worker.execute(() -> {
            boolean limit = Battery.isLimitOn();
            main.post(() -> renderLimit(limit));
        });
        dataTile.setState(false, "Toucher pour relancer");
        if (torchCameraId == null) {
            torchTile.setState(false, "Indisponible");
        }
        worker.execute(() -> {
            boolean usb = UsbDisk.isOn();
            main.post(() -> renderUsb(usb));
        });
    }

    private void renderUsb(boolean on) {
        usbDiskOn = on;
        usbTile.setState(on, on ? "Ventoy branché" : "Désactivée");
    }

    private void toggleUsb() {
        if (usbDiskOn) {
            confirmUsbDrop("Débrancher la clé Ventoy ?", this::doToggleUsb);
        } else {
            doToggleUsb();
        }
    }

    private void doToggleUsb() {
        boolean target = !usbDiskOn;
        usbTile.setState(target, target ? "Activation…" : "Arrêt…");
        worker.execute(() -> {
            Boolean result = UsbDisk.setOn(target);
            main.post(() -> {
                if (result == null) {
                    usbTile.setState(false, "Échec (root ?)");
                } else {
                    renderUsb(result);
                }
            });
        });
    }

    private void toggleTorch() {
        if (torchCameraId == null) {
            return;
        }
        TorchService.send(this, torchOn ? TorchService.ACTION_OFF : TorchService.ACTION_ON);
    }

    private void restartMobileData() {
        dataTile.setState(true, "Relance…");
        worker.execute(() -> {
            Root.Result r = Root.run("svc data disable && sleep 2 && svc data enable");
            main.post(() -> dataTile.setState(false, r.ok() ? "Relancées ✓" : "Échec (root ?)"));
        });
    }

    private void renderAwake(boolean on) {
        awakeTile.setState(on, on ? "Toujours allumé" : "Normal");
    }

    private void toggleAwake() {
        boolean target = !KeepAwakeService.isRunning();
        if (target) {
            KeepAwakeService.start(this);
        } else {
            KeepAwakeService.stop(this);
        }
        renderAwake(target);
    }

    private void renderSaver(boolean on) {
        saverTile.setState(on, on ? "Activée" : "Désactivée");
    }

    private void toggleSaver() {
        boolean target = !Battery.isSaverOn(this);
        saverTile.setState(target, target ? "Activation…" : "Arrêt…");
        worker.execute(() -> {
            boolean ok = Battery.setSaver(target);
            main.postDelayed(() -> {
                if (ok) {
                    renderSaver(Battery.isSaverOn(this));
                } else {
                    saverTile.setState(false, "Échec (root ?)");
                }
            }, 400);
        });
    }

    private boolean limitOn;

    private void renderLimit(boolean on) {
        limitOn = on;
        limitTile.setState(on, on ? "Limite active" : "Charge complète");
    }

    private void toggleLimit() {
        boolean target = !limitOn;
        limitTile.setState(target, "…");
        worker.execute(() -> {
            boolean ok = Battery.setLimit(target);
            boolean now = Battery.isLimitOn();
            main.post(() -> {
                if (ok) {
                    renderLimit(now);
                } else {
                    limitTile.setState(false, "Échec (root ?)");
                }
            });
        });
    }

    private void renderTether(boolean on) {
        tetherTile.setState(on, on ? "Connexion partagée" : "Désactivé");
    }

    private void toggleTether() {
        if (usbDiskOn) {
            confirmUsbDrop("Changer le partage USB ?", this::doToggleTether);
        } else {
            doToggleTether();
        }
    }

    /** USB function changes re-enumerate the gadget: a PC that has Ventoy mounted loses it. */
    private void confirmUsbDrop(String title, Runnable action) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage("La clé Ventoy va être déconnectée du PC quelques secondes.\n\n"
                        + "Démonte-la d'abord sur le PC (Éjecter), sinon ses fichiers "
                        + "risquent d'être abîmés.")
                .setPositiveButton("C'est démonté", (d, w) -> action.run())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void doToggleTether() {
        boolean target = !UsbTether.isOn();
        tetherTile.setState(target, target ? "Activation…" : "Arrêt…");
        worker.execute(() -> {
            boolean ok = UsbTether.setOn(target);
            main.postDelayed(() -> {
                if (ok) {
                    renderTether(UsbTether.isOn());
        keyboardTile.setState(false, "Piloter le PC");
        batteryLine.setText(Battery.summary(this));
        renderAwake(KeepAwakeService.isRunning());
        renderSaver(Battery.isSaverOn(this));
        worker.execute(() -> {
            boolean limit = Battery.isLimitOn();
            main.post(() -> renderLimit(limit));
        });
                } else {
                    tetherTile.setState(false, "Échec (root ?)");
                }
            }, 1500);
        });
    }

    // ---- Helpers --------------------------------------------------------

    private String findTorchCamera() {
        return TorchService.findTorchCamera(cameraManager);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unused")
    private static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }
}
