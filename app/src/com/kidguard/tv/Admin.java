package com.kidguard.tv;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Device Owner / 设备管理员能力封装。所有调用都单独 try-catch，并把真实结果记进 grants。 */
public final class Admin {

    private Admin() {}

    public static ComponentName cn(Context c) {
        return new ComponentName(c.getPackageName(), GuardAdminReceiver.class.getName());
    }

    public static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isOwner(Context c) {
        try {
            return dpm(c).isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAdmin(Context c) {
        try {
            return dpm(c).isAdminActive(cn(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 普通设备管理员激活（单机、无 USB 部署时的降级路径）。 */
    public static void requestAdmin(Context c) {
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn(c));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "用于限制电视使用时长、阻止卸载管控程序。");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.e("requestAdmin fail", t);
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Throwable ignored) { }
    }

    /** 应用全部策略，返回 grant/deny 报告（同时写入 prefs["grants"]）。 */
    public static String applyAll(Context c) {
        JSONObject g = new JSONObject();
        DevicePolicyManager d = dpm(c);
        ComponentName a = cn(c);
        boolean own = isOwner(c);
        put(g, "deviceOwner", own);
        if (d == null || (!own && !isAdmin(c))) {
            put(g, "note", "未激活设备管理员，策略全部跳过");
            Prefs.sp(c).edit().putString("grants", g.toString()).apply();
            return g.toString();
        }

        List<String> policies = new ArrayList<String>();
        policies.add(UserManager.DISALLOW_UNINSTALL_APPS);
        policies.add(UserManager.DISALLOW_APPS_CONTROL);
        policies.add(UserManager.DISALLOW_SAFE_BOOT);
        policies.add(UserManager.DISALLOW_ADD_USER);
        policies.add(UserManager.DISALLOW_FACTORY_RESET);
        policies.add(UserManager.DISALLOW_CONFIG_DATE_TIME);
        policies.add(UserManager.DISALLOW_CREATE_WINDOWS);
        policies.add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
        if (Build.VERSION.SDK_INT >= 23) policies.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);

        for (int i = 0; i < policies.size(); i++) {
            String p = policies.get(i);
            boolean ok = false;
            try { d.addUserRestriction(a, p); ok = true; } catch (Throwable t) { Log.w("restrict " + p + " fail: " + t); }
            put(g, p, ok);
        }

        try {
            d.setStatusBarDisabled(a, true);
            put(g, "statusBarDisabled", true);
        } catch (Throwable t) { put(g, "statusBarDisabled", false); }

        try {
            d.setKeyguardDisabled(a, true);
            put(g, "keyguardDisabled", true);
        } catch (Throwable t) { put(g, "keyguardDisabled", false); }

        try {
            d.setLockTaskPackages(a, new String[]{ c.getPackageName() });
            put(g, "lockTaskPackages", true);
        } catch (Throwable t) { put(g, "lockTaskPackages", false); }

        if (Build.VERSION.SDK_INT >= 23) {
            try {
                d.setPermittedInputMethods(a, null);
                put(g, "permittedInputMethods", "null");
            } catch (Throwable t) { put(g, "permittedInputMethods", "denied"); }
        }

        // 关键应用禁止卸载
        String[] keep = new String[]{ "com.youku.tv", "com.kidguard.tv" };
        for (int i = 0; i < keep.length; i++) {
            try {
                d.setUninstallBlocked(a, keep[i], true);
                put(g, "uninstallBlocked:" + keep[i], true);
            } catch (Throwable t) {
                put(g, "uninstallBlocked:" + keep[i], "fail:" + t.getClass().getSimpleName());
            }
        }

        // 关闭 ADB（仅 Device Owner）
        if (own && Prefs.blockAdb(c)) {
            try {
                d.setGlobalSetting(a, android.provider.Settings.Global.ADB_ENABLED, "0");
                put(g, "adbDisabled", true);
            } catch (Throwable t) {
                put(g, "adbDisabled", "fail:" + t.getClass().getSimpleName());
            }
        } else {
            // 默认保持 ADB 开着：这是家长用电脑救回来的唯一通道，别锁死自己
            if (own) {
                try {
                    d.setGlobalSetting(a, android.provider.Settings.Global.ADB_ENABLED, "1");
                    put(g, "adbDisabled", "no(adb kept on)");
                } catch (Throwable t) {
                    put(g, "adbDisabled", "no(set fail " + t.getClass().getSimpleName() + ")");
                }
            } else {
                put(g, "adbDisabled", "skipped");
            }
        }

        try {
            d.setCameraDisabled(a, true);
            put(g, "cameraDisabled", true);
        } catch (Throwable t) { put(g, "cameraDisabled", false); }

        Prefs.sp(c).edit().putString("grants", g.toString()).apply();
        return g.toString();
    }

    /** 撤销全部策略（卸载/交还设备前调用），并把 ADB 打开。 */
    public static String clearAll(Context c) {
        JSONObject g = new JSONObject();
        DevicePolicyManager d = dpm(c);
        ComponentName a = cn(c);
        if (d == null || (!isOwner(c) && !isAdmin(c))) {
            put(g, "note", "未激活管理员");
            Prefs.sp(c).edit().putString("grants", g.toString()).apply();
            return g.toString();
        }
        String[] policies = new String[]{
                UserManager.DISALLOW_UNINSTALL_APPS, UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_CONFIG_DATE_TIME,
                UserManager.DISALLOW_CREATE_WINDOWS, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES };
        for (int i = 0; i < policies.length; i++) {
            try { d.clearUserRestriction(a, policies[i]); put(g, policies[i], "cleared"); }
            catch (Throwable t) { put(g, policies[i], "fail"); }
        }
        try { d.setStatusBarDisabled(a, false); put(g, "statusBar", "restored"); } catch (Throwable ignored) { }
        try { d.setKeyguardDisabled(a, false); put(g, "keyguard", "restored"); } catch (Throwable ignored) { }
        try { d.setCameraDisabled(a, false); put(g, "camera", "restored"); } catch (Throwable ignored) { }
        try { d.setLockTaskPackages(a, new String[0]); put(g, "lockTask", "cleared"); } catch (Throwable ignored) { }
        try { d.setUninstallBlocked(a, c.getPackageName(), false); } catch (Throwable ignored) { }
        try { d.setUninstallBlocked(a, "com.youku.tv", false); } catch (Throwable ignored) { }
        try {
            d.setGlobalSetting(a, android.provider.Settings.Global.ADB_ENABLED, "1");
            put(g, "adb", "on");
        } catch (Throwable t) { put(g, "adb", "fail"); }
        unsuspendAll(c);
        Prefs.setGuardOn(c, false);
        Prefs.setForceLock(c, false);
        Prefs.setFreeToday(c, true);
        Prefs.sp(c).edit().putString("grants", g.toString()).apply();
        Log.i("clearAll done");
        return g.toString();
    }

    // ---------------- 挂起/恢复 ----------------
    public static boolean suspendSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    /**
     * 计算"锁定时要挂起"的应用集合：APP 黑名单 +（强锁模式下）所有可启动的应用；只排除本包。
     *
     * 注意（2026-10-16 新规则）：**APP 白名单不在这里豁免**。
     * APP 白名单只豁免「内容过滤」（关键字/片名），每日时长限制对它照常生效，
     * 所以时间到了它同样要被挂起 —— 否则夸克就能在锁定时继续看。
     */
    public static List<String> suspendTargets(Context c) {
        List<String> out = new ArrayList<String>();
        String self = c.getPackageName();
        PackageManager pm = c.getPackageManager();

        List<String> bl = Prefs.appBlack(c);
        for (int i = 0; i < bl.size(); i++) addT(out, pm, bl.get(i), self);

        if (Prefs.hardLock(c)) {
            try {
                Intent m = new Intent(Intent.ACTION_MAIN);
                m.addCategory(Intent.CATEGORY_LAUNCHER);
                List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(m, 0);
                for (int i = 0; i < ris.size(); i++) {
                    android.content.pm.ResolveInfo ri = ris.get(i);
                    if (ri.activityInfo == null) continue;
                    addT(out, pm, ri.activityInfo.packageName, self);
                }
                Intent s = new Intent(android.provider.Settings.ACTION_SETTINGS);
                List<android.content.pm.ResolveInfo> sris = pm.queryIntentActivities(s, 0);
                for (int i = 0; i < sris.size(); i++) {
                    if (sris.get(i).activityInfo == null) continue;
                    addT(out, pm, sris.get(i).activityInfo.packageName, self);
                }
            } catch (Throwable t) {
                Log.w("suspendTargets query fail: " + t);
            }
        }
        return out;
    }

    private static void addT(List<String> out, PackageManager pm, String pkg, String self) {
        if (pkg == null || pkg.length() == 0) return;
        if (pkg.equals(self)) return;
        if (pkg.equals("android")) return;
        if (out.contains(pkg)) return;
        try { pm.getPackageInfo(pkg, 0); out.add(pkg); } catch (Throwable ignored) { }
    }

    /**
     * 当前默认桌面（HOME）的包名；取不到返回 null。
     * 用途：`setPackagesSuspended` 不允许挂起当前桌面那个包（真机实测永远进 failed 数组），
     * 状态行要据此把提示写清楚，免得以后误判成故障。
     */
    public static String homePkg(Context c) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(it, 0);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Throwable t) {
            Log.w("homePkg fail: " + t);
        }
        return null;
    }

