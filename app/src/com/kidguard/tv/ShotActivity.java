package com.kidguard.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 只为「抓一帧」而生的工具 Activity：没有界面，拿到 MediaProjection 授权就抓图，写完结果自己结束。
 *
 * <p>起它的两种方式：广播 {@code shot}（CommandReceiver 里 startActivity），
 * 或 {@code adb shell am start -n com.kidguard.tv/.ShotActivity}。
 *
 * <p>结果写在 {@code /sdcard/kidguard/shot.txt}（一行），图片在 {@code /sdcard/kidguard/shot.png}。
 * 如果没做过 {@code appops set com.kidguard.tv PROJECT_MEDIA allow}，
 * 这里会看到系统的「开始投屏/录制」确认框，家长点一次「允许」也能过。
 */
public class ShotActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.bind(this);
        Log.i("SHOT 请求抓帧（appops PROJECT_MEDIA 已 allow 时不会弹系统确认框）");
        // 先试不需要授权的 screencap 直抓；通了就不用 MediaProjection 了
        String exe = ScreenShot.tryExe("/sdcard/kidguard/shot.png");
        Log.i("SHOT screencap 直抓 -> " + exe);
        if (exe.startsWith("OK ")) {
            note(exe);
            finish();
            return;
        }
        try {
            ScreenShot.request(this);
        } catch (Throwable t) {
            Log.e("SHOT 请求失败: " + t);
            note("ERR screencap 与 MediaProjection 都失败：" + exe + " / 请求异常: " + t);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != ScreenShot.REQ) {
            super.onActivityResult(req, res, data);
            return;
        }
        ScreenShot.grab(this, res, data, new ScreenShot.Done() {
            @Override public void run(String pathOrErr) {
                Log.i("SHOT 结果 -> " + pathOrErr);
                note(pathOrErr);
                finish();
            }
        });
    }

    /** 结果落盘，PC 脚本 `adb shell cat /sdcard/kidguard/shot.txt` 直接看。 */
    private void note(String s) {
        try {
            File dir = new File("/sdcard/kidguard");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(dir, "shot.txt"), false);
            fos.write(s.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable t) {
            Log.w("SHOT 写 shot.txt 失败: " + t);
        }
    }
}
