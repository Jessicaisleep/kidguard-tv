package com.kidguard.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 全部持久化配置集中在此，任何模块只通过它读写。 */
public final class Prefs {

    public static final String SP = "kidguard";

    // 默认值
    public static final int    DEF_DAILY_MIN   = 60;
    public static final int    DEF_ADD_STEP    = 30;
    /**
     * 方向键防抖窗口（毫秒）。同一个方向键这么短时间内又按下 → 只算一格。
     * 真机反馈（m01824）：B站/优酷 的搜索键盘和选集条「一下滑很多」，一次按下走好几格。
     * 300ms 大约是「一秒最多走 3 格」，也比遥控器最长的一次「点按」还长一点，
     * 所以正常点一下必定只走一格；真按住不放才会匀速走。设 0 就完全关掉。
     */
    public static final int    DEF_DIR_MIN     = 300;
    /**
     * 触发黑名单后盖「已屏蔽」黑幕的时长（秒），家长端可调 1~10（默认 10）。
     * 家长反馈（m02430）：不同家庭对这个「挡多久」的容忍度不一样——太长孩子干等着，
     * 太短又可能出现「刚放开又自己播起来」的轮空。所以交给家长面板调，默认仍是 10 秒。
     */
    public static final int    DEF_COVER_SEC   = 10;
    public static final String DEF_BL_KEYWORDS = "熊出没,小猪佩奇,汪汪队,奥特曼,我的世界,迷你世界,喜羊羊,奶龙,汪汪队立大功,植物大战僵尸";
    /** 片名名单：装完整片名（如《熊出没之怪兽计划》）。与关键字名单是「任一命中就拦」的关系。 */
    public static final String DEF_BL_TITLES   = "";
    public static final String DEF_BL_PACKAGES = "com.youku.tv,com.tmall.cm.video,tv.douyin,com.duowan.kiwi,com.qiyi.video,com.tencent.qqlive";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 首次运行写入默认值；返回 true 表示是首次初始化。 */
    public static boolean ensureDefaults(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean("inited", false)) {
            // 老版本升级时补新键
            SharedPreferences.Editor e = p.edit();
            if (!p.contains("grants")) e.putString("grants", "{}");
            if (!p.contains("hardLock")) e.putBoolean("hardLock", true);
            if (!p.contains("blTitles")) e.putString("blTitles", DEF_BL_TITLES);
            if (!p.contains("blPlayBlock")) e.putBoolean("blPlayBlock", true);
            e.apply();
            return false;
        }
        p.edit()
                .putBoolean("inited", true)
                .putInt("dailyMin", DEF_DAILY_MIN)
                .putInt("addStep", DEF_ADD_STEP)
                .putInt("usedToday", 0)
                .putInt("extraToday", 0)
                .putString("day", today())
                .putBoolean("freeToday", false)
                .putBoolean("forceLock", false)
                .putBoolean("started", false)
                .putBoolean("guardOn", true)
                .putBoolean("enforceApps", true)
                .putBoolean("blockAdb", false)
                .putBoolean("blEnable", true)
                .putBoolean("hardLock", true)
                .putString("blKeywords", DEF_BL_KEYWORDS)
                .putString("blTitles", DEF_BL_TITLES)
                .putString("blPackages", DEF_BL_PACKAGES)
                .putString("whitelist", "")
                .putString("apps", "")
                .putString("grants", "{}")
                .putString("pin", "")
                .putInt("strikes", 0)
                .putLong("strikeWin", 0L)
                .putLong("suspendedUntil", 0L)
                .apply();
        return true;
    }

    /**
     * 一天的起点是凌晨 4 点，不是 0 点。
     * 原因：若按 0 点换天，孩子 23:50 开始看，一过 0 点额度就满血复活，等于奖励熬夜。
     * 采用社区已验证的做法（TV Sitter 在真机上跑过后定的 04:00 边界）。
     */
    public static final int DAY_START_HOUR = 4;

    // ---------------- 时间防作弊 ----------------
    /** 墙钟与单调时钟允许的偏差（NTP 正常微调也就几秒）。 */
    private static final long CLOCK_TOL = 120000L;
    /** 时钟锚点落盘间隔与日志节流（用单调时钟计时，避免被墙钟带偏）。 */
    private static final long CLOCK_SAVE_MS = 5000L;
    private static final long CLOCK_LOG_MS = 60000L;

    /** 校准过的「当前时间」。0 = 还没校准过（退回墙钟）。 */
    private static volatile long effNow = 0L;
    /** 最近一次时钟检查的结论（null = 墙钟可信）；给 `status` 用，让家长能一眼看出时间被拨过。 */
    private static volatile String lastNote = null;
    private static long lastClockSaveEl = 0L;
    private static long lastClockLogEl = 0L;

    /**
     * 时间防作弊核心：算出**可信的当前时间**。
     *
     * 墙钟（`System.currentTimeMillis`）孩子改得动，单调时钟（`SystemClock.elapsedRealtime`）
     * 改不动、但设备重启会归零，所以两个配合：
     *   · 进程没重启过（`elapsedRealtime` 没有倒退）：只认「上次锚点 + 真实流逝的时间」，
     *     墙钟跟它差超过 2 分钟就一律不认——**往回拨**（想提前换天）和**往前拨**（想跳到明天）都拦。
     *   · 设备重启过：单调时钟不可比，退回墙钟并重新落锚点（重启要动手拔插头，不是孩子随手能干的）。
     * 由 `GuardService.step()` 每秒调一次。
     */
    public static void tickClock(Context c) {
        long wall = System.currentTimeMillis();
        long el = SystemClock.elapsedRealtime();
        SharedPreferences p = sp(c);
        long lw = p.getLong("clockWall", 0L);
        long le = p.getLong("clockElapsed", -1L);
        long now = wall;
        String note = null;
        if (lw > 0L && le >= 0L && el >= le) {
            long mono = lw + (el - le);
            long drift = wall - mono;
            if (drift < -CLOCK_TOL) {
                now = mono;
                note = "墙钟被往回拨 " + (Math.abs(drift) / 60000L) + " 分钟";
            } else if (drift > CLOCK_TOL) {
                now = mono;
                note = "墙钟被往前拨 " + (Math.abs(drift) / 60000L) + " 分钟";
            }
        } else if (lw > 0L && le >= 0L) {
            note = "设备重启过，重新对时";
        }
        effNow = now;
        lastNote = note;
        if (note != null && el - lastClockLogEl > CLOCK_LOG_MS) {
            lastClockLogEl = el;
            Log.w("CLOCK " + note + "，换天仍按 " + today(now) + " 算");
        }
        if (el - lastClockSaveEl > CLOCK_SAVE_MS) {
            lastClockSaveEl = el;
            p.edit().putLong("clockWall", now).putLong("clockElapsed", el).apply();
        }
    }

    /** 可信的当前时间（毫秒）。 */
    public static long now() {
        return effNow > 0L ? effNow : System.currentTimeMillis();
    }

    /** 最近一次时钟检查的结论（null = 墙钟可信、与运行时长一致）。 */
    public static String clockNote() { return lastNote; }

    public static long dayStartMillis() {
        return dayStartMillis(now());
    }

    /** 返回 now 所处的「逻辑日」起点（最近的凌晨 4 点）的毫秒时间戳。 */
    public static long dayStartMillis(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, DAY_START_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() > now) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.getTimeInMillis();
    }

    /** 逻辑日标识（形如 2026-10-03），用于判断是否跨天。 */
    public static String today() {
        return today(now());
    }

    public static String today(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStartMillis(now));
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean isNewDay(Context c) {
        return !today().equals(sp(c).getString("day", ""));
    }

    /** 跨天翻转：把 usedToday 归零并清掉当天加时与强制锁。 */
    public static void rollover(Context c) {
        sp(c).edit()
                .putString("day", today())
                .putInt("usedToday", 0)
                .putInt("extraToday", 0)
                .putBoolean("freeToday", false)
                .putBoolean("forceLock", false)
                .putBoolean("zeroedToday", false)
                .apply();
    }

    // ---------- 时长 ----------
    public static int dailyMin(Context c)   { return sp(c).getInt("dailyMin", DEF_DAILY_MIN); }
    public static void setDailyMin(Context c, int v) { sp(c).edit().putInt("dailyMin", Math.max(1, v)).apply(); }

    /** 方向键防抖窗口（毫秒），0 = 关掉。 */
    public static int dirMinMs(Context c)   { return Math.max(0, Math.min(2000, sp(c).getInt("dirMin", DEF_DIR_MIN))); }
    public static void setDirMinMs(Context c, int v) { sp(c).edit().putInt("dirMin", Math.max(0, Math.min(2000, v))).apply(); }

    /** 命中黑名单后「已屏蔽」黑幕的秒数（1~10，默认 10）。家长面板可调。 */
    public static int coverSec(Context c)   { return Math.max(1, Math.min(10, sp(c).getInt("coverSec", DEF_COVER_SEC))); }
    public static void setCoverSec(Context c, int v) { sp(c).edit().putInt("coverSec", Math.max(1, Math.min(10, v))).apply(); }

    public static int addStep(Context c)    { return sp(c).getInt("addStep", DEF_ADD_STEP); }
    public static void setAddStep(Context c, int v)  { sp(c).edit().putInt("addStep", Math.max(1, v)).apply(); }

    public static int usedToday(Context c)  { return sp(c).getInt("usedToday", 0); }
    public static void addUsed(Context c, int sec) { sp(c).edit().putInt("usedToday", usedToday(c) + sec).apply(); }
    public static void setUsedToday(Context c, int sec) { sp(c).edit().putInt("usedToday", Math.max(0, sec)).apply(); }

    /**
     * 家长今天亲手「今日清零」过。
     * 置真之后 {@link Usage#reconcile} 不再用系统统计把时长对账回来——
     * 否则家长一点清零，服务一重启就被系统 UsageStats 的旧值 undo（实测过的真 bug）。
     * 孩子在电视上按不出这个（要 PIN），所以不担心被当成后门。
     */
    public static boolean zeroedToday(Context c) { return sp(c).getBoolean("zeroedToday", false); }
    public static void setZeroedToday(Context c, boolean v) { sp(c).edit().putBoolean("zeroedToday", v).apply(); }

    public static int extraToday(Context c) { return sp(c).getInt("extraToday", 0); }
    public static void addExtra(Context c, int min) { sp(c).edit().putInt("extraToday", Math.max(0, extraToday(c) + min)).apply(); }

    public static boolean freeToday(Context c) { return sp(c).getBoolean("freeToday", false); }
    public static void setFreeToday(Context c, boolean v) { sp(c).edit().putBoolean("freeToday", v).apply(); }

    public static boolean forceLock(Context c) { return sp(c).getBoolean("forceLock", false); }
    public static void setForceLock(Context c, boolean v) { sp(c).edit().putBoolean("forceLock", v).apply(); }

    public static int allowedSec(Context c) { return (dailyMin(c) + extraToday(c)) * 60; }
    public static int remainSec(Context c)  { return Math.max(0, allowedSec(c) - usedToday(c)); }

    /** 是否已锁定（唯一判定入口）。 */
    public static boolean isLocked(Context c) {
        if (freeToday(c)) return false;
        if (forceLock(c)) return true;
        return usedToday(c) >= allowedSec(c);
    }

    // ---------- 开关 ----------
    public static boolean guardOn(Context c)     { return sp(c).getBoolean("guardOn", true); }
    public static void setGuardOn(Context c, boolean v) { sp(c).edit().putBoolean("guardOn", v).apply(); }

    public static boolean enforceApps(Context c) { return sp(c).getBoolean("enforceApps", true); }
    public static void setEnforceApps(Context c, boolean v) { sp(c).edit().putBoolean("enforceApps", v).apply(); }

    public static boolean blockAdb(Context c)    { return sp(c).getBoolean("blockAdb", true); }
    public static void setBlockAdb(Context c, boolean v) { sp(c).edit().putBoolean("blockAdb", v).apply(); }

    public static boolean isStarted(Context c)   { return sp(c).getBoolean("started", false); }
    public static void setStarted(Context c, boolean v) { sp(c).edit().putBoolean("started", v).apply(); }

    // ---------- 家长 PIN ----------
    public static String pin(Context c) { return sp(c).getString("pin", ""); }
    public static boolean hasPin(Context c) { return pin(c).length() >= 4; }
    public static void setPin(Context c, String p) { sp(c).edit().putString("pin", p == null ? "" : p.trim()).apply(); }
    public static boolean checkPin(Context c, String p) {
        if (!hasPin(c)) return true;                 // 未设 PIN 时不做鉴权（部署阶段）
        return pin(c).equals(p == null ? "" : p.trim());
    }

    // ---------- 黑名单 ----------
    public static boolean blEnable(Context c) { return sp(c).getBoolean("blEnable", true); }
    public static void setBlEnable(Context c, boolean v) { sp(c).edit().putBoolean("blEnable", v).apply(); }

    /** 播放页兜底：内容真的播起来了（有进度条/画面层 + 正在出声 + 标题命中）才强制退出。 */
    public static boolean blPlayBlock(Context c) { return sp(c).getBoolean("blPlayBlock", true); }
    public static void setBlPlayBlock(Context c, boolean v) { sp(c).edit().putBoolean("blPlayBlock", v).apply(); }

    /**
     * 预览严格模式（默认关）：读不到片名也算命中。
     * 有些片子/剧集的标题本身就是一张图（艺术字海报），读屏永远读不出文字，
     * 黑名单按名字匹配就一定会漏。开了这个开关以后，只要
     * 「孩子没按确定 + 画面上有视频层 + 正在出声」= 焦点停靠自动预览 → 一律切掉；
     * 孩子自己按确定挑的（按过确定后的宽限时间内）不受影响。
     */
    public static boolean strictPreview(Context c) { return sp(c).getBoolean("strictPreview", false); }
    public static void setStrictPreview(Context c, boolean v) { sp(c).edit().putBoolean("strictPreview", v).apply(); }

    /**
     * 诊断日志开关（verbose，默认关）。
     * 打开后无障碍服务会打 EVT（每次事件一行，含 e.getSource()）这类高频日志。
     * 关掉的理由（2026-10-10 定案）：EVT 里的 e.getSource() 是一次 IPC，
     * 在主线程上做会拖慢无障碍按键过滤回调 → App 把「点按」误判成「按住」自己连跳。
     * 所以默认关；排查时才用 `verbose 1` 打开、`verbose 0` 关掉。
     */
    public static boolean verbose(Context c) { return sp(c).getBoolean("verbose", false); }
    public static void setVerbose(Context c, boolean v) { sp(c).edit().putBoolean("verbose", v).apply(); }

    /** 强锁模式：锁定时把「所有能启动的应用」都挂起（本包与白名单除外）。 */
    public static boolean hardLock(Context c) { return sp(c).getBoolean("hardLock", true); }
    public static void setHardLock(Context c, boolean v) { sp(c).edit().putBoolean("hardLock", v).apply(); }

    // ---------- 家长临时放行（在电视上按【菜单键】输 PIN 允许播放某 App） ----------

    /** 家长放行某个包的截止时刻（毫秒）；<=0 = 没放行过。 */
    private static long allowUntil(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return 0L;
        return sp(c).getLong("allow_" + pkg, 0L);
    }

    /** 这个包现在是不是被家长放行了（放行期内完全不管它）。 */
    public static boolean isAllowed(Context c, String pkg) {
        long t = allowUntil(c, pkg);
        return t > 0L && System.currentTimeMillis() < t;
    }

    /** 放行到指定时刻。 */
    public static void setAllowUntil(Context c, String pkg, long until) {
        if (pkg == null || pkg.length() == 0) return;
        sp(c).edit().putLong("allow_" + pkg, until).apply();
    }

    /** 立刻取消放行。 */
    public static void clearAllow(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return;
        sp(c).edit().remove("allow_" + pkg).apply();
    }

    /** 取消所有临时放行（家长在 PC 端「取消全部放行」用）。 */
    public static void clearAllAllow(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        Map<String, ?> all = sp(c).getAll();
        for (Map.Entry<String, ?> en : all.entrySet()) {
            String k = en.getKey();
            if (k != null && k.startsWith("allow_")) e.remove(k);
        }
        e.apply();
    }

    public static List<String> blKeywords(Context c) {
        return split(sp(c).getString("blKeywords", DEF_BL_KEYWORDS));
    }
    public static List<String> blTitles(Context c) {
        return split(sp(c).getString("blTitles", DEF_BL_TITLES));
    }
    public static List<String> blPackages(Context c) {
        return split(sp(c).getString("blPackages", DEF_BL_PACKAGES));
    }

    /**
     * 两个名单任一命中即返回（关键字优先）。返回 {词条, 名单名}，没命中返回 null。
     * 关键字 = 模糊包含（「熊出没」能命中「熊出没之怪兽计划」）；
     * 片名   = 完整片名（写全名更准，适合「喜羊羊与灰太狼」这种）。
     */
    public static String[] matchBlacklist(Context c, String text) {
        String n = norm(text);
        if (n.isEmpty()) return null;
        List<String> kw = blKeywords(c);
        for (int i = 0; i < kw.size(); i++) {
            String k = norm(kw.get(i));
            if (!k.isEmpty() && n.contains(k)) return new String[]{ kw.get(i), "关键字" };
        }
        List<String> ti = blTitles(c);
        for (int i = 0; i < ti.size(); i++) {
            String k = norm(ti.get(i));
            if (!k.isEmpty() && n.contains(k)) return new String[]{ ti.get(i), "片名" };
        }
        return null;
    }
    public static List<String> whitelist(Context c) {
        return split(sp(c).getString("whitelist", ""));
    }
    /** 白名单非空时，只允许里面的包；否则用黑名单。 */
    public static boolean whitelistMode(Context c) { return !whitelist(c).isEmpty(); }

    public static boolean isPackageBlocked(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(c.getPackageName())) return false;
        if (whitelistMode(c)) return !whitelist(c).contains(pkg);
        return blPackages(c).contains(pkg);
    }

    public static void addList(Context c, String key, String val) {
        if (val == null || val.trim().isEmpty()) return;
        List<String> l = split(sp(c).getString(key, ""));
        String v = val.trim();
        if (!l.contains(v)) l.add(v);
        sp(c).edit().putString(key, join(l)).apply();
    }

    public static void delList(Context c, String key, String val) {
        List<String> l = split(sp(c).getString(key, ""));
        l.remove(val == null ? "" : val.trim());
        sp(c).edit().putString(key, join(l)).apply();
    }

    public static void setList(Context c, String key, List<String> l) {
        sp(c).edit().putString(key, join(l)).apply();
    }

    // ---------- 加时豁免（家长本次操作不算违规） ----------
    public static long graceUntil(Context c) { return sp(c).getLong("graceUntil", 0L); }
    public static void grantGrace(Context c, long ms) { sp(c).edit().putLong("graceUntil", System.currentTimeMillis() + ms).apply(); }
    public static boolean inGrace(Context c) { return System.currentTimeMillis() < graceUntil(c); }

    // ---------- 违规计数 / 临时挂起 ----------
    public static int strikes(Context c) { return sp(c).getInt("strikes", 0); }
    public static long strikeWin(Context c) { return sp(c).getLong("strikeWin", 0L); }
    public static long suspendedUntil(Context c) { return sp(c).getLong("suspendedUntil", 0L); }

    /**
     * 记录一次违规。规则：60 秒窗口内累计 3 次则返回 true（要求调用方挂起该应用 60 秒）。
     * 计数在窗口过期后自动归零。
     */
    public static boolean strike(Context c) {
        long now = System.currentTimeMillis();
        SharedPreferences p = sp(c);
        long win = p.getLong("strikeWin", 0L);
        int n = p.getInt("strikes", 0);
        if (now - win > 60000L) { win = now; n = 0; }
        n++;
        boolean over = n >= 3;
        SharedPreferences.Editor e = p.edit().putLong("strikeWin", win).putInt("strikes", over ? 0 : n);
        if (over) e.putLong("suspendedUntil", now + 60000L);
        e.apply();
        return over;
    }

    /** 违规挂起是否已到期；到期则清除标记并返回 true（由 GuardService 周期调用）。 */
    public static boolean suspendExpired(Context c) {
        long until = suspendedUntil(c);
        if (until <= 0L) return false;
        if (System.currentTimeMillis() < until) return false;
        sp(c).edit().putLong("suspendedUntil", 0L).apply();
        return true;
    }

    public static void clearSuspended(Context c) {
        sp(c).edit().putLong("suspendedUntil", 0L).apply();
    }

    // ---------- 工具 ----------
    public static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] parts = s.split("[,\\n;、]");
        for (String x : parts) {
            String v = x.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    public static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(l.get(i).trim());
        }
        return sb.toString();
    }

    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }
}
