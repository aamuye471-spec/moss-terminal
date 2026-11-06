package com.mossfield.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * KeepAliveService：前台服务，让 Claude Code 会话在切后台、锁屏时不被系统杀掉。
 * 配合 RootBridge 的 R1（关 Phantom Process Killer）使用。
 * 注意：这只能降低被杀概率，不能保证 100% 存活；ColorOS 的后台管理仍需用户
 * 在系统设置里把本应用设为“电池优化：无限制”。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "moss_keepalive";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MossTerminal:keepalive");
            wakeLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.keepalive_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_keepalive),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.keepalive_text));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
