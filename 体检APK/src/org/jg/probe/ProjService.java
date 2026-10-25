package org.jg.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 投屏必须挂在一个前台服务上，否则 Android 10+ 会拒 */
public class ProjService extends Service {

    @Override public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("probe",
                        "体检投屏", NotificationManager.IMPORTANCE_MIN);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "probe") : new Notification.Builder(this);
            b.setContentTitle("集港体检").setContentText("正在测试投屏取帧").setSmallIcon(android.R.drawable.ic_menu_search);
            startForeground(11, b.build());
        } catch (Throwable t) {
            Logx.add("前台服务启动失败 " + t);
        }
    }

    @Override public int onStartCommand(Intent it, int flags, int startId) {
        if (it != null && it.hasExtra("code") && it.hasExtra("data")) {
            int code = it.getIntExtra("code", 0);
            Intent data = (Intent) it.getParcelableExtra("data");
            boolean ok = Proj.start(this, code, data);
            Logx.add(ok ? "投屏通道已开" : "投屏通道开不起来：" + Proj.err());
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        Proj.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent it) { return null; }

    public static void start(Context c, int code, Intent data) {
        Intent i = new Intent(c, ProjService.class);
        i.putExtra("code", code);
        i.putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
