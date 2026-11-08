package me.nico.toolbox;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Handles the buttons of the "Branché à un PC" notification (not exported). */
public class UsbActionReceiver extends BroadcastReceiver {
    static final String ACTION_TETHER = "me.nico.toolbox.USB_TETHER";
    static final String ACTION_VENTOY = "me.nico.toolbox.USB_VENTOY";

    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSystemService(NotificationManager.class).cancel(UsbConnectReceiver.NOTIFICATION_ID);
        String action = intent.getAction();
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            String message;
            if (ACTION_TETHER.equals(action) && UsbDisk.isOn()) {
                // Re-enumeration would yank a mounted Ventoy volume: let the panel confirm.
                message = "Clé Ventoy branchée : démonte-la puis utilise le panneau";
            } else if (ACTION_TETHER.equals(action)) {
                message = UsbTether.setOn(true) ? "Partage de connexion USB activé" : "Échec (root ?)";
            } else if (ACTION_VENTOY.equals(action)) {
                Boolean on = UsbDisk.setOn(true);
                message = Boolean.TRUE.equals(on) ? "Clé Ventoy branchée" : "Échec (root ?)";
            } else {
                message = null;
            }
            if (message != null) {
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
            }
            pending.finish();
        }).start();
    }
}
