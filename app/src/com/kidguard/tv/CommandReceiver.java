package com.kidguard.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * PC 控制通道（adb）：
 *   adb shell am broadcast -a com.kidguard.tv.CMD --es cmd status
 *   adb shell am broadcast -a com.kidguard.tv.CMD --es cmd add --es pin 1234 --es val 30
 * 结果同时写到 /sdcard/kidguard/status.txt，可直接 cat。
 */
public class CommandReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.kidguard.tv.CMD";

    @Override
    public void onReceive(Context c, Intent it) {
        String out;
        String cmd0 = null;
        try {
            Log.bind(c);
            Prefs.tickClock(c);
            Prefs.ensureDefaults(c);
            String cmd = it == null ? null : it.getStringExtra("cmd");
            String pin = it == null ? null : it.getStringExtra("pin");
            String val = it == null ? null : it.getStringExtra("val");
            if (cmd == null) cmd = (it == null || it.getAction() == null) ? "" : it.getAction();
            cmd0 = cmd;
            out = handle(c, cmd, pin, val);
        } catch (Throwable t) {
            Log.e("CommandReceiver fail", t);
            out = "ERROR " + t;
        }
        // 注意：conf 的返回值是配置全文，不能覆盖 status.txt，
        // 否则「2-查看状态.bat」读到的是配置而不是状态（v1.0.0 修）。
        try { if (!"conf".equals(cmd0)) writeStatus(c, out); } catch (Throwable ignored) { }
        try {
            setResultCode(0);
            setResultData(out);
        } catch (Throwable ignored) { }
    }

    public static void writeStatus(Context c, String s) { writeFile(c, "status.txt", s); }

    /** 同时写 /sdcard/kidguard/<name> 与应用专属外部目录（adb shell 都能 cat 到）。 */
    public static void writeFile(Context c, String name, String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Throwable t) { b = s.getBytes(); }
        // 1) 老系统/多数盒子：/sdcard/kidguard/<name>（adb shell cat 直接读）
        try {
            File dir = new File("/sdcard/kidguard");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(dir, name), false);
            fos.write(b);
            fos.close();
        } catch (Throwable t) {
            Log.w("writeFile sdcard fail: " + t);
        }
        // 2) Android 10+ 分区存储下的应用专属目录（adb shell 也能读）
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fos = new FileOutputStream(new File(dir, name), false);
                fos.write(b);
                fos.close();
            }
        } catch (Throwable t) {
            Log.w("writeFile ext fail: " + t);
        }
    }

    /** 读 /sdcard/kidguard/<name>（PC 端 push 进来的配置文件）；读不到返回 null。 */
    public static String readFile(Context c, String name) {
        try {
            File f = new File("/sdcard/kidguard", name);
            if (!f.exists() || !f.canRead()) {
                File d = c.getExternalFilesDir(null);
                File f2 = d == null ? null : new File(d, name);
                if (f2 == null || !f2.exists()) return null;
                f = f2;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            in.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            Log.w("readFile " + name + " fail: " + t);
            return null;
        }
    }

    private static boolean readonly(String cmd) {
        return "ping".equals(cmd) || "status".equals(cmd) || "get".equals(cmd)
                || "apps".equals(cmd) || "dump".equals(cmd) || "owner".equals(cmd)
                || "acc".equals(cmd) || "help".equals(cmd) || "".equals(cmd)
                || "conf".equals(cmd);
    }

    private String handle(Context c, String cmd, String pin, String val) {
        if ("ping".equals(cmd)) return "pong v" + 1;

        if (!readonly(cmd) && !Prefs.checkPin(c, pin)) {
            return "DENIED: 需要正确的 pin（--es pin 1234）";
        }

        if ("".equals(cmd) || "help".equals(cmd)) {
            return "可用命令: ping status apps dump owner acc sync conf conf-set setpin limit add step dirmin coversec reset free "
                 + "lock unlock start suspend resume release guard-on guard-off enforce-on enforce-off "
                 + "blpkg-add blpkg-del blkw-add blkw-del bltitle-add bltitle-del "
                 + "blwatch-on blwatch-off wl-add wl-del bl-on bl-off hardlock targets test-blk a11y-dump verbose "
                 + "strictprev-on strictprev-off cover-now allow-clear shot";
        }
        if ("status".equals(cmd) || "get".equals(cmd)) return status(c);
        if ("apps".equals(cmd)) return Admin.appList(c);
        if ("dump".equals(cmd)) return dump(c);
        if ("owner".equals(cmd)) return "isDeviceOwner=" + Admin.isOwner(c) + " isAdmin=" + Admin.isAdmin(c);
        if ("acc".equals(cmd)) return "accessibilityEnabled=" + accEnabled(c) + " svc=" + accEnabled(c, c.getPackageName());
        if ("sync".equals(cmd)) return Admin.applyAll(c);

        // ---------- 电脑控制台「同步」：整包导出 / 整包导入 ----------
        if ("conf".equals(cmd)) {
            // 只读命令（同 status，不要 PIN）：导出全部配置到 /sdcard/kidguard/config.txt 并原样返回
            String t = Conf.export(c);
            writeFile(c, "config.txt", t);
            return t;
        }
        if ("conf-set".equals(cmd)) {
            // 需要 PIN。配置内容由 PC 端 adb push 到 /sdcard/kidguard/config.in.txt 再发本命令，
            // 避免把中文/逗号/换行塞进 adb 命令行（引号地狱）。
            String t = readFile(c, "config.in.txt");
            if (t == null || t.trim().isEmpty()) {
                return "FAIL: 读不到配置文件 /sdcard/kidguard/config.in.txt\n"
                     + "（PC 端先 adb push 本机配置到该路径，再发 conf-set）";
            }
            String r = Conf.apply(c, t);
            writeFile(c, "config.txt", Conf.export(c));
            return r;
        }

        if ("setpin".equals(cmd)) {
            if (val == null || val.trim().length() < 4) return "FAIL: pin 至少 4 位";
            Prefs.setPin(c, val.trim());
            return "OK pin=" + val.trim();
        }
        if ("limit".equals(cmd)) {
            int v = parseInt(val, -1);
            if (v <= 0) return "FAIL: val 需要正整数（分钟）";
            Prefs.setDailyMin(c, v);
            GuardService.start(c);
            return "OK dailyMin=" + Prefs.dailyMin(c);
        }
        if ("step".equals(cmd)) {
            int v = parseInt(val, -1);
            if (v <= 0) return "FAIL: val 需要正整数（分钟）";
            Prefs.setAddStep(c, v);
            return "OK addStep=" + Prefs.addStep(c);
        }
        if ("dirmin".equals(cmd)) {
            // 方向键防抖窗口（毫秒）：0 = 关掉（恢复系统原样，也方便 A/B 对照）
            int v = parseInt(val, Prefs.dirMinMs(c));
            if (v < 0 || v > 2000) return "FAIL: val 需要 0~2000（毫秒），0=关掉防抖";
            Prefs.setDirMinMs(c, v);
            return "OK 方向键防抖=" + Prefs.dirMinMs(c) + "ms（0=关）";
        }
        if ("coversec".equals(cmd)) {
            // 命中黑名单后「已屏蔽」黑幕盖几秒（1~10，默认 10）。家长面板 / 电脑控制台都能调。
            int v = parseInt(val, Prefs.coverSec(c));
            if (v < 1 || v > 10) return "FAIL: val 需要 1~10（秒）";
            Prefs.setCoverSec(c, v);
            return "OK 黑名单遮罩=" + Prefs.coverSec(c) + "秒";
        }
        if ("verbose".equals(cmd)) {
            // 诊断日志开关（默认关）。排查「按键被拖慢 / 一次走很多格」这类问题时才打开，
            // 因为它会让无障碍服务打 EVT 等高声道日志（要调 e.getSource()，本身也会拖主线程）。
            String s = val == null ? "" : val.trim().toLowerCase();
            boolean on;
            if (s.length() == 0) on = !Prefs.verbose(c);
            else if ("1".equals(s) || "on".equals(s) || "true".equals(s)) on = true;
            else if ("0".equals(s) || "off".equals(s) || "false".equals(s)) on = false;
            else return "FAIL: val 需要 1/0（或 on/off，留空=切换）";
            Prefs.setVerbose(c, on);
            return "OK verbose=" + Prefs.verbose(c)
                    + (on ? "（已打开高频诊断日志，排查完记得 verbose 0 关掉）" : "");
        }
        if ("add".equals(cmd)) {
            int v = parseInt(val, Prefs.addStep(c));
            Prefs.addExtra(c, v);
            GuardService.start(c);
            return "OK 已加时 " + v + " 分钟；今日上限=" + (Prefs.dailyMin(c) + Prefs.extraToday(c))
                    + "分钟 已用=" + (Prefs.usedToday(c) / 60) + "分钟 锁定=" + Prefs.isLocked(c);
        }
        if ("reset".equals(cmd)) {
            Prefs.setUsedToday(c, 0);
            Prefs.setZeroedToday(c, true);
            Prefs.addExtra(c, -Prefs.extraToday(c));
            Prefs.setFreeToday(c, false);
            Prefs.setForceLock(c, false);
            Admin.unsuspendAll(c);
            GuardService.start(c);
            return "OK 今日用量已清零（今天不再用系统统计对账回来）";
        }
        if ("free".equals(cmd)) {
            boolean on = !("0".equals(val) || "false".equalsIgnoreCase(val));
            Prefs.setFreeToday(c, on);
            if (on) Prefs.setForceLock(c, false);
            GuardService.start(c);
            return "OK freeToday=" + on;
        }
        if ("lock".equals(cmd)) {
            Prefs.setForceLock(c, true);
            Prefs.setFreeToday(c, false);
            GuardService.start(c);
            return "OK 已强制锁定";
        }
        if ("unlock".equals(cmd)) {
            Prefs.setForceLock(c, false);
            Admin.unsuspendAll(c);
            GuardService.start(c);
            return "OK 已解除强制锁定";
        }
        if ("start".equals(cmd)) {
            GuardService.start(c);
            return "OK service started";
        }
        if ("suspend".equals(cmd)) {
            String e = Admin.suspendBlocked(c);
            return e == null ? "OK 已挂起黑名单应用" : "FAIL " + e;
        }
        if ("resume".equals(cmd)) {
            Admin.unsuspendAll(c);
            return "OK 已恢复";
        }
        if ("release".equals(cmd)) {
            String r = Admin.clearAll(c);
            return "OK 已撤销全部管控与限制（管控开关=关）\n" + r;
        }
        if ("bl-on".equals(cmd) || "bl-off".equals(cmd)) {
            Prefs.setBlEnable(c, "bl-on".equals(cmd));
            return "OK blEnable=" + Prefs.blEnable(c);
        }
        if ("guard-on".equals(cmd) || "guard-off".equals(cmd)) {
            boolean on = "guard-on".equals(cmd);
            Prefs.setGuardOn(c, on);
            if (on) GuardService.start(c); else Admin.unsuspendAll(c);
            return "OK guardOn=" + Prefs.guardOn(c)
                 + (on ? "" : "（管控已整体关闭：不再计时、不再拦截、已挂起的应用已全部恢复）");
        }
        if ("enforce-on".equals(cmd) || "enforce-off".equals(cmd)) {
            boolean on = "enforce-on".equals(cmd);
            Prefs.setEnforceApps(c, on);
            if (!on) Admin.unsuspendAll(c);
            return "OK enforceApps=" + Prefs.enforceApps(c)
                 + (on ? "" : "（应用挂起开关已关：锁定时不会再挂起应用，只靠黑幕提醒）");
        }
        if ("hardlock".equals(cmd)) {
            boolean on = !("0".equals(val) || "off".equalsIgnoreCase(val));
            Prefs.setHardLock(c, on);
            return "OK hardLock=" + Prefs.hardLock(c);
        }
        if ("targets".equals(cmd)) {
            return "wouldSuspend=" + Prefs.join(Admin.suspendTargets(c));
        }
        if (cmd != null && cmd.startsWith("blpkg-")) {
            String key = "blPackages";
            if (cmd.endsWith("-add")) { Prefs.addList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blPackages(c)); }
            if (cmd.endsWith("-del")) { Prefs.delList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blPackages(c)); }
        }
        if (cmd != null && cmd.startsWith("blkw-")) {
            String key = "blKeywords";
            if (cmd.endsWith("-add")) { Prefs.addList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blKeywords(c)); }
            if (cmd.endsWith("-del")) { Prefs.delList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blKeywords(c)); }
        }
        if (cmd != null && cmd.startsWith("bltitle-")) {
            String key = "blTitles";
            if (cmd.endsWith("-add")) { Prefs.addList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blTitles(c)); }
            if (cmd.endsWith("-del")) { Prefs.delList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.blTitles(c)); }
        }
        if ("blwatch-on".equals(cmd) || "blwatch-off".equals(cmd)) {
            Prefs.setBlPlayBlock(c, "blwatch-on".equals(cmd));
            return "OK blPlayBlock=" + Prefs.blPlayBlock(c);
        }
        if ("strictprev-on".equals(cmd) || "strictprev-off".equals(cmd)) {
            Prefs.setStrictPreview(c, "strictprev-on".equals(cmd));
            return "OK strictPreview=" + Prefs.strictPreview(c)
                    + "（开＝读不到片名也切：只要没按确定、有画面、在出声就当成自动预览切掉）";
        }
        if (cmd != null && cmd.startsWith("wl-")) {
            String key = "whitelist";
            if (cmd.endsWith("-add")) { Prefs.addList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.whitelist(c)); }
            if (cmd.endsWith("-del")) { Prefs.delList(c, key, val); return "OK " + key + "=" + Prefs.join(Prefs.whitelist(c)); }
        }
        if ("a11y-dump".equals(cmd)) {
            GuardAccessibilityService.DUMP_TREE = true;
            return "OK 下一次无障碍事件会把节点树写进 logcat（tag KidGuard，TREE 前缀）";
        }
        if ("cover-now".equals(cmd)) {
            // 手工测试「已屏蔽」遮罩：对当前前台 App 立刻盖 10 秒，看 logcat 的 COVER 行
            return GuardAccessibilityService.testCover();
        }
        if ("allow-clear".equals(cmd)) {
            // 不给包名 = 清掉所有临时放行
            if (val == null || val.length() == 0) {
                Prefs.clearAllAllow(c);
                return "OK 已取消全部放行；当前放行=" + allowList(c);
            }
            Prefs.clearAllow(c, val);
            return "OK 已取消放行 " + val + "；当前放行=" + allowList(c);
        }
        if ("test-blk".equals(cmd)) {
            String t = val == null ? "" : val;
            String[] m = Prefs.matchBlacklist(c, t);
            return "命中: " + (m == null ? "无" : (m[0] + "（" + m[1] + "）"))
                 + "  关键字=" + Prefs.join(Prefs.blKeywords(c)) + "  片名=" + Prefs.join(Prefs.blTitles(c))
                 + "  输入=\"" + t + "\"";
        }
        if ("shot".equals(cmd)) {
            // 手工测试「截图 + OCR」路线：起 ShotActivity 抓一帧。
            // 先跑一次 adb shell appops set com.kidguard.tv PROJECT_MEDIA allow，否则会弹系统授权框。
            Intent it = new Intent();
            it.setClassName(c, "com.kidguard.tv.ShotActivity");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(it);
            } catch (Throwable t) {
                return "ERR 起 ShotActivity 失败: " + t;
            }
            return "OK 已请求抓帧；结果看 logcat 的 SHOT 行或 cat /sdcard/kidguard/shot.txt；"
                 + "图片 /sdcard/kidguard/shot.png（若弹了系统授权框，请用遥控器点「允许」）";
        }
        return "UNKNOWN cmd=" + cmd;
    }

    public static String status(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== KidGuard 状态 ===\n");
        sb.append("时间            ").append(Prefs.today()).append(' ').append(System.currentTimeMillis()).append('\n');
        sb.append("日期桶          ").append(Prefs.sp(c).getString("day", "")).append('\n');
        sb.append("换天时刻        ").append(String.format("%02d:00", Prefs.DAY_START_HOUR)).append("（当天起点 ").append(Prefs.dayStartMillis()).append("）\n");
        String cn = Prefs.clockNote();
        sb.append("时钟            ").append(cn == null ? "正常（与开机以来的真实时长一致）" : ("已纠正：" + cn)).append('\n');
        sb.append("今日已用        ").append(Prefs.usedToday(c) / 60).append(" 分 ").append(Prefs.usedToday(c) % 60).append(" 秒\n");
        sb.append("每日上限        ").append(Prefs.dailyMin(c)).append(" 分钟\n");
        sb.append("今日加时        ").append(Prefs.extraToday(c)).append(" 分钟\n");
        sb.append("每次加时步长    ").append(Prefs.addStep(c)).append(" 分钟\n");
        sb.append("今日放行        ").append(Prefs.freeToday(c)).append('\n');
        sb.append("手动锁定        ").append(Prefs.forceLock(c)).append('\n');
        sb.append("强锁模式        ").append(Prefs.hardLock(c)).append('\n');
        sb.append("是否已锁定      ").append(Prefs.isLocked(c)).append('\n');
        sb.append("剩余            ").append(Prefs.remainSec(c) / 60).append(" 分 ").append(Prefs.remainSec(c) % 60).append(" 秒\n");
        sb.append("管控开关        ").append(Prefs.guardOn(c)).append('\n');
        sb.append("应用挂起开关    ").append(Prefs.enforceApps(c)).append('\n');
        sb.append("已设PIN         ").append(Prefs.hasPin(c)).append('\n');
        sb.append("DeviceOwner     ").append(Admin.isOwner(c)).append('\n');
        sb.append("DeviceAdmin     ").append(Admin.isAdmin(c)).append('\n');
        sb.append("无障碍已启用    ").append(accEnabled(c)).append('\n');
        sb.append("上次挂起结果    ").append(Prefs.sp(c).getString("lastLockErr", "")).append('\n');
        sb.append("黑名单关键字    ").append(Prefs.join(Prefs.blKeywords(c))).append('\n');
        sb.append("黑名单片名      ").append(Prefs.join(Prefs.blTitles(c))).append('\n');
        sb.append("播放页兜底      ").append(Prefs.blPlayBlock(c)).append('\n');
        sb.append("预览严格模式    ").append(Prefs.strictPreview(c))
          .append(Prefs.strictPreview(c) ? "（读不到片名也切：没按确定 + 有画面 + 在出声 = 自动预览）" : "").append('\n');
        sb.append("方向键防抖      ").append(Prefs.dirMinMs(c)).append(" ms")
          .append(Prefs.dirMinMs(c) > 0 ? "（同一方向键这个时间内连发只走一格）" : "（已关闭，恢复系统原样）").append('\n');
        sb.append("黑名单遮罩      ").append(Prefs.coverSec(c)).append(" 秒（命中后遥控器按不动这么久，1~10 家长可调）").append('\n');
        sb.append("诊断日志        ").append(Prefs.verbose(c) ? "verbose 开（高频日志，排查用）" : "verbose 关").append('\n');
        sb.append("黑名单应用      ").append(Prefs.join(Prefs.blPackages(c))).append('\n');
        sb.append("家长临时放行    ").append(allowList(c)).append('\n');
        sb.append("白名单          ").append(Prefs.join(Prefs.whitelist(c))).append('\n');
        sb.append("已挂起列表      ").append(Prefs.sp(c).getString("suspended", "")).append('\n');
        sb.append("grants          ").append(Prefs.sp(c).getString("grants", "{}")).append('\n');
        return sb.toString();
    }

    private static String dump(Context c) {
        return status(c) + "\n--- 已装应用 ---\n" + Admin.appList(c);
    }

    /** 家长临时放行了哪些 App（allow_<包名> = 截止毫秒）。 */
    private static String allowList(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            long now = System.currentTimeMillis();
            for (java.util.Map.Entry<String, ?> e : Prefs.sp(c).getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith("allow_")) continue;
                Object v = e.getValue();
                long t = (v instanceof Long) ? (Long) v : 0L;
                String pkg = k.substring("allow_".length());
                if (t > now) sb.append(pkg).append("(还剩 ").append((t - now) / 60000).append(" 分) ");
                else sb.append(pkg).append("(已过期) ");
            }
        } catch (Throwable ignored) { }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    public static boolean accEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean accEnabled(Context c, String pkg) {
        return accEnabled(c);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(String.valueOf(s).trim()); } catch (Throwable t) { return def; }
    }
}