    /** 挂起当前该挂起的应用（设备所有者）。返回 null 表示成功，否则返回错误说明。 */
    public static String suspendBlocked(Context c) {
        if (!isOwner(c) || !suspendSupported()) return "not-owner-or-api";
        try {
            DevicePolicyManager d = dpm(c);
            List<String> real = suspendTargets(c);
            if (real.isEmpty()) return null;
            String[] arr = real.toArray(new String[0]);
            String[] failed = d.setPackagesSuspended(cn(c), arr, true);
            // 只记「真的挂起成功」的那些，状态行的「已挂起列表」才不会把没挂上的也算进去。
            List<String> okList = new ArrayList<String>(real);
            if (failed != null) {
                for (String f : failed) okList.remove(f);
            }
            Prefs.setList(c, "suspended", okList);
            if (failed != null && failed.length > 0) {
                // 当前桌面这个包系统不允许挂起（真机实测：永远进 failed 数组）。
                // 这不影响封锁效果 —— 锁定期间由 GuardActivity 的「锁定任务模式」把锁屏钉在最前面，
                // 遥控器的【主页】键回不去。所以这里把提示写清楚，免得以后误判成故障。
                String home = homePkg(c);
                StringBuilder sb = new StringBuilder();
                for (String f : failed) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(f);
                    if (f != null && f.equals(home)) sb.append("(当前桌面，系统不允许挂起；已由锁定任务模式兜住)");
                }
                return "partial fail: " + sb;
            }
            Log.i("suspended " + okList.size() + " pkgs");
            return null;
        } catch (Throwable t) {
            Log.e("suspendBlocked fail", t);
            return "fail:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    /**
     * 系统里「到底还有哪些包挂着」—— 不信自己的记账，直接问设备策略/包管理器。
     * `isPackageSuspended(ComponentName, String)` 从 API 24 就有；API 29+ 还能直接问 PackageManager。
     */
    public static List<String> actuallySuspended(Context c) {
        List<String> out = new ArrayList<String>();
        List<ApplicationInfo> all;
        try { all = c.getPackageManager().getInstalledApplications(0); }
        catch (Throwable t) { return out; }
        DevicePolicyManager d = dpm(c);
        ComponentName a = cn(c);
        String self = c.getPackageName();
        for (int i = 0; i < all.size(); i++) {
            String p = all.get(i).packageName;
            if (p == null || p.equals(self)) continue;
            boolean s = false;
            try { s = d != null && d.isPackageSuspended(a, p); } catch (Throwable ignored) { }
            if (!s && Build.VERSION.SDK_INT >= 29) {
                try { s = c.getPackageManager().isPackageSuspended(p); } catch (Throwable ignored) { }
            }
            if (s) out.add(p);
        }
        return out;
    }

    public static void unsuspendAll(Context c) {
        if (!isOwner(c) || !suspendSupported()) return;
        try {
            List<String> l = Prefs.split(Prefs.sp(c).getString("suspended", ""));
            int memo = l.size();
            // 除了记账，还必须问一遍系统：换天/重启/更新 APK 之后记账可能丢，
            // 而包管理器里的 suspended=true 会老老实实留下来 —— 那样桌面点应用全都没反应
            // （实测：已挂起列表为空时 resume 直接空跑，永远救不回来）。
            List<String> real = actuallySuspended(c);
            for (int i = 0; i < real.size(); i++) {
                if (!l.contains(real.get(i))) l.add(real.get(i));
            }
            if (l.isEmpty()) return;
            dpm(c).setPackagesSuspended(cn(c), l.toArray(new String[0]), false);
            Prefs.sp(c).edit().putString("suspended", "").apply();
            Log.i("unsuspended " + l.size() + " pkgs (记账 " + memo + " + 实查 " + real.size() + ")");
        } catch (Throwable t) {
            Log.e("unsuspendAll fail", t);
        }
    }

    public static void lockNow(Context c) {
        try {
            dpm(c).lockNow();
        } catch (Throwable t) {
            Log.w("lockNow fail: " + t);
        }
    }

    // ---------------- 报告 ----------------
    public static String appList(Context c) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo ai = apps.get(i);
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            sb.append(ai.packageName).append('|').append(sys ? "sys" : "usr").append('|')
              .append(String.valueOf(pm.getApplicationLabel(ai))).append('\n');
        }
        return sb.toString();
    }

    public static boolean isInstalled(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Throwable t) { return false; }
    }

    public static String grantsPretty(Context c) {
        String s = Prefs.sp(c).getString("grants", "{}");
        try {
            JSONObject o = new JSONObject(s);
            StringBuilder sb = new StringBuilder();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                sb.append(k).append(" = ").append(o.opt(k)).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return s;
        }
    }
}
