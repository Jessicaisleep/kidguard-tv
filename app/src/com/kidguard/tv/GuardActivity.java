package com.kidguard.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 桌面 + 锁屏（合一）。既是 HOME，也是超时后的强制界面。 */
public class GuardActivity extends Activity {

    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy 年 M 月 d 日 EEEE", Locale.CHINA);

    private LinearLayout root;
    private Handler h;
    private Runnable refresher;
    private boolean renderedLocked = false;
    private int renderedStyle = -1;
    private Ui.Pad pad;
    private boolean kiosk = false;

    private TextView clock, stats, stats2;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.bind(this);
        Prefs.ensureDefaults(this);
        Prefs.setStarted(this, true);
        if (Prefs.isNewDay(this)) Prefs.rollover(this);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } catch (Throwable ignored) { }
        render(true);
        GuardService.start(this);
        if (!Admin.isAdmin(this)) Admin.requestAdmin(this);
        h = new Handler();
        refresher = new Runnable() {
            @Override public void run() {
                try { refresh(); } catch (Throwable t) { Log.e("GuardActivity.refresh", t); }
                try { applyKiosk(); } catch (Throwable t) { Log.e("GuardActivity.kiosk", t); }
                if (h != null) h.postDelayed(this, 1000L);
            }
        };
        h.postDelayed(refresher, 1000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try { refresh(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { refresh(); } catch (Throwable ignored) { }
        try { applyKiosk(); } catch (Throwable ignored) { }
    }

    /**
     * 锁定期间用「锁定任务模式」（Lock Task / COSU）把这个界面钉在最前面。
     *
     * 为什么必须这么做：正常的桌面（当贝桌面影视版）由家长自己设成了 HOME，而 Android 的系统
     * 不允许挂起「当前桌面」这个包（真机实测：setPackagesSuspended 对
     * com.yangqi.rom.launcher.free 返回 failed，日志 `partial fail: com.yangqi.rom.launcher.free`），
     * 所以到点锁定后光是挂起应用挡不住遥控器的【主页】键。锁定任务模式由系统层面禁掉
     * HOME / 最近任务，是本机唯一能把孩子摁在锁屏上的手段（我们是 Device Owner，
     * Admin.applyAll 已经把本包设成 lockTaskPackages 白名单，所以不需要用户确认）。
     * 解锁（或用量归零）后下一秒自动解除。
     */
    private void applyKiosk() {
        boolean want = Prefs.isLocked(this) && Prefs.guardOn(this) && Admin.isOwner(this);
        try {
            if (want && !kiosk) {
                kiosk = true;
                startLockTask();
                Log.i("KIOSK on 锁定任务模式已开启");
            } else if (!want && kiosk) {
                stopLockTask();
                kiosk = false;
                Log.i("KIOSK off 锁定任务模式已解除");
            }
        } catch (Throwable t) {
            // 开失败：复位标记，下一拍重试。
            // 关失败（例如这一刻本界面不在前台，系统报 Invalid task, not in foreground）：
            // 必须保留 kiosk=true，否则设备会被一直钉在锁屏上却再也没人去解。
            if (want) kiosk = false;
            Log.w("kiosk fail: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        try { if (h != null && refresher != null) h.removeCallbacks(refresher); } catch (Throwable ignored) { }
        try { if (pad != null) pad.hide(); } catch (Throwable ignored) { }
        pad = null;
        GuardService.setPinPadOpen(false);
        super.onDestroy();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Prefs.isLocked(this)) {
            // 不允许离开锁屏
            try { render(true); } catch (Throwable ignored) { }
        }
    }

    // ---------------- 视图 ----------------
    private void render(boolean force) {
        boolean locked = Prefs.isLocked(this) && Prefs.guardOn(this);
        int style = locked ? 2 : 1;
        if (!force && style == renderedStyle) { renderedLocked = locked; return; }
        renderedStyle = style;
        renderedLocked = locked;

        if (root == null) {
            root = Ui.col(this);
            root.setBackgroundColor(Ui.BG);
            root.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 24));
            setContentView(root);
        }
        root.removeAllViews();
        clock = null; stats = null; stats2 = null; bar = null;
        if (locked) buildLock(); else buildHome();
        refresh();
    }

    private void buildHome() {
        LinearLayout top = Ui.row(this);
        top.setLayoutParams(Ui.mwrap());
        LinearLayout left = Ui.col(this);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Ui.tv(this, "KidGuard 电视管家", 18, Ui.ACCENT, true);
        left.addView(title);
        clock = Ui.tv(this, HM.format(new Date()), 64, Ui.TEXT, true);
        left.addView(clock);
        left.addView(Ui.tv(this, DATE.format(new Date()), 16, Ui.DIM, false));
        top.addView(left);

        LinearLayout right = Ui.col(this);
        right.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 460), ViewGroup.LayoutParams.WRAP_CONTENT));
        stats = Ui.tv(this, "", 24, Ui.TEXT, true);
        stats2 = Ui.tv(this, "", 16, Ui.DIM, false);
        bar = Ui.bar(this, Math.max(1, Prefs.allowedSec(this)));
        right.addView(stats);
        right.addView(Ui.gap(this, 6));
        right.addView(bar);
        right.addView(Ui.gap(this, 6));
        right.addView(stats2);
        top.addView(right);
        root.addView(top);
        root.addView(Ui.gap(this, 20));

        root.addView(Ui.tv(this, "可以看的应用", 16, Ui.ACCENT, true));
        root.addView(Ui.gap(this, 8));
        LinearLayout apps = Ui.col(this);
        apps.setLayoutParams(Ui.mwrap());
        // 注意：最后一个参数是「是否隐藏黑名单包」。黑名单应用 = 「内容守卫要扫描的 App」，
        // 不是「禁止孩子打开」，所以这里必须传 false——否则把孩子要用的优酷也藏掉了。
        Ui.fillLaunchables(this, apps, 4, false);
        root.addView(apps);

        View filler = new View(this);
        filler.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        root.addView(filler);
        root.addView(Ui.tv(this, "遥控器按【菜单键】进入家长设置", 14, Ui.DIM, false));
    }

    private void buildLock() {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.setLayoutParams(p);
        box.addView(Ui.tv(this, "今天的电视时间用完啦", 54, Ui.WARN, true));
        box.addView(Ui.gap(this, 18));
        stats = Ui.tv(this, "", 26, Ui.TEXT, true);
        stats.setGravity(Gravity.CENTER);
        box.addView(stats);
        box.addView(Ui.gap(this, 10));
        bar = Ui.bar(this, Math.max(1, Prefs.allowedSec(this)));
        box.addView(bar);
        box.addView(Ui.gap(this, 10));
        stats2 = Ui.tv(this, "", 18, Ui.DIM, false);
        stats2.setGravity(Gravity.CENTER);
        box.addView(stats2);
        box.addView(Ui.gap(this, 26));
        box.addView(Ui.tv(this, "想看的话，请爸爸妈妈按【菜单键】输入 PIN 加时间", 20, Ui.ACCENT, false));
        box.addView(Ui.gap(this, 26));

        LinearLayout btns = Ui.row(this);
        btns.setGravity(Gravity.CENTER);
        Button pin = Ui.btn(this, "家长 PIN 加时");
        pin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askPin(); }
        });
        Button off = Ui.ghostBtn(this, "关闭电视屏幕");
        off.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Admin.lockNow(GuardActivity.this); }
        });
        btns.addView(pin);
        btns.addView(Ui.gap(this, 40));
        btns.addView(off);
        box.addView(btns);
        root.addView(box);
    }

    private void refresh() {
        boolean locked = Prefs.isLocked(this) && Prefs.guardOn(this);
        if (!locked && renderedStyle == 2) { render(true); return; }
        if (locked && renderedStyle == 1) { render(true); return; }
        if (pad != null) return;
        if (clock != null) clock.setText(HM.format(new Date()));
        if (stats != null) {
            int used = Prefs.usedToday(this) / 60;
            int total = Prefs.dailyMin(this) + Prefs.extraToday(this);
            int remain = Prefs.remainSec(this) / 60;
            stats.setText("今日已用 " + used + " 分钟   剩余 " + remain + " 分钟   今日上限 " + total + " 分钟");
        }
        if (stats2 != null) {
            String note;
            if (Prefs.freeToday(this)) note = "今天已放行（不限时）";
            else if (Prefs.forceLock(this)) note = "已被家长手动锁定";
            else if (!Admin.isOwner(this)) note = "当前为普通模式（未取得设备所有者权限）";
            else note = "设备所有者模式：时间到将自动挂起娱乐应用";
            stats2.setText(note);
        }
        if (bar != null) {
            bar.setMax(Math.max(1, Prefs.allowedSec(this)));
            bar.setProgress(Math.min(Prefs.usedToday(this), Math.max(1, Prefs.allowedSec(this))));
        }
    }

    // ---------------- 家长入口 ----------------
    private void askPin() {
        if (pad != null) return;
        if (!Prefs.hasPin(this)) {
            openParent();
            return;
        }
        GuardService.setPinPadOpen(true);
        pad = Ui.pinPadOverlay(this, "请输入家长 PIN", new Ui.PinCb() {
            @Override public void onPin(String pin) {
                closePad();
                if (Prefs.checkPin(GuardActivity.this, pin)) openParent();
                else Ui.toast(GuardActivity.this, "PIN 不正确");
            }
            @Override public void onCancel() { closePad(); }
        });
    }

    private void closePad() {
        if (pad != null) pad.hide();
        pad = null;
        GuardService.setPinPadOpen(false);
    }

    private void openParent() {
        try {
            Intent i = new Intent(this, ParentActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.e("openParent fail", t);
        }
    }

    // ---------------- 按键 ----------------
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean locked = Prefs.isLocked(this) && Prefs.guardOn(this);
        // PIN 键盘是挂在活动窗口里的覆盖层：遥控器按键全部走活动自己的按键回调，
        // 不再依赖 AlertDialog 独立窗口的 window focus（那个在电视上会被锁屏重推抢走）。
        if (pad != null) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.onKeyDown(code, e);
            }
            if (code == KeyEvent.KEYCODE_BACK) { pad.submit(""); return true; }
            // 数字键 / 删除键：遥控器数字键或外接键盘直接生效（焦点停在哪个键上都一样）
            if ((code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                    || code == KeyEvent.KEYCODE_DEL) {
                pad.onKey(code);
                return true;
            }
            // 其余（上下左右、确定、回车…）一律交回视图层：
            //   方向键 → 系统移动焦点框；确定键 → 系统点击当前有焦点的那个按钮
            // 旧版这里把方向键也吞了，导致焦点框永远卡在「1」上、左右上下都没反应。
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_PROG_RED) {
            askPin();
            return true;
        }
        if (locked) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.onKeyDown(code, e);
            }
            return true;   // 锁定时吞掉所有按键（含 BACK / HOME 之外的确认键）
        }
        if (code == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (pad != null) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.onKeyUp(code, e);
            }
            return true;
        }
        boolean locked = Prefs.isLocked(this) && Prefs.guardOn(this);
        if (locked && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) return true;
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
