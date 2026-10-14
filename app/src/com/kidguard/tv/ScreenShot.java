package com.kidguard.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 屏幕抓帧（MediaProjection 最小实现）。
 *
 * <p>为什么需要它：酷喵/云视听 的很多片名是「画在画面上」的艺术字或海报图，
 * 无障碍读屏永远读不到 → 只靠文字匹配一定会漏。想走「截图 + OCR」就得先能持续抓屏。
 *
 * <p>Android 9 上 MediaProjection 默认要弹一次系统授权框，但 AOSP 的
 * {@code MediaProjectionPermissionActivity} 开头就是查 appop {@code PROJECT_MEDIA}，
 * 查到 {@code allow} 会**直接返回授权、不弹窗**。所以部署时跑一次：
 * <pre>adb shell appops set com.kidguard.tv PROJECT_MEDIA allow</pre>
 * （这条会写进 /data/system/appops.xml，重启也在；实测真机 Android 9 有效。
 *  没做过这一步的话，@link ShotActivity 会弹出系统的「开始录屏/投屏」确认框，
 *  需要家长用遥控器点一次「允许」。）
 *
 * <p>抓到的图同时写两处：/sdcard/kidguard/shot.png（adb pull 直接拿）
 * 和 App 私有外置目录（没给存储权限时的兜底）。
 */
public final class ScreenShot {

    /** startActivityForResult 的请求码。 */
    public static final int REQ = 0x5C01;

    private static MediaProjection proj;
    private static VirtualDisplay vd;
    private static ImageReader reader;

    private ScreenShot() {}

    /** 抓帧完成回调：成功给图片路径，失败给原因。 */
    public interface Done { void run(String pathOrErr); }

