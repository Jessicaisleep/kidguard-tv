package com.kidguard.tv;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一日志：logcat + /sdcard/kidguard/kidguard.log（部署脚本可直接 cat）。
 *
 * ★ 2026-10-10 真机定案：原来的实现是**同步** FileWriter 写盘（每次 open/write/close），
 *   而 GuardAccessibilityService.onKeyEvent 每个按键都要写两行 —— /sdcard 是 FUSE，
 *   写盘一慢就会把**无障碍按键过滤回调**一起卡住：App 收到按键的时间被推迟 0.3~1 秒，
 *   于是 App 把「点按」认成「按住」，自己触发长按连跳 → 用户看到的「一下滑很多 / 封面选集乱滑」。
 *   （A/B 实测：我们的服务开着 App 可见按住 1337/998/731ms；关掉就是 829/881/849ms = 物理时长。）
 *
 * 现在：调用方只做入队（微秒级，**绝不碰磁盘**），真正的写盘交给一条后台线程按顺序做。
 * 队列满就丢最旧的一条：宁可丢日志，也绝不阻塞按键。
 */
public final class Log {

    public static final String TAG = "KidGuard";
    /** 单行时间戳。只在写入线程里用（SimpleDateFormat 不是线程安全的）。 */
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    /** 队列长度：2 秒内正常写不完的日志量已经说明在刷屏，丢掉是安全的。 */
    private static final int QUEUE = 2048;
    /** 单文件上限，超过就滚到 kidguard.old.log。 */
    private static final long MAX_BYTES = 512L * 1024L;

    private static volatile File file;
    private static final ArrayBlockingQueue<String> Q = new ArrayBlockingQueue<String>(QUEUE);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile long dropped = 0L;

    private Log() {}

    public static void bind(Context c) {
        try {
            File dir = new File("/sdcard/kidguard");
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, "kidguard.log");
        } catch (Throwable t) {
            file = null;
        }
    }

    public static void i(String s) { android.util.Log.i(TAG, s); write("I", s, null); }
    public static void w(String s) { android.util.Log.w(TAG, s); write("W", s, null); }
    public static void e(String s) { android.util.Log.e(TAG, s); write("E", s, null); }
    public static void e(String s, Throwable t) {
        android.util.Log.e(TAG, s, t);
        write("E", s + " :: " + (t == null ? "" : t.toString()), t);
    }

    /** 调用方做的事：拼一行字符串 + 入队。不含任何 I/O。 */
    private static void write(String lvl, String s, Throwable t) {
        if (file == null) return;
        String line;
        try {
            line = lvl + " " + s + (t == null ? "" : "\n    " + android.util.Log.getStackTraceString(t));
        } catch (Throwable ignored) {
            line = lvl + " " + s;
        }
        if (!Q.offer(line)) {
            Q.poll();
            dropped++;
            if (!Q.offer(line)) return;
        }
        start();
    }

    private static void start() {
        if (STARTED.getAndSet(true)) return;
        try {
            Thread th = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "kg-log");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            // 起不了线程就退回 logcat（上面已经打过）
        }
    }

    /** 写入线程：阻塞取行 → 写盘 → 队列空了就 flush（这样部署脚本 cat 到的是最新内容）。 */
    private static void loop() {
        FileWriter w = null;
        long lastWarn = 0L;
        while (true) {
            try {
                String body = Q.take();
                File f = file;
                if (f == null) { Q.clear(); continue; }
                if (w == null) {
                    w = new FileWriter(f, true);
                    long nowW = System.currentTimeMillis();
                    if (dropped > 0 && nowW - lastWarn > 60000L) {
                        lastWarn = nowW;
                        w.write("W 日志队列积压，已丢弃 " + dropped + " 行\n");
                        dropped = 0L;
                    }
                }
                w.write(FMT.format(new Date()) + " " + body + "\n");
                if (Q.isEmpty()) w.flush();
                if (f.length() > MAX_BYTES) {
                    try { w.close(); } catch (Throwable ignored) { }
                    w = null;
                    File old = new File(f.getParentFile(), "kidguard.old.log");
                    if (old.exists()) old.delete();
                    if (!f.renameTo(old)) {
                        // 改名失败（少见）：直接清空继续写，别让日志把磁盘吃满
                        new FileWriter(f, false).close();
                    }
                    file = new File(f.getParentFile(), "kidguard.log");
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try { if (w != null) w.close(); } catch (Throwable ignored) { }
                w = null;
                try { Thread.sleep(200L); } catch (InterruptedException ie) { return; }
            }
        }
    }
}
