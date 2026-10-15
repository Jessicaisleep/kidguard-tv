package com.kidguard.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 纯代码 UI 工具（不依赖 AndroidX）。 */
public final class Ui {

    public static final int BG     = 0xFF0B1020;
    public static final int CARD   = 0xFF16213E;
    public static final int ACCENT = 0xFF4FC3F7;
    public static final int WARN   = 0xFFFF5252;
    public static final int OKC    = 0xFF69F0AE;
    public static final int TEXT   = 0xFFECEFF1;
    public static final int DIM    = 0xFF90A4AE;

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView tv(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static Button btn(Context c, String s) {
        return btn(c, s, ACCENT);
    }

    public static Button btn(Context c, String s, int tint) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(0xFF0B1020);
        b.setBackground(round(tint, 8));
        b.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        focusRing(c, b, tint);
        return b;
    }

    /**
     * 电视上没有鼠标，只有焦点框。按钮拿到焦点时必须肉眼可见，否则家长按遥控器
     * 根本不知道当前停在哪一格（Android 默认的焦点高亮会被 setBackground 覆盖掉）。
     */
    public static void focusRing(final Context c, final Button b, final int tint) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) {
                    GradientDrawable g = round(lighten(tint), 8);
                    g.setStroke(dp(c, 3), ACCENT);
                    v.setBackground(g);
                } else {
                    v.setBackground(round(tint, 8));
                }
            }
        });
    }

    /** 把颜色整体提亮（保留透明度），用于焦点态。 */
    public static int lighten(int color) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * 1.4f) + 26);
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * 1.4f) + 26);
        int b = Math.min(255, (int) ((color & 0xFF) * 1.4f) + 26);
        if (a < 0x50) {
            a = Math.min(0xFF, a + 0x66);
            r = Math.max(r, 0xC8);
            g = Math.max(g, 0xC8);
            b = Math.max(b, 0xC8);
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static Button ghostBtn(Context c, String s) {
        Button b = btn(c, s, 0x33FFFFFF);
        b.setTextColor(TEXT);
        return b;
    }

    public static GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * 2f);
        return g;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams mwrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static View gap(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(CARD, 12));
        l.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
        LinearLayout.LayoutParams p = mwrap();
        p.bottomMargin = dp(c, 14);
        l.setLayoutParams(p);
        return l;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }

    public static ProgressBar bar(Context c, int max) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(max);
        p.setLayoutParams(mwrap());
        return p;
    }

    public static void toast(Activity a, String s) {
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------- 输入框 ----------------
    public interface TextCb { void onText(String s); }

    public static void input(final Activity a, String title, String init, final boolean numeric, final TextCb cb) {
        final EditText et = new EditText(a);
        et.setText(init == null ? "" : init);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = col(a);
        // 固定宽度：否则 WRAP_CONTENT 的父容器会把子控件压成一条缝
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 430), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(dp(a, 24), dp(a, 12), dp(a, 24), dp(a, 4));
        box.addView(tv(a, title, 18, TEXT, true));
        box.addView(gap(a, 8));
        et.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(et);
        AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog)
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        cb.onText(et.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        fitDialog(a, dlg, 520);
    }

    // ---------------- PIN 键盘（遥控器可导航） ----------------
    public interface PinCb {
        void onPin(String pin);
        void onCancel();
    }

    public static Pad pinPadOverlay(final Activity a, String title, final PinCb cb) {
        final Pad pad = new Pad();
        pad.activity = a;
        pad.cb = cb;
        final StringBuilder sb = pad.sb;
        final LinearLayout box = col(a);
        // 关键：必须显式给对话框窗口定宽。数字键是 width=0+weight=1，
        // 而 AlertController 会把自定义视图强制成 MATCH_PARENT（setView 的 LayoutParams 会被丢弃），
        // 窗口宽度由内容反推 → 整块塌成 81×363 的一条竖缝，家长根本看不见键盘。
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 430), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(dp(a, 24), dp(a, 16), dp(a, 24), dp(a, 16));

        final TextView disp = tv(a, "· · · ·", 34, ACCENT, true);
        disp.setGravity(Gravity.CENTER);
        box.addView(disp);
        TextView hint = tv(a, title, 14, DIM, false);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint);
        box.addView(gap(a, 10));

        pad.disp = disp;

        final Button[] firstKey = new Button[1];
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确定"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = row(a);
            line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int c2 = 0; c2 < 3; c2++) {
                final String k = keys[r * 3 + c2];
                Button b = btn(a, k, "删除".equals(k) ? 0x55FFFFFF : ("确定".equals(k) ? OKC : ACCENT));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                p.setMargins(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
                b.setLayoutParams(p);
                if ("删除".equals(k)) b.setTextColor(TEXT);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if ("删除".equals(k)) {
                            if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                        } else if ("确定".equals(k)) {
                            pad.submit(sb.toString());
                            return;
                        } else {
                            if (sb.length() < 8) sb.append(k);
                        }
                        pad.refresh();
                    }
                });
                // 遥控器数字键 / 外接键盘数字键直接生效（焦点落在哪个数字键上都一样）；
                // 其它键一律不吞 → 方向键让系统移动焦点框，确定键让系统点击当前焦点按钮
                // （View.onKeyUp 会对 confirm 键执行 performClick）。
                b.setOnKeyListener(new View.OnKeyListener() {
                    @Override public boolean onKey(View v, int code, KeyEvent e) {
                        if ((code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                                || code == KeyEvent.KEYCODE_DEL) {
                            if (e.getAction() == KeyEvent.ACTION_DOWN) pad.onKey(code);
                            return true;
                        }
                        return false;
                    }
                });
                // 触屏设备（手机镜像/带触摸的电视）处于 touch mode 时，
                // focusableInTouchMode=false 的控件拿不到焦点 → 方向键完全失效。
                b.setFocusableInTouchMode(true);
                if (firstKey[0] == null) firstKey[0] = b;
                line.addView(b);
            }
            box.addView(line);
        }

        // 不用独立 Dialog 窗口：电视上它会被 pushLockUi 的重推抢走 window focus，
        // 结果 ViewRootImpl 把遥控器按键全部按 "no window focus" 丢掉（触摸却正常）。
        // 改成挂在 Activity 自己窗口里的全屏覆盖层，按键统一由 GuardActivity.onKeyDown 转发。
        FrameLayout mask = new FrameLayout(a);
        mask.setBackgroundColor(0xCC000000);
        mask.setClickable(true);
        mask.setFocusable(true);
        mask.setFocusableInTouchMode(true);
        mask.addView(box, new FrameLayout.LayoutParams(dp(a, 430), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        pad.view = mask;
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        blockBackground(content);
        content.addView(mask, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 主动把焦点给第一个数字键：没有焦点视图时，方向键在 ViewRootImpl.performFocusNavigation
        // 里因为 findFocus()==null 而直接放弃，遥控器就完全点不动这个键盘。
        if (firstKey[0] != null) {
            final Button f0 = firstKey[0];
            f0.post(new Runnable() {
                @Override public void run() {
                    try { f0.requestFocus(); } catch (Throwable ignored) { }
                }
            });
        }
        return pad;
    }

    /** 给 AlertDialog 定一个电视上看得清的宽度（不超过屏幕 90%）。 */
    public static void fitDialog(Activity a, AlertDialog d, int widthDp) {
        try {
            android.view.Window w = d.getWindow();
            if (w == null) return;
            int px = dp(a, widthDp);
            int max = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.9f);
            if (px > max) px = max;
            w.setLayout(px, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) { }
    }

    /** PIN 键盘状态。挂在活动窗口内，不走独立 Dialog 窗口，避免窗口焦点丢失。 */
    public static final class Pad {
        public View view;
        public Activity activity;
        public PinCb cb;
        final StringBuilder sb = new StringBuilder();
        TextView disp;
        boolean done = false;

        public void refresh() {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < sb.length(); i++) d.append("● ");
            disp.setText(d.length() == 0 ? "· · · ·" : d.toString());
        }

        /** 返回 true 表示已消费该按键。 */
        public boolean onKey(int code) {
            if (done) return true;
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                if (sb.length() < 8) sb.append((char) ('0' + (code - KeyEvent.KEYCODE_0)));
            } else if (code == KeyEvent.KEYCODE_DEL) {
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } else if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                submit(sb.toString());
                return true;
            } else {
                return false;
            }
            refresh();
            return true;
        }

        /** ACTION_UP 阶段：已消费过的键也要吞掉，避免 View.onKeyUp 再触发一次按钮点击。 */
        public boolean onKeyUp(int code) {
            if (done) return true;
            if ((code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                    || code == KeyEvent.KEYCODE_DEL
                    || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) return true;
            return false;
        }

        public void submit(String pin) {
            if (done) return;
            done = true;
            hide();
            if (pin == null || pin.isEmpty()) cb.onCancel(); else cb.onPin(pin);
        }

        public void hide() {
            try {
                if (view == null) return;
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                    unblockBackground(parent);
                }
            } catch (Throwable ignored) { }
        }
    }

    // ---------------- 文字输入盘（电视端：预设词条 + 字母数字键 + 系统输入法） ----------------
    public interface TextPadCb {
        void onText(String s);
        void onCancel();
    }

    /**
     * 「加入但不关窗」模式的回调：加完一个继续留着面板，方便连着加好几个。
     * 真机反馈（m03316 BUG2）：原来加一个词条就退回主界面，加 5 个词条要进进出出 5 次。
     */
    public interface TextPadAdd {
        void onAdd(String s);
    }

    /** 遥控器/键盘的数字与字母键 → 字符（0 = 不是可打印键）。 */
    public static char keyChar(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return (char) ('0' + (code - KeyEvent.KEYCODE_0));
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return (char) ('a' + (code - KeyEvent.KEYCODE_A));
        return 0;
    }

    /**
     * 电视上的文字输入盘。
     * 之所以不用 AlertDialog / 系统弹窗：锁屏每 4 秒重推会抢走窗口焦点，
     * 独立窗口的 ViewRootImpl 会丢掉所有按键（logcat: Dropping event due to no window focus）。
     * 所以一律做成「挂在当前 Activity 窗口里的覆盖层」，按键由 Activity 转给我们。
     * EditText 只作为显示与输入法的落点；真正的文本存在 EditText 里，方向键上/下可离开它。
     */
    public static final class TextPad {
        public View view;
        public Activity activity;
        public TextPadCb cb;
        /** 非空 = 「加入但不关窗」模式（连续加词条）。 */
        public TextPadAdd adder;
        /** 覆盖层那层 ScrollView（加完一个之后滚回顶部用）。 */
        public ScrollView sc;
        /** 第一个常用词条按钮（加完一个之后把焦点还给它在的地方）。 */
        public Button firstBtn;
        public EditText et;
        boolean done = false;

        public String text() {
            try { return et == null ? "" : et.getText().toString(); } catch (Throwable t) { return ""; }
        }

        /**
         * 「加入」一个但保持面板不关（可以连着加）。
         * 真机反馈（BUG2）：原来加一个就退回主界面，加 5 个词条要进进出出 5 次。
         */
        public boolean addNow() {
            if (done) return true;
            String v = text().trim();
            if (v.isEmpty()) {
                toast(activity, "先点一个常用词条或打字，再按「加入」");
                return true;
            }
            try { if (adder != null) adder.onAdd(v); } catch (Throwable t) { }
            clear();
            // 加完一个之后回到面板顶部：输入框是空的、常用词条一目了然，接着加下一个
            //（不然焦点停在下方的「加入」按钮上，整个面板会滚到看不到输入框的位置）。
            // 注意顺序：requestFocus 会触发 ScrollView「把焦点滚进可视区」（只滚最小距离，
            // 真机上停在最顶端、标题和输入框被顶出屏幕），所以必须 post 一次 fullScroll 兜住。
            try {
                if (firstBtn != null) firstBtn.requestFocus();
                final ScrollView sv = sc;
                if (sv != null) sv.post(new Runnable() { public void run() { try { sv.fullScroll(ScrollView.FOCUS_UP); } catch (Throwable t) { } } });
            } catch (Throwable ignored) { }
            return true;
        }

        public void append(String s) {
            if (done || s == null || s.isEmpty()) return;
            try {
                if (et.getText().length() + s.length() > 30) return;
                et.append(s);
            } catch (Throwable ignored) { }
        }

        public void del() {
            if (done) return;
            try {
                android.text.Editable e = et.getText();
                if (e.length() > 0) e.delete(e.length() - 1, e.length());
            } catch (Throwable ignored) { }
        }

        public void clear() {
            if (done) return;
            try { et.setText(""); } catch (Throwable ignored) { }
        }

        /** 返回 true 表示已消费该按键。 */
        public boolean onKey(int code) {
            if (done) return true;
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (adder != null) addNow(); else submit(text());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) { del(); return true; }
            char ch = keyChar(code);
            if (ch != 0) { append(String.valueOf(ch)); return true; }
            return false;
        }

        public boolean onKeyUp(int code) {
            if (done) return true;
            if (code == KeyEvent.KEYCODE_DEL || keyChar(code) != 0) return true;
            return false;
        }

        public void submit(String s) {
            if (done) return;
            done = true;
            String v = s == null ? "" : s.trim();
            hide();
            if (cb == null) return;   // 只给了「加入」回调的调用方：关掉面板就够了
            try {
                if (v.isEmpty()) cb.onCancel(); else cb.onText(v);
            } catch (Throwable ignored) { }
        }

        public void hide() {
            try {
                if (view == null) return;
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                    // 关键：overlay() 里把底下的界面设成了 FOCUS_BLOCK_DESCENDANTS，
                    // 这里不还原的话，面板一关整个主界面就没有任何东西能拿焦点 ——
                    // 真机表现就是「退回主界面后只剩上下翻页，选不到任何按钮」（BUG1）。
                    unblockBackground(parent);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 给按钮同时挂「点击」与「遥控器按键」两条路（焦点在按钮上时按键不走 onclick，得自己接）。
     *  pad 可以为 null（普通覆盖层用）。 */
    public static void bindAction(final TextPad pad, final Button b, final Runnable action) {
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (pad == null || !pad.done) action.run(); }
        });
        b.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (pad != null && pad.done) return true;
                if (e.getAction() == KeyEvent.ACTION_UP) return true;
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || code == KeyEvent.KEYCODE_DPAD_CENTER) { action.run(); return true; }
                return pad != null && pad.onKey(code);
            }
        });
    }

    public static void showIme(Activity a, EditText et) {
        try {
            et.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) { }
    }

    /**
     * 把一块内容以「全屏遮罩 + 居中卡片 + 可滚动」的方式挂到当前 Activity 窗口上。
     * 电视上独立弹窗（AlertDialog）会因为窗口焦点被抢而收不到遥控器按键，所以一律走覆盖层。
     */
    public static View overlay(Activity a, View box, int widthDp, int heightDp) {
        // 真机电视常见 1080p + 320dpi（density=2.0），按 dp 给的尺寸会被放大到比屏幕还大 →
        // 覆盖层被裁到只剩左边一条（实测酷喵那台电视上「添加关键字」面板就是被裁掉的）。
        // 所以这里按屏幕可用尺寸夹一次，四周留 24dp 边距。
        try {
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            if (dm != null && dm.density > 0f) {
                int maxW = (int) (dm.widthPixels / dm.density) - 24;
                int maxH = (int) (dm.heightPixels / dm.density) - 24;
                if (maxW > 200 && widthDp > maxW) widthDp = maxW;
                if (maxH > 200 && heightDp > maxH) heightDp = maxH;
            }
        } catch (Throwable ignored) { }
        FrameLayout mask = new FrameLayout(a);
        mask.setBackgroundColor(0xCC000000);
        mask.setClickable(true);
        mask.setFocusable(true);
        mask.setFocusableInTouchMode(true);
        ScrollView sc = new ScrollView(a);
        sc.setBackground(round(CARD, 12));
        sc.addView(box);
        mask.addView(sc, new FrameLayout.LayoutParams(dp(a, widthDp), dp(a, heightDp), Gravity.CENTER));
        try {
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            blockBackground(content);
            content.addView(mask,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) { }
        return mask;
    }

    /** 覆盖层弹出时，把底下原有的界面挡在焦点搜索之外（否则方向键可能把焦点挪到被盖住的按钮上）。 */
    static void blockBackground(ViewGroup parent) {
        setBgFocus(parent, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** 覆盖层移除后恢复原有界面能拿焦点。 */
    static void unblockBackground(ViewGroup parent) {
        setBgFocus(parent, ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    }

    private static void setBgFocus(ViewGroup parent, int mode) {
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View ch = parent.getChildAt(i);
                if (ch instanceof ViewGroup) ((ViewGroup) ch).setDescendantFocusability(mode);
            }
        } catch (Throwable ignored) { }
    }

    public static void removeOverlay(View mask) {
        try {
            if (mask == null) return;
            ViewGroup parent = (ViewGroup) mask.getParent();
            if (parent != null) {
                parent.removeView(mask);
                unblockBackground(parent);
            }
        } catch (Throwable ignored) { }
    }

    public static TextPad textPadOverlay(final Activity a, String title, final String[] presets,
                                         final boolean letters, final TextPadCb cb) {
        return textPadOverlay(a, title, presets, letters, cb, null);
    }

    /**
     * 带「加入但不关窗」回调的版本（{@code adder != null} 时启用连续添加模式：
     * 「加入」只加不关面板，「完成（返回）」才关）。
     */
    public static TextPad textPadOverlay(final Activity a, String title, final String[] presets,
                                         final boolean letters, final TextPadCb cb,
                                         final TextPadAdd adder) {
        final TextPad pad = new TextPad();
        pad.activity = a;
        pad.cb = cb;
        pad.adder = adder;
        final Button[] firstRef = new Button[1];

        LinearLayout box = col(a);
        box.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 14));

        box.addView(tv(a, title, 20, ACCENT, true));

        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        et.setTextColor(TEXT);
        et.setHint("还没输入…（中文用下面「常用」，或「调出输入法」）");
        et.setHintTextColor(DIM);
        et.setBackground(round(0x22FFFFFF, 8));
        et.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        et.setLayoutParams(mwrap());
        pad.et = et;
        box.addView(et);
        box.addView(gap(a, 8));

        // 输入框里按方向键要能离开（TextView 默认会吃掉左右键去挪光标、吃上下键去滚动，
        // 真机反馈：光修上下不够 —— 「只能上下不能左右」就是这么来的）。
        et.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int dir;
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) dir = View.FOCUS_DOWN;
                    else if (code == KeyEvent.KEYCODE_DPAD_UP) dir = View.FOCUS_UP;
                    else if (code == KeyEvent.KEYCODE_DPAD_LEFT) dir = View.FOCUS_LEFT;
                    else dir = View.FOCUS_RIGHT;
                    View nxt = v.focusSearch(dir);
                    if (nxt == null || nxt == v) nxt = firstRef[0];
                    if (nxt != null && nxt != v) { nxt.requestFocus(); return true; }
                    return false;
                }
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (pad.adder != null) pad.addNow(); else pad.submit(pad.text());
                    return true;
                }
                return false;
            }
        });

        if (presets != null && presets.length > 0) {
            box.addView(tv(a, "常用词条（点一下就加进去）", 15, DIM, false));
            LinearLayout line = null;
            for (int i = 0; i < presets.length; i++) {
                if (i % 5 == 0) { line = row(a); line.setLayoutParams(mwrap()); box.addView(line); }
                final String w = presets[i];
                Button b = ghostBtn(a, w);
                bindAction(pad, b, new Runnable() { @Override public void run() { pad.append(w); } });
                LinearLayout.LayoutParams lp = wrap();
                lp.setMargins(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
                b.setLayoutParams(lp);
                if (line != null) line.addView(b);
                if (firstRef[0] == null) firstRef[0] = b;
            }
        }

        box.addView(tv(a, letters ? "自己打字（字母 / 数字）" : "自己打字（数字）", 15, DIM, false));
        String ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        LinearLayout line = null;
        if (letters) {
            for (int i = 0; i < ABC.length(); i++) {
                if (i % 9 == 0) { line = row(a); line.setLayoutParams(mwrap()); box.addView(line); }
                final String ch = String.valueOf(ABC.charAt(i)).toLowerCase();
                Button b = ghostBtn(a, ch);
                bindAction(pad, b, new Runnable() { @Override public void run() { pad.append(ch); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 78), ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
                b.setLayoutParams(lp);
                if (line != null) line.addView(b);
                if (firstRef[0] == null) firstRef[0] = b;
            }
        }
        line = row(a); line.setLayoutParams(mwrap()); box.addView(line);
        for (int d = 0; d <= 9; d++) {
            final String ch = String.valueOf((char) ('0' + d));
            Button b = ghostBtn(a, ch);
            bindAction(pad, b, new Runnable() { @Override public void run() { pad.append(ch); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 78), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
            b.setLayoutParams(lp);
            if (line != null) line.addView(b);
            if (firstRef[0] == null) firstRef[0] = b;
        }

        box.addView(gap(a, 8));
        LinearLayout acts = row(a);
        acts.setLayoutParams(mwrap());
        Button bDel = ghostBtn(a, "删除一位");
        bindAction(pad, bDel, new Runnable() { @Override public void run() { pad.del(); } });
        Button bClr = ghostBtn(a, "清空");
        bindAction(pad, bClr, new Runnable() { @Override public void run() { pad.clear(); } });
        Button bIme = ghostBtn(a, "调出输入法");
        bindAction(pad, bIme, new Runnable() { @Override public void run() { showIme(a, et); } });
        Button bNo = ghostBtn(a, adder != null ? "完成（返回）" : "取消");
        bindAction(pad, bNo, new Runnable() { @Override public void run() { pad.submit(""); } });
        Button bOk = btn(a, adder != null ? "加入（不关窗）" : "确定加入", OKC);
        if (adder != null) {
            bindAction(pad, bOk, new Runnable() { @Override public void run() { pad.addNow(); } });
        } else {
            bindAction(pad, bOk, new Runnable() { @Override public void run() { pad.submit(pad.text()); } });
        }
        Button[] actBtns = new Button[] { bDel, bClr, bIme, bNo, bOk };
        for (int i = 0; i < actBtns.length; i++) {
            LinearLayout.LayoutParams lp = wrap();
            lp.setMargins(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
            actBtns[i].setLayoutParams(lp);
            acts.addView(actBtns[i]);
        }
        box.addView(acts);

        pad.view = overlay(a, box, 1020, 650);
        // 注意：overlay() 返回的是最外层 mask（FrameLayout），ScrollView 是 box 的父节点。
        try {
            if (box.getParent() instanceof ScrollView) pad.sc = (ScrollView) box.getParent();
        } catch (Throwable ignored) { }
        final Button fb = firstRef[0];
        pad.firstBtn = fb;
        if (fb != null) fb.post(new Runnable() { @Override public void run() { fb.requestFocus(); } });
        return pad;
    }

    // ---------------- 应用列表 ----------------
    public static List<ResolveInfo> launchables(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(appPm)).compareTo(String.valueOf(b.loadLabel(appPm)));
            }
        });
        return list;
    }

    static PackageManager appPm;

    public static void fillLaunchables(Activity a, LinearLayout container, int perRow, boolean skipBlocked) {
        appPm = a.getPackageManager();
        List<ResolveInfo> list = launchables(a);
        LinearLayout line = null;
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            final String pkg = ri.activityInfo.packageName;
            if (pkg.equals(a.getPackageName())) continue;
            // skipBlocked：跳过「APP 黑名单里、且还没被家长放行」的包（= 点开也要家长 PIN 的那种）。
            if (skipBlocked && Prefs.appLocked(a, pkg)) continue;
            if (n % perRow == 0) { line = row(a); line.setLayoutParams(mwrap()); container.addView(line); }
            n++;
            Button b = btn(a, String.valueOf(ri.loadLabel(appPm)), 0x22FFFFFF);
            b.setTextColor(TEXT);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { launch(a, pkg); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
            b.setLayoutParams(p);
            if (line != null) line.addView(b);
        }
        if (n == 0) {
            container.addView(tv(a, "（没有可用的应用）", 16, DIM, false));
        }
    }

    public static void launch(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.w("launch fail " + pkg + " " + t);
        }
    }
}