    /**
     * 直接 exec 系统自带的 {@code /system/bin/screencap} 抓一屏——**不需要任何授权弹窗**。
     *
     * <p>这条路在大部分正规 ROM 上会被 SELinux + SurfaceFlinger 的 READ_FRAME_BUFFER 检查挡住；
     * 但很多电视盒子 ROM 给 /system/bin/screencap 开了 755、SELinux 又是 Permissive，
     * 于是普通 App 直接 exec 就能抓到（实测小米盒子 MiTV-ASTP0 / Android 9 可以）。
     * 先用它，失败再退回 MediaProjection。
     */
    public static String tryExe(String path) {
        File f = new File(path);
        try { if (f.exists()) f.delete(); } catch (Throwable t) { }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/screencap", "-p", path);
            pb.redirectErrorStream(true);
            p = pb.start();
            long deadline = System.currentTimeMillis() + 4000L;
            int code = Integer.MIN_VALUE;
            while (System.currentTimeMillis() < deadline) {
                try { code = p.exitValue(); break; } catch (IllegalThreadStateException e) { }
                try { Thread.sleep(50L); } catch (Throwable t) { }
            }
            String out = drain(p);
            if (f.exists() && f.length() > 0) {
                return "OK " + path + " " + f.length() + "字节（screencap 直抓，无需授权）";
            }
            return "screencap 没抓到 code=" + code + " out=" + out;
        } catch (Throwable t) {
            return "screencap 起不来: " + t;
        } finally {
            try { if (p != null) p.destroy(); } catch (Throwable t) { }
        }
    }

    /** 把子进程输出读干净（最多 400 字符），避免它写满管道卡住。 */
    private static String drain(Process p) {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[256];
            long deadline = System.currentTimeMillis() + 800L;
            while (System.currentTimeMillis() < deadline && sb.length() < 400) {
                int n = in.available();
                if (n <= 0) { try { Thread.sleep(30L); } catch (Throwable t) { } continue; }
                int r = in.read(buf, 0, Math.min(n, buf.length));
                if (r <= 0) break;
                sb.append(new String(buf, 0, r, "UTF-8"));
            }
        } catch (Throwable t) { }
        return sb.toString().replace('\n', ' ').trim();
    }

    /** 系统有没有 MediaProjection 服务（电视上一般都有）。 */
    public static boolean available(Activity a) {
        try {
            return a.getSystemService(Context.MEDIA_PROJECTION_SERVICE) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求授权（appops 已 allow 时这一下不会弹窗，立刻回 RESULT_OK）。 */
    public static void request(Activity a) {
        MediaProjectionManager mgr = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) throw new IllegalStateException("没有 MEDIA_PROJECTION_SERVICE");
        Intent i = mgr.createScreenCaptureIntent();
        a.startActivityForResult(i, REQ);
    }

    /** 在 onActivityResult 里调：拿到授权 → 建虚拟屏 → 抢一帧 → 存 PNG。 */
    public static void grab(final Activity a, int resultCode, Intent data, final Done cb) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.run("授权被拒（resultCode=" + resultCode + "）；先跑 adb shell appops set com.kidguard.tv PROJECT_MEDIA allow");
            return;
        }
        if (Build.VERSION.SDK_INT < 21) {
            cb.run("系统版本太低（<5.0），没有 MediaProjection");
            return;
        }
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            proj = mgr.getMediaProjection(resultCode, data);
            if (proj == null) { cb.run("getMediaProjection 返回 null"); return; }
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            final int w = dm.widthPixels;
            final int h = dm.heightPixels;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = proj.createVirtualDisplay("kidguard-shot", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            final Handler hd = new Handler(Looper.getMainLooper());
            final int[] tries = new int[]{ 0 };
            hd.postDelayed(new Runnable() {
                @Override public void run() {
                    Image img = null;
                    try { img = reader.acquireLatestImage(); } catch (Throwable t) { }
                    if (img != null) {
                        String r = save(a, img);
                        stop();
                        cb.run(r);
                        return;
                    }
                    if (++tries[0] > 25) {   // 约 2.5 秒还没帧：多半被系统挡了 / 屏是黑的
                        stop();
                        cb.run("抓帧超时（2.5 秒内没有帧到来）");
                        return;
                    }
                    hd.postDelayed(this, 100);
                }
            }, 200);
        } catch (Throwable t) {
            stop();
            cb.run("抓帧异常: " + t);
        }
    }

    private static String save(Activity a, Image img) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pix = p.getPixelStride();
            int row = p.getRowStride();
            int w = img.getWidth();
            int h = img.getHeight();
            int pad = row - pix * w;
            int bw = w + (pix > 0 ? pad / pix : 0);
            Bitmap full = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            Bitmap cut = bw == w ? full : Bitmap.createBitmap(full, 0, 0, w, h);
            byte[] png = null;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                cut.compress(Bitmap.CompressFormat.PNG, 100, bo);
                png = bo.toByteArray();
            } catch (Throwable t) { }
            String went = null;
            // 1) /sdcard/kidguard/shot.png（adb pull 最好拿的那份）
            try {
                File dir = new File("/sdcard/kidguard");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "shot.png");
                FileOutputStream fos = new FileOutputStream(f, false);
                if (png != null) fos.write(png);
                else cut.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush(); fos.close();
                went = f.getAbsolutePath();
            } catch (Throwable t) { }
            // 2) App 私有外置目录兜底
            try {
                File dir2 = a.getExternalFilesDir(null);
                if (dir2 != null) {
                    File f2 = new File(dir2, "shot.png");
                    FileOutputStream fos2 = new FileOutputStream(f2, false);
                    if (png != null) fos2.write(png);
                    else cut.compress(Bitmap.CompressFormat.PNG, 100, fos2);
                    fos2.flush(); fos2.close();
                    if (went == null) went = f2.getAbsolutePath();
                }
            } catch (Throwable t) { }
            return went == null ? ("抓到了帧但写文件失败（" + w + "x" + h + "）") : ("OK " + went + " " + w + "x" + h);
        } catch (Throwable t) {
            return "保存失败: " + t;
        } finally {
            try { img.close(); } catch (Throwable t) { }
        }
    }

    private static void stop() {
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        vd = null;
        try { if (reader != null) reader.close(); } catch (Throwable t) { }
        reader = null;
        try { if (proj != null) proj.stop(); } catch (Throwable t) { }
        proj = null;
    }
}
