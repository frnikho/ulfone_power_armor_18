package me.nico.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the torch on while the screen is off. CameraService turns the torch off when the
 * process that enabled it dies, so the process is pinned with a foreground service.
 * Start with ACTION_TOGGLE / ACTION_ON / ACTION_OFF (also from root: am start-foreground-service).
 */
public class TorchService extends Service {
    static final String ACTION_TOGGLE = "me.nico.toolbox.TORCH_TOGGLE";
    static final String ACTION_ON = "me.nico.toolbox.TORCH_ON";
    static final String ACTION_OFF = "me.nico.toolbox.TORCH_OFF";

    private static final String CHANNEL_ID = "torch";
    private static final int NOTIFICATION_ID = 1;

    private CameraManager cameraManager;
    private String cameraId;
    private boolean torchOn;
    private boolean callbackRegistered;
    private boolean seenOn;

    private final CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String id, boolean enabled) {
            if (!id.equals(cameraId)) {
                return;
            }
            torchOn = enabled;
            if (enabled) {
                seenOn = true;
            } else if (seenOn) {
                // Turned off elsewhere (QS tile, camera app): nothing left to keep alive.
                // The initial "off" callback delivered at registration is ignored.
                stopSelf();
            }
        }
    };

    static void send(Context context, String action) {
        context.startForegroundService(new Intent(context, TorchService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cameraManager = getSystemService(CameraManager.class);
        cameraId = findTorchCamera(cameraManager);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always satisfy the startForegroundService() contract first.
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (cameraId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!callbackRegistered) {
            cameraManager.registerTorchCallback(callback, new Handler(Looper.getMainLooper()));
            callbackRegistered = true;
        }
        String action = intent != null ? intent.getAction() : ACTION_TOGGLE;
        boolean target = ACTION_ON.equals(action) || (ACTION_TOGGLE.equals(action) && !torchOn);
        if (!setTorch(target) || !target) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (callbackRegistered) {
            cameraManager.unregisterTorchCallback(callback);
        }
        if (torchOn) {
            setTorch(false);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean setTorch(boolean on) {
        try {
            cameraManager.setTorchMode(cameraId, on);
            torchOn = on;
            return true;
        } catch (CameraAccessException | IllegalArgumentException e) {
            return false;
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Lampe torche", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent off = PendingIntent.getService(this, 0,
                new Intent(this, TorchService.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_torch)
                .setContentTitle("Lampe allumée")
                .setContentText("Toucher pour éteindre · appui long bouton gauche")
                .setContentIntent(off)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    static String findTorchCamera(CameraManager manager) {
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            return null;
        }
        return null;
    }
}
