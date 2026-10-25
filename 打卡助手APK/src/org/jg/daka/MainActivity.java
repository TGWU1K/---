package org.jg.daka;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 主界面：状态 + 自检 + 开始/停止 + 日志 */
public class MainActivity extends Activity {

    static volatile MainActivity cur;

    TextView tvState, tvCheck, tvLog, tvSpeed;
    Button btnRun;
    SeekBar sbSpeed;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------- 验证码点击速度 ----------------
    private void pickSpeed(int ms) {
        Cfg.setBudgetMs(this, ms);
        int v = Cfg.budgetMs(this);
        if (sbSpeed != null) sbSpeed.setProgress((v - Cfg.BUDGET_MIN) / 100);
        showSpeed(v);
        Toast.makeText(this, "点数字速度：" + (v / 1000.0) + " 秒（下一轮生效）", Toast.LENGTH_SHORT).show();
    }

    /** 把当前设置画出来：秒数 + 人味区间 + 颜色 */
    private void showSpeed(int ms) {
        String name;
        if (ms < 800) name = "偏快，明显像脚本";
        else if (ms < 1200) name = "很快，风险偏高";
        else if (ms < 1500) name = "手很快的人";
        else if (ms <= 3500) name = "和真人一致（推荐）";
        else name = "比真人还从容";
        if (tvSpeed != null) {
            tvSpeed.setText("当前：" + (ms / 1000.0) + " 秒 · " + name);
            tvSpeed.setTextSize(18);
            tvSpeed.setTextColor(zoneColor(ms));
        }
    }

    private int zoneColor(int ms) {
        if (ms < 800) return 0xFFC62828;
        if (ms < 1200) return 0xFFEF6C00;
        if (ms < 1500) return 0xFFF9A825;
        if (ms <= 3500) return 0xFF2E7D32;
        return 0xFF1565C0;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        int dp = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);

        TextView title = new TextView(this);
        title.setText("集港打卡助手  v2.4");
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
        // 统一的大按钮尺寸：按「无障碍」原始尺寸 ×2（720 宽下实测 97×54 → 194×108），
        // 屏宽放不下就按均分收窄（高度不变）。所有"大按钮"行共用这一套尺寸。
        int margin = 8 * dp;
        int avail = getResources().getDisplayMetrics().widthPixels - 32 * dp;   // 扣掉 root 左右 padding
        int slot3 = (avail - margin * 3) / 3;
        int bw0, bh0;
        {
            Button probe = btn("无障碍", null);
            probe.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                          android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            bw0 = probe.getMeasuredWidth();
            bh0 = probe.getMeasuredHeight();
        }
        final int BIG_W = Math.min(bw0 * 2, slot3);
        final int BIG_H = bh0 * 2;
        for (int i = 0; i < 3; i++) {
            pb[i].setTextSize(20);                      // 字也放大：14 -> 20sp
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(BIG_W, BIG_H);
            p.rightMargin = margin;
            perms.addView(pb[i], p);
        }
        root.addView(perms);

        // ---------------- 验证码点击速度 ----------------
        TextView tvSpeedHint = new TextView(this);
        tvSpeedHint.setTextSize(12);
        tvSpeedHint.setPadding(0, 16 * dp, 0, 2 * dp);
        tvSpeedHint.setText("点数字速度：从看到验证码弹窗，到点完第 3 个数字的总时长。"
                + "司机自己实测平均 3.0 秒（1.5~3.5 秒）。");
        root.addView(tvSpeedHint);

        tvSpeed = new TextView(this);
        tvSpeed.setPadding(0, 4 * dp, 0, 6 * dp);
        root.addView(tvSpeed);

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        Button bSteady = btn("稳 3.0", new View.OnClickListener() {
            public void onClick(View v) { pickSpeed(3000); }
        });
        Button bFast = btn("快 2.0", new View.OnClickListener() {
            public void onClick(View v) { pickSpeed(2000); }
        });
        Button bMax = btn("极快 1.2", new View.OnClickListener() {
            public void onClick(View v) { pickSpeed(1200); }
        });
        Button[] spb = new Button[]{bMax, bFast, bSteady};   // 左→右：极快 1.2 | 快 2.0 | 稳 3.0
        for (Button tb : spb) {
            tb.setTextSize(20);                         // 与上面三个权限按钮完全一致：20sp
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(BIG_W, BIG_H);
            p.rightMargin = margin;
            speedRow.addView(tb, p);
        }
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = 4 * dp;
        root.addView(speedRow, srp);

