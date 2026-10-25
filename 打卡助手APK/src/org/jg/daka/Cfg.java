package org.jg.daka;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置（SharedPreferences） */
public class Cfg {
    public static final int TAP_GAP = 620;          // 每格之间等多久
    public static final int MISS_STREAK_MAX = 20;   // 连续多少轮没中 → 停
    public static final int FAIL_STREAK_MAX = 5;    // 连续多少次异常 → 停
    public static final int IDLE_STREAK_MAX = 15;   // 连续多少次找不到按钮 → 停

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static int rounds(Context c) { return sp(c).getInt("rounds", 40); }
    public static void setRounds(Context c, int v) { sp(c).edit().putInt("rounds", v).apply(); }

    public static int gap(Context c) { return sp(c).getInt("gap", 800); }

    public static boolean testAny(Context c) { return sp(c).getBoolean("testany", true); }
    public static void setTestAny(Context c, boolean v) { sp(c).edit().putBoolean("testany", v).apply(); }

    public static boolean debugNoTap(Context c) { return sp(c).getBoolean("debug", false); }
    public static void setDebugNoTap(Context c, boolean v) { sp(c).edit().putBoolean("debug", v).apply(); }

    public static boolean floaty(Context c) { return sp(c).getBoolean("floaty", true); }
    public static void setFloaty(Context c, boolean v) { sp(c).edit().putBoolean("floaty", v).apply(); }

    // ---------------- 验证码答题速度 ----------------
    public static final int BUDGET_MIN = 500;        // 从"看到弹窗"到"第 3 下点完"的总时长下限
    public static final int BUDGET_MAX = 4000;
    public static final int BUDGET_DEFAULT = 2000;   // 比司机实测平均(约 3.0s)快一点，仍在他自己的分布内

    public static int clampBudget(int v) {
        if (v < BUDGET_MIN) return BUDGET_MIN;
        if (v > BUDGET_MAX) return BUDGET_MAX;
        return v;
    }

    public static int budgetMs(Context c) {
        return clampBudget(sp(c).getInt("budget", BUDGET_DEFAULT));
    }

    public static void setBudgetMs(Context c, int v) {
        sp(c).edit().putInt("budget", clampBudget(v)).apply();
    }

    /** 校准模式：点完检查圆圈有没有变绿（多花约 1.2 秒，装机头几次用） */
    public static boolean calib(Context c) { return sp(c).getBoolean("calib", false); }
    public static void setCalib(Context c, boolean v) { sp(c).edit().putBoolean("calib", v).apply(); }

    /**
     * 把总时长 T 拆成 {读题, 第1→2, 第2→3}，毫秒。
     * 比例取司机实测均值 2.2 : 0.55 : 0.25 ≈ 72 : 18 : 10；每段有下限 250 / 100 / 100。
     * T 太小凑不出下限时，返回的就是下限之和（调用方负责在日志里说明）。
     */
    public static int[] plan(int T) {
        T = clampBudget(T);
        int read = (int) Math.round(T * 0.72);
        int g1 = (int) Math.round(T * 0.18);
        int g2 = T - read - g1;
        if (read < 250) read = 250;
        if (g1 < 100) g1 = 100;
        if (g2 < 100) g2 = 100;
        return new int[]{read, g1, g2};
    }

    /** 允许动作的包名：微信；测试模式下额外允许假打卡页 */
    public static boolean isAllowedPkg(Context c, String pkg) {
        if (pkg == null) return true;              // 还拿不到前台包名时先放行（动作本身还有视觉护栏）
        if ("com.tencent.mm".equals(pkg)) return true;
        if (testAny(c) && "org.jg.mockdaka".equals(pkg)) return true;
        return false;
    }
}
