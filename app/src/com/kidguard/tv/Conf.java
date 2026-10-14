package com.kidguard.tv;

import android.content.Context;

import java.util.List;

/**
 * 配置整包导出 / 导入（电脑控制台「同步」用）。
 *
 * 格式（两边唯一的交换格式，改格式必须 PC 端一起改）：
 *   - 以 # 开头 = 注释，导入时忽略
 *   - 其余每行 key=value
 *   - 列表用半角逗号分隔（也接受 、 和 ; 换行）
 *   - 开关 1=开 0=关
 *   - 家长 PIN 不在本文件里（导出拿不到明文，导入也不会改 PIN）
 */
public final class Conf {

    private Conf() {}

    /** 文件格式版本。 */
    public static final String VERSION = "1";

    /** 导出成文本：PC 端存成本机配置文件，也是「上传」时使用的模板。 */
    public static String export(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# KidGuard 配置 v").append(VERSION).append('\n');
        sb.append("# 导出时间 ").append(Prefs.today()).append(' ').append(System.currentTimeMillis()).append('\n');
        sb.append("# 说明：列表用半角逗号分隔；开关 1=开 0=关；PIN 不在本文件里（导入不改 PIN）。\n");
        sb.append("limit=").append(Prefs.dailyMin(c)).append('\n');
        sb.append("step=").append(Prefs.addStep(c)).append('\n');
        sb.append("dirmin=").append(Prefs.dirMinMs(c)).append('\n');
        sb.append("coversec=").append(Prefs.coverSec(c)).append('\n');
        sb.append("guard=").append(b(Prefs.guardOn(c))).append('\n');
        sb.append("enforce=").append(b(Prefs.enforceApps(c))).append('\n');
        sb.append("hardlock=").append(b(Prefs.hardLock(c))).append('\n');
        sb.append("bl=").append(b(Prefs.blEnable(c))).append('\n');
        sb.append("blplay=").append(b(Prefs.blPlayBlock(c))).append('\n');
        sb.append("strict=").append(b(Prefs.strictPreview(c))).append('\n');
        sb.append("blkw=").append(Prefs.join(Prefs.blKeywords(c))).append('\n');
        sb.append("bltitle=").append(Prefs.join(Prefs.blTitles(c))).append('\n');
        sb.append("blpkg=").append(Prefs.join(Prefs.blPackages(c))).append('\n');
        sb.append("white=").append(Prefs.join(Prefs.whitelist(c))).append('\n');
        return sb.toString();
    }

    /** 逐项导入；返回「应用报告 + 应用后的完整配置」。 */
    public static String apply(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return "FAIL: 配置内容为空";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { sb.append("跳过（格式不对）：").append(line).append('\n'); continue; }
            String k = line.substring(0, eq).trim().toLowerCase();
            String v = line.substring(eq + 1).trim();
            try {
                if ("limit".equals(k)) {
                    int x = intOf(v, -1);
                    if (x <= 0) { sb.append("跳过（上限必须是正整数分钟）：limit=").append(v).append('\n'); continue; }
                    Prefs.setDailyMin(c, x);
                } else if ("step".equals(k)) {
                    int x = intOf(v, -1);
                    if (x <= 0) { sb.append("跳过（步长必须是正整数分钟）：step=").append(v).append('\n'); continue; }
                    Prefs.setAddStep(c, x);
                } else if ("dirmin".equals(k)) {
                    int x = intOf(v, -1);
                    if (x < 0 || x > 2000) { sb.append("跳过（方向键防抖需要 0~2000 毫秒）：dirmin=").append(v).append('\n'); continue; }
                    Prefs.setDirMinMs(c, x);
                } else if ("coversec".equals(k)) {
                    int x = intOf(v, -1);
                    if (x < 1 || x > 10) { sb.append("跳过（黑名单遮罩需要 1~10 秒）：coversec=").append(v).append('\n'); continue; }
                    Prefs.setCoverSec(c, x);
                } else if ("guard".equals(k)) {
                    Prefs.setGuardOn(c, boolOf(v, true));
                } else if ("enforce".equals(k)) {
                    Prefs.setEnforceApps(c, boolOf(v, true));
                } else if ("hardlock".equals(k)) {
                    Prefs.setHardLock(c, boolOf(v, true));
                } else if ("bl".equals(k)) {
                    Prefs.setBlEnable(c, boolOf(v, true));
                } else if ("blplay".equals(k)) {
                    Prefs.setBlPlayBlock(c, boolOf(v, true));
                } else if ("strict".equals(k)) {
                    Prefs.setStrictPreview(c, boolOf(v, false));
                } else if ("blkw".equals(k)) {
                    setList(c, "blKeywords", v);
                } else if ("bltitle".equals(k)) {
                    setList(c, "blTitles", v);
                } else if ("blpkg".equals(k)) {
                    setList(c, "blPackages", v);
                } else if ("white".equals(k)) {
                    setList(c, "whitelist", v);
                } else {
                    sb.append("忽略未知项：").append(k).append('\n');
                    continue;
                }
                n++;
            } catch (Throwable t) {
                sb.append("应用 ").append(k).append(" 失败：").append(t).append('\n');
            }
        }

        // 导入完立刻让运行状态跟上（该挂起就挂起，没锁就确保解挂）
        try {
            if (Prefs.guardOn(c) && Prefs.isLocked(c)) Admin.suspendBlocked(c);
            else Admin.unsuspendAll(c);
        } catch (Throwable t) {
            Log.w("conf apply enforce fail: " + t);
        }
        try { GuardService.start(c); } catch (Throwable ignored) { }

        sb.append("OK 已应用 ").append(n).append(" 项\n");
        sb.append(export(c));
        return sb.toString();
    }

    /** 列表项：拆开、去重、去掉两边空白；空串 = 清空该名单。 */
    private static void setList(Context c, String key, String v) {
        List<String> l = Prefs.split(v);
        Prefs.setList(c, key, l);
    }

    private static String b(boolean v) { return v ? "1" : "0"; }

    private static boolean boolOf(String v, boolean def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if ("1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s) || "开".equals(s)) return true;
        if ("0".equals(s) || "false".equals(s) || "off".equals(s) || "no".equals(s) || "关".equals(s)) return false;
        return def;
    }

    private static int intOf(String v, int def) {
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Throwable t) { return def; }
    }
}