        sbSpeed = new SeekBar(this);
        sbSpeed.setMax(35);                             // 0.5~4.0 秒，步进 0.1
        sbSpeed.setProgress((Cfg.budgetMs(this) - Cfg.BUDGET_MIN) / 100);
        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int ms = Cfg.BUDGET_MIN + p * 100;
                Cfg.setBudgetMs(MainActivity.this, ms);
                showSpeed(ms);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                int ms = Cfg.budgetMs(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        "点数字速度：" + (ms / 1000.0) + " 秒（下一轮生效）", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = 4 * dp;
        slp.bottomMargin = 4 * dp;
        root.addView(sbSpeed, slp);

        final CheckBox cbCalib = new CheckBox(this);
        cbCalib.setText("校准模式：点完核对圆圈变绿（慢约 1 秒，装机头几次用）");
        cbCalib.setTextSize(14);
        cbCalib.setChecked(Cfg.calib(this));
        cbCalib.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                Cfg.setCalib(MainActivity.this, c);
            }
        });
        root.addView(cbCalib);
        showSpeed(Cfg.budgetMs(this));

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

        root.addView(btn("抓一屏（5 秒后）· 看它到底看见了什么", new View.OnClickListener() {
            public void onClick(View v) { grabNow(); }
        }), lp(dp));

        root.addView(btn("投屏授权（可选）· 只在抓屏一直失败时才点", new View.OnClickListener() {
            public void onClick(View v) { askProjection(); }
        }), lp(dp));

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

        // 打开 App 就把小球放出来：先开助手 → 再去开打卡页 → 最后点小球开始
        if (Cfg.floaty(this) && Floaty.canShow(this) && DakaService.alive() && !Runner.isRunning()) {
            Floaty.set("点我开始", Floaty.COL_IDLE);
            Floaty.show(getApplicationContext());
        }
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

    // ---------------- 抓一屏（诊断） ----------------
    private void grabNow() {
        Toast.makeText(this, "5 秒后抓屏：现在切到要看的那一页，别动屏幕", Toast.LENGTH_LONG).show();
        Fx.log(this, "抓屏：5 秒倒计时，现在切到要看的那一页");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (DakaService.I == null) {
                    Fx.log(MainActivity.this, "抓屏失败：无障碍没连上");
                    return;
                }
                // 截图要 1~2 秒，绝不能放在主线程里等
                new Thread(new Runnable() {
                    public void run() {
                        Bitmap b = null;
                        try {
                            b = DakaService.I.capture(3000);
                        } catch (Throwable t) {
                            Fx.log(MainActivity.this, "抓屏出错：" + t);
                        }
                        if (b == null) {
                            Fx.log(MainActivity.this, "抓屏失败：无障碍截图拿不到帧，可点下面「投屏授权」");
                            ui.post(new Runnable() { public void run() { showLog(); } });
                            return;
                        }
                        try {
                            Vision.Shot s = new Vision.Shot(b);
                            Fx.log(MainActivity.this, "抓屏 " + Vision.diag(s));
                            String p = Fx.savePng(MainActivity.this, b, "手动抓屏");
                            Fx.log(MainActivity.this, "已存相册：" + p);
                        } catch (Throwable t) {
                            Fx.log(MainActivity.this, "抓屏出错：" + t);
                        } finally {
                            try { b.recycle(); } catch (Throwable t) { }
                        }
                        ui.post(new Runnable() { public void run() { showLog(); } });
                    }
                }, "daka-grab").start();
            }
        }, 5000);
    }

    /** 手动要投屏授权（无障碍截图不行时的兜底通道） */
    private void askProjection() {
        if (Projection.ready()) {
            Fx.log(this, "投屏已经在开了");
            Toast.makeText(this, "投屏已经开着了", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.media.projection.MediaProjectionManager m =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
            Fx.log(this, "用户点了投屏授权");
        } catch (Throwable t) {
            Fx.log(this, "投屏授权请求失败 " + t);
        }
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
        if (btnRun != null) btnRun.setText(Runner.isRunning() ? "停止" : "开始抢单（也可以点小球）");
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
                Toast.makeText(this, "投屏通道已开，现在可以点开始抢单", Toast.LENGTH_LONG).show();
                refreshIfVisible();
            } else {
                Fx.log(this, "用户拒绝了投屏授权");
                Toast.makeText(this, "没有投屏授权，就走了无障碍截图这一条路", Toast.LENGTH_LONG).show();
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
        // 投屏不在开始时强制要授权：绝大多数手机无障碍截图就够用。
        // 只有无障碍截图一直拿不到帧时，用户才需要手动点「投屏授权」那个按钮。
        doStart(c);
    }

    static void doStart(Context c) {
        Fx.log(c, "用户点了开始（投屏=" + (Projection.ready() ? "开" : "关") + "）");
        KeepAliveService.start(c);
        if (Cfg.floaty(c)) Floaty.show(c);
        Runner.start(c);
        Toast.makeText(c, "已开始：现在去打开微信的打卡页，助手会等你", Toast.LENGTH_SHORT).show();
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
