package me.nico.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sent by the root script toolbox-usb.sh when a PC enumerates the phone (exported, guarded by
 * DUMP so only root/shell can send it). Posts a notification with quick USB actions.
 */
public class UsbConnectReceiver extends BroadcastReceiver {
    static final String ACTION_PC_CONNECTED = "me.nico.toolbox.PC_CONNECTED";
    static final String ACTION_PC_DISCONNECTED = "me.nico.toolbox.PC_DISCONNECTED";
    static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "usb_pc";

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (ACTION_PC_DISCONNECTED.equals(intent.getAction())) {
            nm.cancel(NOTIFICATION_ID);
            return;
        }
        if (!ACTION_PC_CONNECTED.equals(intent.getAction())) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Branchement PC", NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle("Branché à un PC")
                .setContentText("Partager la connexion ou brancher la clé Ventoy ?")
                .setTimeoutAfter(5 * 60 * 1000)
                .setAutoCancel(true)
                .addAction(action(context, UsbActionReceiver.ACTION_TETHER, "Partage de connexion", 1))
                .addAction(action(context, UsbActionReceiver.ACTION_VENTOY, "Clé Ventoy", 2))
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static Notification.Action action(Context context, String action, String title, int code) {
        PendingIntent pi = PendingIntent.getBroadcast(context, code,
                new Intent(context, UsbActionReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, title, pi).build();
    }
}
