package com.kidguard.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 管控大脑：每秒累计亮屏时长、判定锁定、执行/解除应用挂起。 */
public class GuardService extends Service {

    public static final String ACTION_SYNC = "com.kidguard.tv.SYNC";
    private static final int NOTI_ID = 0x4B47;
    private static final String CH_ID = "kidguard";

    /** 家长界面在前台时，不要抢焦点弹锁屏。 */
    public static volatile boolean parentForeground = false;
    private static volatile long parentAt = 0L;

    /** 家长 PIN 键盘打开时，更不许抢焦点：否则主窗口丢 window focus，遥控器按键全被丢掉。 */
    public static volatile boolean pinPadOpen = false;
    private static volatile long padAt = 0L;

    /**
     * 这两个标记位的保鲜期。App 是常驻进程（它自己就是桌面），万一界面被系统直接干掉、
     * onPause/onDestroy 没跑到，标记位会永久为 true → 锁屏不推、内容守卫静默失效，
     * 而且从外面完全看不出来。所以让它们必须靠心跳续期，过期一律按「家长不在」处理。
     */
    private static final long BUSY_TTL = 6000L;

    public static void setParentForeground(boolean on) {
        parentForeground = on;
        parentAt = System.currentTimeMillis();
    }

    public static void setPinPadOpen(boolean on) {
        pinPadOpen = on;
        padAt = System.currentTimeMillis();
    }

    /** 家长正在操作（家长界面在前台 / PIN 键盘开着），过期的标记位不算。 */
    public static boolean parentBusy() {
        long now = System.currentTimeMillis();
        if (parentForeground && now - parentAt <= BUSY_TTL) return true;
        if (pinPadOpen && now - padAt <= BUSY_TTL) return true;
        return false;
    }

