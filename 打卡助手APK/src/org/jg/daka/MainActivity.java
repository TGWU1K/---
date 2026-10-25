package org.jg.daka;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 主界面：状态 + 自检 + 开始/停止 + 日志 */
public class MainActivity extends Activity {

    static volatile MainActivity cur;

    TextView tvState, tvCheck, tvLog;
    Button btnRun;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        int dp = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);

        TextView title = new TextView(this);
        title.setText("集港打卡助手  v1.4");
        title.setTextSize(20);
        root.addView(title);

        tvState = new TextView(this);
        tvState.setTextSize(16);
        tvState.setPadding(0, 12 * dp, 0, 8 * dp);
        root.addView(tvState);

        tvCheck = new TextView(this);
        tvCheck.setTextSize(14);
        tvCheck.setPadding(0, 0, 0, 12 * dp);
        root.addView(tvCheck);

        LinearLayout perms = new LinearLayout(this);
        perms.setOrientation(LinearLayout.HORIZONTAL);
        Button[] pb = new Button[3];
        pb[0] = btn("无障碍", new View.OnClickListener() {
            public void onClick(View v) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS); }
        });
        pb[1] = btn("悬浮窗", new View.OnClickListener() {
            public void onClick(View v) {
                openPkg(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            }
        });
        pb[2] = btn("电池", new View.OnClickListener() {
            public void onClick(View v) { openBattery(); }
        });
        // 三个按钮放大到原来 2 倍；一排放不下就按屏宽均分后缩窄（高度不变）
        int bw = 0, bh = 0;
        for (Button tb : pb) {
            tb.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                       android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            bw = Math.max(bw, tb.getMeasuredWidth());
            bh = Math.max(bh, tb.getMeasuredHeight());
        }
        int margin = 6 * dp;
        int avail = getResources().getDisplayMetrics().widthPixels - 32 * dp;   // 扣掉 root 左右 padding
        int slot = (avail - margin * 3) / 3;
        int wantW = Math.min(bw * 2, slot);
        int wantH = bh * 2;
        for (int i = 0; i < 3; i++) {
            pb[i].setTextSize(20);                      // 字也放大：14 -> 20sp
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(wantW, wantH);
            p.rightMargin = margin;
            perms.addView(pb[i], p);
        }
        root.addView(perms);

        btnRun = btn("开始抢单", new View.OnClickListener() {
            public void onClick(View v) {
                if (Runner.isRunning()) {
                    Runner.stop();
                    refresh();
                } else {
                    startRun(MainActivity.this);
                }
            }
        });
        btnRun.setTextSize(18);
        btnRun.setPadding(0, 18 * dp, 0, 18 * dp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = 8 * dp;
        root.addView(btnRun, rlp);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(0, 16 * dp, 0, 8 * dp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView l1 = new TextView(this);
        l1.setText("轮数上限 ");
        l1.setTextSize(14);
        row1.addView(l1);
        final EditText et = new EditText(this);
        et.setText(String.valueOf(Cfg.rounds(this)));
        et.setTextSize(14);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row1.addView(et, elp);
        row1.addView(btn("保存", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    int n = Integer.parseInt(et.getText().toString().trim());
                    if (n < 1) n = 1;
                    if (n > 999) n = 999;
                    Cfg.setRounds(MainActivity.this, n);
                    Toast.makeText(MainActivity.this, "轮数上限 " + n, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "数字不对", Toast.LENGTH_SHORT).show();
                }
            }
        }), lp(dp));
        opts.addView(row1);

        final CheckBox cbTest = new CheckBox(this);
        cbTest.setText("测试模式：允许在「假打卡页」上动作");
        cbTest.setTextSize(14);
        cbTest.setChecked(Cfg.testAny(this));
        cbTest.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                Cfg.setTestAny(MainActivity.this, c);
            }
        });
        opts.addView(cbTest);

        final CheckBox cbFloaty = new CheckBox(this);
        cbFloaty.setText("显示悬浮球");
        cbFloaty.setTextSize(14);
        cbFloaty.setChecked(Cfg.floaty(this));
        cbFloaty.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                Cfg.setFloaty(MainActivity.this, c);
                if (c) Floaty.show(MainActivity.this);
                else Floaty.hide();
            }
        });
        opts.addView(cbFloaty);
        root.addView(opts);

        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.addView(btn("导出日志", new View.OnClickListener() {
            public void onClick(View v) {
                String r = Fx.exportLog(MainActivity.this);
                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
            }
        }), lp(dp));
        logRow.addView(btn("刷新日志", new View.OnClickListener() {
            public void onClick(View v) { showLog(); }
        }), lp(dp));
        logRow.addView(btn("清空", new View.OnClickListener() {
            public void onClick(View v) {
                Fx.clearLog(MainActivity.this);
                showLog();
            }
        }), lp(dp));
        root.addView(logRow);

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setPadding(0, 12 * dp, 0, 0);
        root.addView(tvLog);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Throwable t) { }
        }
        showLog();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cur = this;
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (cur == this) cur = null;
        super.onDestroy();
    }

    // ---------------- 自检 ----------------
    boolean a11yOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = getPackageName() + "/" + DakaService.class.getName();
            return s.contains(me) || s.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    boolean notifOn() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    boolean batteryOk() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    void refresh() {
        if (tvState == null) return;
        String st = Runner.I != null ? Runner.I.state : "待机";
        tvState.setText("状态：" + st + (Runner.isRunning() ? "（运行中）" : ""));
        boolean a = a11yOn(), o = Floaty.canShow(this), n = notifOn(), bt = batteryOk();
        String txt = "无障碍 " + (a ? "✔" : "✘")
                + "    悬浮窗 " + (o ? "✔" : "✘")
                + "\n通知 " + (n ? "✔" : "✘")
                + "    电池不限制 " + (bt ? "✔" : "✘");
        if (!a) txt += "\n\n⚠ 无障碍没开，脚本什么也做不了：点上面「无障碍」→ 找到「集港打卡助手」打开";
        tvCheck.setText(txt);
        if (btnRun != null) btnRun.setText(Runner.isRunning() ? "停止" : "开始抢单");
    }

    static void refreshIfVisible() {
        final MainActivity c = cur;
        if (c == null) return;
        c.runOnUiThread(new Runnable() {
            public void run() { c.refresh(); }
        });
    }

    void showLog() {
        if (tvLog == null) return;
        String s = Fx.readLog(this);
        if (s.length() > 6000) s = s.substring(s.length() - 6000);
        tvLog.setText(s);
    }

    // ---------------- 动作 ----------------
    static final int REQ_PROJ = 1001;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ) {
            if (res == RESULT_OK && data != null) {
                Fx.log(this, "拿到投屏授权");
                KeepAliveService.startProjection(this, res, data);
                final Context c = this;
                ui.postDelayed(new Runnable() {
                    public void run() {
                        MainActivity.startRun(c);
                    }
                }, 900);
            } else {
                Fx.log(this, "用户拒绝了投屏授权");
                Toast.makeText(this, "没有投屏授权，截图可能不可用；已尝试继续", Toast.LENGTH_LONG).show();
                doStart(this);
            }
        }
    }

    public static void startRun(Context c) {
        DakaService svc = DakaService.I;
        if (svc == null) {
            Toast.makeText(c, "无障碍服务没开：请到 系统设置→无障碍 里打开「集港打卡助手」", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable t) { }
            return;
        }
        if (!Floaty.canShow(c)) {
            Toast.makeText(c, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable t) { }
            return;
        }
        if (!Projection.ready() && c instanceof Activity && !RootShot.available()) {
            try {
                android.media.projection.MediaProjectionManager m =
                        (android.media.projection.MediaProjectionManager)
                                c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                ((Activity) c).startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
                Fx.log(c, "请求投屏授权（截图兜底通道）");
                return;
            } catch (Throwable t) {
                Fx.log(c, "投屏授权请求失败 " + t);
            }
        }
        doStart(c);
    }

    static void doStart(Context c) {
        Fx.log(c, "用户点了开始（投屏=" + (Projection.ready() ? "开" : "关") + "）");
        KeepAliveService.start(c);
        if (Cfg.floaty(c)) Floaty.show(c);
        Runner.start(c);
        Toast.makeText(c, "已开始，切到微信的集港打卡页", Toast.LENGTH_SHORT).show();
        refreshIfVisible();
    }

    void open(String action) {
        try {
            Intent i = new Intent(action);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开系统设置：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    void openPkg(String action) {
        try {
            Intent i = new Intent(action, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        }
    }

    void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable t2) { }
        }
    }

    Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    LinearLayout.LayoutParams lp(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = 6 * dp;
        return p;
    }
}
