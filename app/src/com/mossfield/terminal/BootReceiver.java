package com.mossfield.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * BootReceiver：开机后提醒用户 root 状态。
 *
 * 临时 root 重启即失。开机后若检测不到 root，发一条通知提醒用户重新 root；
 * 若 root 还在，则不打扰。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "moss_boot";
    private static final int NOTIFICATION_ID = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        RootBridge bridge = new RootBridge(context.getFilesDir());
        if (bridge.hasRoot()) return; // root 还在，不打扰
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "开机提醒", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Moss Terminal")
                .setContentText("手机已重启，root 已消失。重新 root 后再打开 Moss Terminal。")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }
}