    private Handler h;
    private Runnable tick;
    private int acc = 0;
    private long lastLog = 0L;
    private long lastPush = 0L;
    private long lastHeal = 0L;
    private boolean lockApplied = false;
    private boolean started = false;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, GuardService.class);
            i.setAction(ACTION_SYNC);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.e("GuardService.start fail", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.bind(this);
        try {
            Prefs.tickClock(this);
            Prefs.ensureDefaults(this);
            if (Prefs.isNewDay(this)) Prefs.rollover(this);
            Usage.reconcile(this);
            foreground();
            Admin.applyAll(this);
            // 开机/重启兜底：不在锁定态、也不在违规挂起窗口内，就把系统里残留的挂起全清掉。
            // 不能再要求「记账非空」—— 记账一丢（换天/重启/更新 APK）系统里 suspended=true 还在，
            // 表现就是桌面点任何应用都没反应，而且自己再也救不回来。
            if (!Prefs.isLocked(this) && Prefs.suspendedUntil(this) == 0L) {
                Log.i("startup reconcile: unsuspend all");
                Admin.unsuspendAll(this);
            }
        } catch (Throwable t) {
            Log.e("GuardService onCreate", t);
        }
        h = new Handler(Looper.getMainLooper());
        tick = new Runnable() {
            @Override public void run() {
                try { step(); } catch (Throwable t) { Log.e("GuardService.step", t); }
                if (h != null) h.postDelayed(this, 1000L);
            }
        };
        h.postDelayed(tick, 800L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { foreground(); } catch (Throwable t) { Log.e("onStartCommand", t); }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (h != null && tick != null) h.removeCallbacks(tick);
        } catch (Throwable ignored) { }
        Log.w("GuardService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- 核心循环 ----------------
    private void step() {
        Prefs.tickClock(this);
        if (Prefs.isNewDay(this)) {
            boolean wasApplied = lockApplied;
            Prefs.rollover(this);
            lockApplied = false;
            // 换天时必须「真的解挂」：以前只把 lockApplied 置 false，releaseLock() 就永远不会跑，
            // 昨天锁定留下的挂起会跨天（甚至跨重启）一直生效 —— 表现就是桌面点应用没反应。
            if (wasApplied) releaseLock();
            Log.i("new day, counters reset" + (wasApplied ? " (已解除挂起)" : ""));
        }
        if (Prefs.inGrace(this) && Prefs.forceLock(this)) {
            // 家长宽限期内不强制锁屏
        }
        boolean on = Usage.screenOn(this);
        boolean locked = Prefs.isLocked(this) && Prefs.guardOn(this);

        if (on && !locked) acc += 1000;
        if (acc >= 5000 || (locked && acc > 0)) {
            Usage.fold(this, acc / 1000);
            acc = 0;
        }

        if (locked) {
            if (!lockApplied) {
                lockApplied = true;
                Log.i("LOCK ON  used=" + Prefs.usedToday(this) + "s allowed=" + Prefs.allowedSec(this) + "s");
                applyLock();
            }
            pushLockUi();
        } else if (lockApplied) {
            lockApplied = false;
            Log.i("LOCK OFF used=" + Prefs.usedToday(this) + "s allowed=" + Prefs.allowedSec(this) + "s");
            releaseLock();
        }

        // 违规挂起 60 秒到期后自动恢复（锁定态下不动，以免破坏 applyLock 的挂起）
        if (!locked && Prefs.suspendExpired(this)) {
            Log.i("strike suspend expired, unsuspend all");
            Admin.unsuspendAll(this);
        }

        // 自愈：系统里若还残留着挂起（实测设备策略会在开机时把挂起重新施加回来），
        // 每 30 秒清一次，免得又变成「桌面点应用没反应、还查不出原因」。
        if (!locked && !lockApplied && Prefs.suspendedUntil(this) == 0L
                && System.currentTimeMillis() - lastHeal > 30000L) {
            lastHeal = System.currentTimeMillis();
            Admin.unsuspendAll(this);
        }

        if (System.currentTimeMillis() - lastLog > 60000L) {
            lastLog = System.currentTimeMillis();
            Log.i("hb used=" + Prefs.usedToday(this) + "s/" + Prefs.allowedSec(this)
                    + "s lock=" + locked + " scr=" + on + " owner=" + Admin.isOwner(this));
        }
    }

    private void applyLock() {
        String err = null;
        if (Prefs.enforceApps(this)) {
            err = Admin.suspendBlocked(this);
        }
        Prefs.sp(this).edit().putString("lastLockErr", err == null ? "" : err).apply();
        notifyLock(true, err);
        pushLockUiNow();
    }

    private void releaseLock() {
        Admin.unsuspendAll(this);
        notifyLock(false, null);
    }

    /** 把管控锁屏推到前台（家长界面在前台时不动）。最多每 4 秒推一次，避免抢焦点抢得太凶。 */
    private void pushLockUi() {
        if (parentBusy()) return;
        if (Prefs.inGrace(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastPush < 4000L) return;
        lastPush = now;
        try {
            Intent i = new Intent(this, GuardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            i.putExtra("lock", true);
            startActivity(i);
        } catch (Throwable t) {
            Log.w("pushLockUi fail: " + t);
        }
    }

    /** 立即推一次（锁定瞬间用，不受节流限制）。 */
    private void pushLockUiNow() {
        lastPush = 0L;
        pushLockUi();
    }

    // ---------------- 前台通知 ----------------
    private void foreground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "电视管控", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, GuardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_ID);
            else b = new Notification.Builder(this);
            b.setContentTitle("电视管控运行中")
             .setContentText("今日已用 " + (Prefs.usedToday(this) / 60) + " 分钟 / 上限 " + Prefs.dailyMin(this) + " 分钟")
             .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
             .setContentIntent(pi)
             .setOngoing(true);
            startForeground(NOTI_ID, b.build());
            started = true;
        } catch (Throwable t) {
            Log.e("foreground fail", t);
        }
    }

    private void notifyLock(boolean locked, String err) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "电视管控", NotificationManager.IMPORTANCE_MIN);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, GuardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_ID);
            else b = new Notification.Builder(this);
            String txt = locked
                    ? ("已锁定，今日用量 " + (Prefs.usedToday(this) / 60) + " 分钟"
                        + (err == null || err.isEmpty() ? "（应用已挂起）" : "（挂起失败:" + err + "）"))
                    : ("运行中，今日已用 " + (Prefs.usedToday(this) / 60) + " 分钟 / 上限 " + Prefs.dailyMin(this) + " 分钟");
            b.setContentTitle(locked ? "电视时间已用完" : "电视管控运行中")
             .setContentText(txt)
             .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
             .setContentIntent(pi)
             .setOngoing(!locked);
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable t) {
            Log.w("notifyLock fail: " + t);
        }
    }
}
