package me.nico.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the screen on (even on battery) until stopped from the panel or the notification. */
public class KeepAwakeService extends Service {
    static final String ACTION_STOP = "me.nico.toolbox.AWAKE_STOP";
    private static final String CHANNEL_ID = "awake";
    private static final int NOTIFICATION_ID = 3;

    private static volatile boolean running;
    private PowerManager.WakeLock wakeLock;

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, KeepAwakeService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, KeepAwakeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            @SuppressWarnings("deprecation")
            int level = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
            wakeLock = getSystemService(PowerManager.class).newWakeLock(level, "toolbox:awake");
            wakeLock.acquire();
        }
        running = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Écran toujours allumé", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, KeepAwakeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_awake)
                .setContentTitle("Écran toujours allumé")
                .setContentText("Toucher pour arrêter")
                .setContentIntent(stop)
                .setOngoing(true)
                .build();
    }
}
