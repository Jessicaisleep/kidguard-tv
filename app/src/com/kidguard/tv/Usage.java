package com.kidguard.tv;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.PowerManager;

import java.util.Calendar;
import java.util.List;

/** 亮屏时长统计：本机 App 折叠计数 + 系统 UsageStats 兜底对账（防拔电清记忆）。 */
public final class Usage {

    private Usage() {}

    public static boolean screenOn(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 折叠 sec 秒到今日用量（自动跨天翻转）。返回折叠后的今日用量（秒）。 */
    public static int fold(Context c, int sec) {
        if (sec <= 0) return Prefs.usedToday(c);
        if (Prefs.isNewDay(c)) Prefs.rollover(c);
        Prefs.addUsed(c, sec);
        return Prefs.usedToday(c);
    }

    /** 当前「逻辑日」的起点毫秒时间戳（凌晨 4 点换天，见 Prefs.DAY_START_HOUR）。 */
    public static long startOfDay() {
        return Prefs.dayStartMillis();
    }

    /**
     * 用系统 UsageStats 估算「今天前台总时长」，取其最大值作为下限。
     * 需要在部署时执行：appops set com.kidguard.tv android:get_usage_stats allow
     * 失败/无数据时返回 -1。
     */
    public static int foregroundToday(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return -1;
            long now = System.currentTimeMillis();
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay(), now);
            if (list == null || list.isEmpty()) return -1;
            long max = 0;
            for (int i = 0; i < list.size(); i++) {
                UsageStats u = list.get(i);
                if (u == null) continue;
                if (u.getTotalTimeInForeground() > max) max = u.getTotalTimeInForeground();
            }
            if (max <= 0) return -1;
            long sec = max / 1000L;
            if (sec > 24L * 3600L) sec = 24L * 3600L;
            return (int) sec;
        } catch (Throwable t) {
            Log.w("foregroundToday fail: " + t);
            return -1;
        }
    }

    /** 启动/上报时对账：取本机计数与系统统计的较大者为今日用量。 */
    public static void reconcile(Context c) {
        if (Prefs.isNewDay(c)) Prefs.rollover(c);
        if (Prefs.zeroedToday(c)) {
            // 家长今天明确清零过：别拿系统统计把时长"对账"回来（否则清零几秒后又被锁）
            Log.i("reconcile skipped: 家长今天清零过");
            return;
        }
        int local = Prefs.usedToday(c);
        int sys = foregroundToday(c);
        if (sys > local) {
            Log.i("reconcile: local=" + local + "s sys=" + sys + "s -> adopt sys");
            Prefs.setUsedToday(c, sys);
        }
    }
}
