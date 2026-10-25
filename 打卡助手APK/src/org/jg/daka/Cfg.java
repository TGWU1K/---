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

    /** 允许动作的包名：微信；测试模式下额外允许假打卡页 */
    public static boolean isAllowedPkg(Context c, String pkg) {
        if (pkg == null) return true;              // 还拿不到前台包名时先放行（动作本身还有视觉护栏）
        if ("com.tencent.mm".equals(pkg)) return true;
        if (testAny(c) && "org.jg.mockdaka".equals(pkg)) return true;
        return false;
    }
}
