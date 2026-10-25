package org.jg.daka;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** 前台服务：常驻通知 + 保持屏幕常亮（不然息屏后截不到图）+ 承载投屏截图通道 */
public class KeepAliveService extends Service {

    static final int NID = 1;
    static final String CH = "daka";
    static final String EX_CODE = "code";
    static final String EX_DATA = "data";

    PowerManager.WakeLock wl;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Fx.log(c, "前台服务启动失败 " + t);
        }
    }

    /** 带着投屏授权启动（必须先起前台服务，再 createVirtualDisplay） */
    public static void startProjection(Context c, int code, Intent data) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            i.putExtra(EX_CODE, code);
            i.putExtra(EX_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Fx.log(c, "投屏服务启动失败 " + t);
        }
    }

    public static void stopSelf(Context c) {
        try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Throwable t) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "运行状态", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
            else b = new Notification.Builder(this);
            b.setContentTitle("集港打卡助手")
                    .setContentText("正在运行（点悬浮球可停止）")
                    .setSmallIcon(android.R.drawable.presence_online)
                    .setOngoing(true);
            startForeground(NID, b.build());
        } catch (Throwable t) {
            Fx.log(this, "常驻通知失败 " + t);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "daka:screen");
            wl.acquire();
        } catch (Throwable t) {
            Fx.log(this, "唤醒锁失败 " + t);
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && i.hasExtra(EX_CODE)) {
            int code = i.getIntExtra(EX_CODE, 0);
            Intent data = (Intent) i.getParcelableExtra(EX_DATA);
            if (data != null) Projection.start(this, code, data);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { Projection.stop(); } catch (Throwable t) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
