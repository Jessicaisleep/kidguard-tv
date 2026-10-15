package com.kidguard.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** 家长设置界面（进入前必须通过 PIN）。 */
public class ParentActivity extends Activity {

    private LinearLayout root;
    private LinearLayout statsCard;
    private TextView statusText;
    private Ui.TextPad tpad;
    private View appMask;

    /** 打开覆盖层之前焦点在哪个按钮上：面板关掉后把焦点还回去（不然退回主界面会被滚到最顶端）。 */
    private View lastFocus;

    /** 两个开关的按钮：文字里带「开 / 关」，家长一眼能看出当前状态。 */
    private Button btnCover;
    private Button btnStrict;
    /** 「黑名单遮罩时长」按钮：文字里带当前秒数（1~10，可调）。 */
    private Button btnCoverSec;

    /** 「清空」这类破坏性按钮要按两次；记录上一次按的是哪个名单。 */
    private static final long CLEAR_CONFIRM_MS = 6000L;
    private String pendingClear = null;
    private long pendingClearAt = 0L;

    /** 遮罩里家长按了【菜单键】进来时，要放行的包名（走完 PIN 就放行 30 分钟）。 */
    private String allowPkg = null;

    /** 心跳：让「家长界面在前台」这个标记位每 2 秒续期一次（进程常驻，不能只靠 onPause 收尾）。 */
    private final android.os.Handler hb = new android.os.Handler();
    private final Runnable hbTask = new Runnable() {
        @Override public void run() {
            GuardService.setParentForeground(true);
            hb.postDelayed(this, 2000L);
        }
    };

    private static final String[] MIN_PRESETS = new String[]{
            "30", "45", "60", "90", "120", "150", "180", "240", "300"
    };
    private static final String[] STEP_PRESETS = new String[]{
            "5", "10", "15", "20", "30", "45", "60"
    };

    /**
     * 黑名单遮罩时长预设（秒）。家长反馈（m02430）：想让「已屏蔽」黑幕挡多久自己定，
     * 所以给 1~10 秒的范围 + 这几个常用值；真正的 clamp 在 {@link Prefs#setCoverSec}。
     */
    private static final String[] COVER_PRESETS = new String[]{
            "1", "2", "3", "5", "8", "10"
    };

    /** 常见动画词条：遥控器打字慢，给家长一键加。 */
    private static final String[] WORD_PRESETS = new String[]{
            "熊出没", "小猪佩奇", "汪汪队", "奥特曼", "喜羊羊", "奶龙", "植物大战僵尸",
            "我的世界", "迷你世界", "猪猪侠", "超级飞侠", "海底小纵队", "大头儿子",
            "巴啦啦小魔仙", "叶罗丽", "精灵梦", "爆笑虫子", "斗罗大陆", "芭比"
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.bind(this);
        Prefs.ensureDefaults(this);
        GuardService.setParentForeground(true);
        build();
        // 从「已屏蔽」遮罩里按【菜单键】进来的：先验 PIN，输对了就允许这个 App 播放 30 分钟。
        // 注意：必须走 PIN，不能直接在这个界面上放行。
        try {
            if (getIntent() != null && getIntent().getBooleanExtra("need_pin", false)) {
                allowPkg = getIntent().getStringExtra("allow_pkg");
                Ui.toast(this, "请输入家长 PIN 才能允许播放");
                askAllowPin();
            }
        } catch (Throwable t) {
            Log.w("parent need_pin: " + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService.setParentForeground(true);
        hb.removeCallbacks(hbTask);
        hb.postDelayed(hbTask, 2000L);
        // 家长界面在前台时把「已屏蔽」黑幕撤掉（家长正在操作，别盖着他）；
        // 但如果这是「APP 门锁」场景，锁要保留 —— 家长界面一离开就把黑幕盖回来。
        GuardAccessibilityService.parentOpened();
        refreshStatus();
        restoreFocus();
    }

    @Override
    protected void onPause() {
        hb.removeCallbacks(hbTask);
        GuardService.setParentForeground(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        GuardAccessibilityService.parentClosed();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        hb.removeCallbacks(hbTask);
        GuardService.setParentForeground(false);
        if (tpad != null) { tpad.hide(); tpad = null; }
        super.onDestroy();
    }

    /** 覆盖层键盘在时，按键先给它（电视上遥控器按键不走 onclick）。 */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (tpad != null) {
            if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) return super.onKeyDown(code, e);
            if (code == android.view.KeyEvent.KEYCODE_BACK) { tpad.submit(""); return true; }
            // 方向键自己挪焦点：覆盖层在前台时这里的按键不会再有下家，
            // 光靠系统默认的方向导航会出现「只能上下、不能左右」（真机反馈过）。
            int dir = 0;
            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) dir = View.FOCUS_UP;
            else if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) dir = View.FOCUS_DOWN;
            else if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) dir = View.FOCUS_LEFT;
            else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) dir = View.FOCUS_RIGHT;
            if (dir != 0) {
                View f = getCurrentFocus();
                View nxt = (f == null) ? null : f.focusSearch(dir);
                if (nxt != null && nxt != f) nxt.requestFocus(dir);
                return true;
            }
            tpad.onKey(code);
            return true;
        }
        if (appMask != null && code == android.view.KeyEvent.KEYCODE_BACK) { closeApps(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, android.view.KeyEvent e) {
        if (tpad != null) {
            if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) return super.onKeyUp(code, e);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void build() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Ui.BG);
        root = Ui.col(this);
        root.setPadding(Ui.dp(this, 40), Ui.dp(this, 24), Ui.dp(this, 40), Ui.dp(this, 32));
        sc.addView(root);
        setContentView(sc);

        // ---- 状态 ----
        root.addView(Ui.tv(this, "KidGuard 家长控制台", 30, Ui.ACCENT, true));
        root.addView(Ui.gap(this, 12));
        statsCard = Ui.card(this);
        statusText = Ui.tv(this, "", 16, Ui.TEXT, false);
        statsCard.addView(statusText);
        root.addView(statsCard);

        // ---- 时长 ----
        LinearLayout t = Ui.card(this);
        t.addView(Ui.tv(this, "① 每日时长", 22, Ui.ACCENT, true));
        t.addView(Ui.gap(this, 8));
        t.addView(buttonRow(new String[]{"设置每日上限", "设置每次加时", "今日清零", "今天放行", "加时一次", "强制锁切换", "强锁模式"},
                new Runnable[]{
                        new Runnable() { @Override public void run() {
                            editNum("每日上限（分钟）", MIN_PRESETS, 60, new NumCb() {
                                @Override public void onNum(int v) { Prefs.setDailyMin(ParentActivity.this, v); }});
                        }},
                        new Runnable() { @Override public void run() {
                            editNum("每次加时（分钟）", STEP_PRESETS, 30, new NumCb() {
                                @Override public void onNum(int v) { Prefs.setAddStep(ParentActivity.this, v); }});
                        }},
                        new Runnable() { @Override public void run() {
                            Prefs.setUsedToday(ParentActivity.this, 0);
                            Prefs.setZeroedToday(ParentActivity.this, true);
                            Prefs.addExtra(ParentActivity.this, -Prefs.extraToday(ParentActivity.this));
                            Prefs.setFreeToday(ParentActivity.this, false);
                            Prefs.setForceLock(ParentActivity.this, false);
                            Admin.unsuspendAll(ParentActivity.this);
                            done();
                        }},
                        new Runnable() { @Override public void run() {
                            boolean on = !Prefs.freeToday(ParentActivity.this);
                            Prefs.setFreeToday(ParentActivity.this, on);
                            if (on) Prefs.setForceLock(ParentActivity.this, false);
                            done();
                        }},
                        new Runnable() { @Override public void run() {
                            Prefs.addExtra(ParentActivity.this, Prefs.addStep(ParentActivity.this));
                            done();
                        }},
                        new Runnable() { @Override public void run() {
                            Prefs.setForceLock(ParentActivity.this, !Prefs.forceLock(ParentActivity.this));
                            done();
                        }},
                        new Runnable() { @Override public void run() {
                            boolean on = !Prefs.hardLock(ParentActivity.this);
                            Prefs.setHardLock(ParentActivity.this, on);
                            Ui.toast(ParentActivity.this, on ? "强锁模式：开（时间到后挂起所有应用）" : "强锁模式：关（只挂起黑名单）");
                            done();
                        }}
                }));
        root.addView(t);

        // ---- PIN ----
        LinearLayout p = Ui.card(this);
        p.addView(Ui.tv(this, "② 家长 PIN", 22, Ui.ACCENT, true));
        p.addView(Ui.gap(this, 8));
        p.addView(buttonRow(new String[]{"设置 / 修改 PIN"}, new Runnable[]{
                new Runnable() { @Override public void run() { editPin(); }}
        }));
        root.addView(p);

        // ---- 内容黑名单：关键字名单 + 片名名单 ----
        LinearLayout k = Ui.card(this);
        k.addView(Ui.tv(this, "③ 内容黑名单（关键字 + 片名）", 22, Ui.ACCENT, true));
        k.addView(Ui.gap(this, 6));
        k.addView(Ui.tv(this, "怎么拦：焦点框停在动画片上时，按「确定」没反应（不弹窗、也不退出）；"
                + "万一真播起来了，会盖一层「已屏蔽」（不退出、不记违规、到点自动放开）。"
                + "两个名单命中任意一个就拦。"
                + "注意：内容黑名单是全局规则，除「APP 白名单」里的应用外，所有 App 都会过滤。", 14, Ui.DIM, false));
        k.addView(Ui.gap(this, 10));

        k.addView(Ui.tv(this, "关键字名单（模糊匹配：填「熊出没」，能拦住「熊出没之怪兽计划」）", 15, Ui.TEXT, true));
        LinearLayout kwBox = Ui.col(this);
        kwBox.setId(1001);
        k.addView(kwBox);
        k.addView(Ui.gap(this, 6));
        k.addView(buttonRow(new String[]{"加关键字", "清空关键字", "常见动画一键加"}, new Runnable[]{
                new Runnable() { @Override public void run() { editWord("关键字", "blKeywords"); }},
                new Runnable() { @Override public void run() {
                    if (!clearConfirmed("关键字名单")) return;
                    Prefs.setList(ParentActivity.this, "blKeywords", new java.util.ArrayList<String>());
                    done();
                }},
                new Runnable() { @Override public void run() { addPresets("blKeywords"); }}
        }));
        k.addView(Ui.gap(this, 12));

        k.addView(Ui.tv(this, "片名名单（整名匹配：只拦写全名的这一部）", 15, Ui.TEXT, true));
        LinearLayout tiBox = Ui.col(this);
        tiBox.setId(1003);
        k.addView(tiBox);
        k.addView(Ui.gap(this, 6));
        k.addView(buttonRow(new String[]{"加片名", "清空片名"}, new Runnable[]{
                new Runnable() { @Override public void run() { editWord("片名", "blTitles"); }},
                new Runnable() { @Override public void run() {
                    if (!clearConfirmed("片名名单")) return;
                    Prefs.setList(ParentActivity.this, "blTitles", new java.util.ArrayList<String>());
                    done();
                }}
        }));
        k.addView(Ui.gap(this, 12));
        btnCover = Ui.btn(this, "", Ui.ACCENT);
        btnStrict = Ui.btn(this, "", Ui.ACCENT);
        LinearLayout tgl = Ui.row(this);
        tgl.setLayoutParams(Ui.mwrap());
        Button[] tglBtns = new Button[] { btnCover, btnStrict };
        for (int i = 0; i < tglBtns.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            tglBtns[i].setLayoutParams(lp);
            tgl.addView(tglBtns[i]);
        }
        k.addView(tgl);
        k.addView(Ui.gap(this, 6));
        // 黑名单遮罩时长（家长可调 1~10 秒）
        btnCoverSec = Ui.btn(this, "", Ui.ACCENT);
        LinearLayout secRow = Ui.row(this);
        secRow.setLayoutParams(Ui.mwrap());
        secRow.addView(btnCoverSec);
        k.addView(secRow);
        // 电视上按钮是按「有焦点时按确定」触发的（View.onKeyUp 对确定键 performClick），
        // 所以只要挂 OnClickListener 就够了。
        btnCoverSec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editNum("黑名单遮罩时长（秒，1~10）", COVER_PRESETS, Prefs.coverSec(ParentActivity.this), new NumCb() {
                    @Override public void onNum(int v) {
                        Prefs.setCoverSec(ParentActivity.this, v);
                        Ui.toast(ParentActivity.this, "黑名单遮罩：" + Prefs.coverSec(ParentActivity.this) + " 秒");
                    }});
            }
        });
        btnCover.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Prefs.blPlayBlock(ParentActivity.this);
                Prefs.setBlPlayBlock(ParentActivity.this, on);
                Ui.toast(ParentActivity.this, "内容兜底：" + (on ? "已开" : "已关"));
                done();
            }
        });
        btnStrict.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Prefs.strictPreview(ParentActivity.this);
                Prefs.setStrictPreview(ParentActivity.this, on);
                Ui.toast(ParentActivity.this, "预览严格模式：" + (on ? "已开" : "已关"));
                done();
            }
        });
        k.addView(Ui.tv(this, "▲「内容兜底」＝只在「页面或焦点框上真的出现名单里的字」时才动手："
                + "盖一层黑幕写「已屏蔽」（不退出、不记违规、不切集、到点自动放开，家长按【菜单键】输 PIN 可放行 30 分钟）。"
                + "它【不管「自动预览」】——预览那件事只归下面那个开关。", 13, Ui.DIM, false));
        k.addView(Ui.tv(this, "▲「预览严格模式」＝给「片名是艺术字图片、读屏读不出字」的片子兜底："
                + "孩子没按确定、画面自己在动、系统又检测到连续出声满 5 秒 → 当成自动预览盖住（两次拦截至少隔 20 秒）；"
                + "孩子自己按确定挑的片子不动。", 13, Ui.DIM, false));
        k.addView(Ui.tv(this, "两个开关各管各的、互不依赖，可以只开一个；命中或预览都只盖一层黑幕，"
                + "绝不按返回、绝不切集（本版已把「切下一条」整个删掉），所以孩子乱按也不会「一下跳好几个」。", 13, Ui.DIM, false));
        k.addView(Ui.tv(this, "遮罩挡多久由「黑名单遮罩时长」那个按钮定（1~10 秒，默认 10）："
                + "嫌孩子干等就调短，怕「刚放开又自己播起来」就调长。家长按【菜单键】输 PIN 随时能提前放行。", 13, Ui.DIM, false));
        k.addView(Ui.tv(this, "两个开关都是「按一下切换」，按钮上的字会跟着变成「开」或「关」，"
                + "上面的状态栏里也各有一行。", 13, Ui.DIM, false));
        root.addView(k);

        // ---- 内容白名单：命中就不拦（比内容黑名单优先）----
        LinearLayout w = Ui.card(this);
        w.addView(Ui.tv(this, "④ 内容白名单（命中就不拦，优先于黑名单）", 22, Ui.ACCENT, true));
        w.addView(Ui.gap(this, 6));
        w.addView(Ui.tv(this, "白名单比黑名单优先：同一部片子只要命中白名单，就算也命中关键字/片名名单也不会被拦。"
                + "适合「黑名单里填了「奥特曼」，但想放开某一部奥特曼」。", 14, Ui.DIM, false));
        w.addView(Ui.gap(this, 10));

        w.addView(Ui.tv(this, "关键字白名单（模糊匹配）", 15, Ui.TEXT, true));
        LinearLayout whkwBox = Ui.col(this);
        whkwBox.setId(1004);
        w.addView(whkwBox);
        w.addView(Ui.gap(this, 6));
        w.addView(buttonRow(new String[]{"加关键字", "清空关键字"}, new Runnable[]{
                new Runnable() { @Override public void run() { editWord("白名单关键字", "whKeywords"); }},
                new Runnable() { @Override public void run() {
                    if (!clearConfirmed("关键字白名单")) return;
                    Prefs.setList(ParentActivity.this, "whKeywords", new java.util.ArrayList<String>());
                    done();
                }}
        }));
        w.addView(Ui.gap(this, 12));

        w.addView(Ui.tv(this, "片名白名单（整名匹配）", 15, Ui.TEXT, true));
        LinearLayout whtitleBox = Ui.col(this);
        whtitleBox.setId(1005);
        w.addView(whtitleBox);
        w.addView(Ui.gap(this, 6));
        w.addView(buttonRow(new String[]{"加片名", "清空片名"}, new Runnable[]{
                new Runnable() { @Override public void run() { editWord("白名单片名", "whTitles"); }},
                new Runnable() { @Override public void run() {
                    if (!clearConfirmed("片名白名单")) return;
                    Prefs.setList(ParentActivity.this, "whTitles", new java.util.ArrayList<String>());
                    done();
                }}
        }));
        root.addView(w);

        // ---- 应用黑名单（打开要家长 PIN）----
        LinearLayout a = Ui.card(this);
        a.addView(Ui.tv(this, "⑤ 应用黑名单（打开要家长 PIN）", 22, Ui.ACCENT, true));
        a.addView(Ui.gap(this, 6));
        a.addView(Ui.tv(this, "作用＝给这些 App 加一把家长锁：孩子点开就盖一层「这个要家长开锁」，"
                + "要家长在电视上输 PIN，或在电脑控制台点「本次放行 / 今日放行」才能进。"
                + "放行只开锁：这些 App 的每日时长限制和内容过滤照常生效。", 14, Ui.DIM, false));
        a.addView(Ui.gap(this, 8));
        LinearLayout appBox = Ui.col(this);
        appBox.setId(1002);
        a.addView(appBox);
        a.addView(Ui.gap(this, 8));
        a.addView(buttonRow(new String[]{"从已装应用添加", "查看当前列表"}, new Runnable[]{
                new Runnable() { @Override public void run() { pickApp(); }},
                new Runnable() { @Override public void run() {
                    Ui.toast(ParentActivity.this, "已在下方显示");
                }}
        }));
        root.addView(a);

        // ---- 应用白名单（不过滤内容，时间照常）----
        LinearLayout aw = Ui.card(this);
        aw.addView(Ui.tv(this, "⑥ 应用白名单（不过滤内容，时间限制照常）", 22, Ui.ACCENT, true));
        aw.addView(Ui.gap(this, 6));
        aw.addView(Ui.tv(this, "这是唯一能让内容黑名单失效的地方：加进来的 App 不再看关键字/片名，"
                + "但每日时长限制、锁定时挂起照旧。适合「夸克网盘里的片子都是自己挑的」这类场景。", 14, Ui.DIM, false));
        aw.addView(Ui.gap(this, 8));
        LinearLayout appWhiteBox = Ui.col(this);
        appWhiteBox.setId(1006);
        aw.addView(appWhiteBox);
        aw.addView(Ui.gap(this, 8));
        aw.addView(buttonRow(new String[]{"从已装应用添加", "查看当前列表"}, new Runnable[]{
                new Runnable() { @Override public void run() { pickAppWhite(); }},
                new Runnable() { @Override public void run() {
                    Ui.toast(ParentActivity.this, "已在下方显示");
                }}
        }));
        root.addView(aw);

        // ---- 系统 ----
        LinearLayout s = Ui.card(this);
        s.addView(Ui.tv(this, "⑦ 系统状态与修复", 22, Ui.ACCENT, true));
        s.addView(Ui.gap(this, 8));
        s.addView(buttonRow(new String[]{"无障碍设置", "重新应用管控策略", "暂停管控", "恢复管控", "返回桌面"}, new Runnable[]{
                new Runnable() { @Override public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) { Ui.toast(ParentActivity.this, "打不开无障碍设置"); }
                }},
                new Runnable() { @Override public void run() {
                    String r = Admin.applyAll(ParentActivity.this);
                    Ui.toast(ParentActivity.this, "策略已应用");
                    Log.i("applyAll -> " + r);
                    done();
                }},
                new Runnable() { @Override public void run() {
                    Prefs.setGuardOn(ParentActivity.this, false);
                    Admin.unsuspendAll(ParentActivity.this);
                    done();
                }},
                new Runnable() { @Override public void run() {
                    Prefs.setGuardOn(ParentActivity.this, true);
                    done();
                }},
                new Runnable() { @Override public void run() { finish(); }}
        }));
        root.addView(s);

        LinearLayout about = Ui.card(this);
        about.addView(Ui.tv(this, "提示：遥控器输入不方便时，用电脑 adb 命令管理更省事，"
                + "命令见《家长手册》。返回键=回到本页。", 14, Ui.DIM, false));
        root.addView(about);
    }

    private LinearLayout buttonRow(String[] labels, Runnable[] actions) {
        LinearLayout wrap = Ui.col(this);
        LinearLayout line = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) { line = Ui.row(this); line.setLayoutParams(Ui.mwrap()); wrap.addView(line); }
            final Runnable r = actions[i];
            Button b = Ui.btn(this, labels[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { r.run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            b.setLayoutParams(lp);
            if (line != null) line.addView(b);
        }
        return wrap;
    }

    private void done() {
        GuardService.start(this);
        refreshStatus();
        listKeywords();
        listTitles();
        listWhKeywords();
        listWhTitles();
        listApps();
        listAppsWhite();
        restoreFocus();
    }

    private interface NumCb {
        void onNum(int v);
    }

    /** 电视上输数字：给一排常用值 + 数字键盘（不再用 AlertDialog，那个在电视上收不到按键）。 */
    private void editNum(String title, String[] presets, final int def, final NumCb cb) {
        if (tpad != null) return;
        rememberFocus();
        tpad = Ui.textPadOverlay(this, title, presets, false, new Ui.TextPadCb() {
            @Override public void onText(String s) {
                tpad = null;
                cb.onNum(num(s, def));
                done();
            }
            @Override public void onCancel() { tpad = null; done(); }
        });
    }

    /**
     * 家长从「已屏蔽」遮罩或「APP 门锁」里按了【菜单键】→ 走这条路：验 PIN。
     * 输对了：内容遮罩场景放行该 App 30 分钟；APP 门锁场景就是开锁（也只开锁，不过滤照常）。
     * 取消 = 不放行。
     */
    private void askAllowPin() {
        if (tpad != null) return;
        boolean lock = false;
        try { lock = getIntent() != null && getIntent().getBooleanExtra("lock_mode", false); } catch (Throwable ignored) { }
        final boolean isLock = lock;
        tpad = Ui.textPadOverlay(this, isLock ? "家长 PIN（输对后开锁）" : "家长 PIN（输对后允许播放 30 分钟）",
                new String[]{}, false, new Ui.TextPadCb() {
            @Override public void onText(String s) {
                tpad = null;
                boolean unlocked = false;
                try {
                    if (Prefs.checkPin(ParentActivity.this, s)) {
                        if (allowPkg != null && allowPkg.length() > 0) {
                            Prefs.setAllowUntil(ParentActivity.this, allowPkg,
                                    System.currentTimeMillis() + GuardAccessibilityService.ALLOW_MS);
                            unlocked = true;
                            Ui.toast(ParentActivity.this, isLock
                                    ? "已开锁 30 分钟（时间限制与内容过滤照常生效）"
                                    : "已允许播放 30 分钟（到期后自动恢复拦截）");
                            Log.i("ALLOW pkg=" + allowPkg + " 30min lock=" + isLock);
                        } else {
                            Ui.toast(ParentActivity.this, "PIN 正确（没有指定要放行的应用）");
                            unlocked = true;
                        }
                    } else {
                        Ui.toast(ParentActivity.this, "PIN 不对");
                    }
                } catch (Throwable t) {
                    Log.w("allow pin: " + t);
                }
                // 门锁场景下只有在真开锁时才撤锁；没输对就保持锁定（离开界面时黑幕会盖回来）。
                if (!isLock || unlocked) GuardAccessibilityService.endCover();
                finish();
            }
            @Override public void onCancel() {
                tpad = null;
                // 门锁场景：取消 = 不开锁，锁保留，靠 onStop 的 parentClosed() 把黑幕盖回来。
                if (!isLock) GuardAccessibilityService.endCover();
                finish();
            }
        });
    }

    private void editPin() {
        if (tpad != null) return;
        rememberFocus();
        tpad = Ui.textPadOverlay(this, "新的 PIN（4-8 位数字；留空 = 取消 PIN）",
                new String[]{"1234", "0000", "1688", "8888"}, false, new Ui.TextPadCb() {
            @Override public void onText(String s) {
                tpad = null;
                Prefs.setPin(ParentActivity.this, s);
                Ui.toast(ParentActivity.this, s.isEmpty() ? "已取消 PIN" : "已设置 PIN：" + s);
                done();
            }
            @Override public void onCancel() { tpad = null; done(); }
        });
    }

    /** 在电视上编辑一个名单（关键字 / 片名）。连续添加模式：加一个留在面板里，加完按「完成（返回）」。 */
    private void editWord(String label, final String key) {
        if (tpad != null) return;
        rememberFocus();
        tpad = Ui.textPadOverlay(this, "添加" + label + "：加一个留一个，加完按「完成（返回）」",
                WORD_PRESETS, true, new Ui.TextPadCb() {
            @Override public void onText(String s) {
                tpad = null;
                Prefs.addList(ParentActivity.this, key, s);
                Ui.toast(ParentActivity.this, "已加入：" + s);
                done();
                restoreFocus();
            }
            @Override public void onCancel() {
                tpad = null;
                done();
                restoreFocus();
            }
        }, new Ui.TextPadAdd() {
            @Override public void onAdd(String s) {
                // 连续加：加完留在面板里，背后的名单/计数立刻刷新（面板盖在上面，不会被收走）
                boolean had = Prefs.split(Prefs.sp(ParentActivity.this).getString(key, "")).contains(s);
                Prefs.addList(ParentActivity.this, key, s);
                Ui.toast(ParentActivity.this,
                        had ? "「" + s + "」已经在名单里了" : "已加入：" + s + "（接着加，或按「完成（返回）」）");
                done();
            }
        });
    }

    /**
     * 面板关掉之后把焦点还给主界面。
     * 真机反馈（BUG1）：加完词条退回主界面后「只能上下翻页、选不到任何按钮」——
     * 根因是覆盖层打开时把底下的界面设成了 FOCUS_BLOCK_DESCENDANTS 却没还原（已在 Ui.TextPad.hide 里修），
     * 这里再补一道保险：确保确实有个按钮拿到焦点。
     */
    private void restoreFocus() {
        if (tpad != null) return;
        try {
            final ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
            if (content == null) return;
            content.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (tpad != null) return;
                        // 优先把焦点还给「打开面板之前那个按钮」：既不会丢焦点，也不会被滚到最顶端。
                        if (lastFocus != null && lastFocus.isShown() && !isScrollOnly(lastFocus)) {
                            lastFocus.requestFocus();
                            return;
                        }
                        View f = content.findFocus();
                        if (f != null && f.isShown() && !isScrollOnly(f)) return;
                        View t = firstFocusable(content);
                        if (t != null) { t.requestFocus(); Log.i("focus -> " + t.getClass().getSimpleName()); }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 开覆盖层之前记一下焦点在哪个按钮上，关掉之后再还回去。 */
    private void rememberFocus() {
        try {
            View f = getCurrentFocus();
            if (f != null && !isScrollOnly(f)) lastFocus = f;
        } catch (Throwable ignored) { }
    }

    /** 滚动容器（含 Activity 自己那层 ScrollView）拿焦点只会「上下翻页」，不能算可用焦点。 */
    private boolean isScrollOnly(View v) {
        if (v == null) return false;
        if (v instanceof ScrollView) return true;
        String cn = v.getClass().getName();
        return cn.contains("ScrollView");
    }

    private View firstFocusable(View v) {
        if (v == null) return null;
        // 滚动容器自己也能拿焦点，但它只会「上下翻页」——正是要避免的结果，所以跳过它本身。
        if (!(v instanceof ScrollView) && v.isFocusable() && v.isShown()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = firstFocusable(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 清空的二次确认：同一个名单在 6 秒内再按一次确定才真清空（防止误按一次就全没了）。 */
    private boolean clearConfirmed(String what) {
        long now = System.currentTimeMillis();
        if (what.equals(pendingClear) && now - pendingClearAt < CLEAR_CONFIRM_MS) {
            pendingClear = null;
            return true;
        }
        pendingClear = what;
        pendingClearAt = now;
        Ui.toast(this, "要清空" + what + "？（6 秒内再按一次确定就清空）");
        return false;
    }

    private void addPresets(String key) {
        int n = 0;
        List<String> cur = Prefs.split(Prefs.sp(this).getString(key, ""));
        for (int i = 0; i < WORD_PRESETS.length; i++) {
            if (!cur.contains(WORD_PRESETS[i])) { Prefs.addList(this, key, WORD_PRESETS[i]); n++; }
        }
        Ui.toast(this, "已加入 " + n + " 个常见词条（重复的不加）");
        done();
    }

    private void refreshStatus() {
        if (statusText == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("今日已用 ").append(Prefs.usedToday(this) / 60).append(" 分钟 / 上限 ")
          .append(Prefs.dailyMin(this) + Prefs.extraToday(this)).append(" 分钟")
          .append("   剩余 ").append(Prefs.remainSec(this) / 60).append(" 分钟\n");
        sb.append("是否锁定: ").append(Prefs.isLocked(this) ? "是" : "否")
          .append("   放行: ").append(Prefs.freeToday(this) ? "是" : "否")
          .append("   强制锁: ").append(Prefs.forceLock(this) ? "是" : "否")
          .append("   强锁模式: ").append(Prefs.hardLock(this) ? "开" : "关")
          .append("   管控: ").append(Prefs.guardOn(this) ? "开" : "关").append('\n');
        sb.append("设备所有者: ").append(Admin.isOwner(this) ? "是（可挂起应用）" : "否（仅锁屏，降级模式）")
          .append("   管理员: ").append(Admin.isAdmin(this) ? "是" : "否").append('\n');
        sb.append("无障碍读屏: ").append(CommandReceiver.accEnabled(this) ? "已启用" : "未启用（黑名单拦截会失效）").append('\n');
        sb.append("PIN: ").append(Prefs.hasPin(this) ? "已设置" : "未设置").append('\n');
        String err = Prefs.sp(this).getString("lastLockErr", "");
        sb.append("上次挂起: ").append(err.isEmpty() ? "正常" : err).append('\n');
        sb.append("黑名单: 关键字 ").append(Prefs.blKeywords(this).size()).append(" 条 / 片名 ")
          .append(Prefs.blTitles(this).size()).append(" 条\n");
        sb.append("内容兜底: ").append(Prefs.blPlayBlock(this) ? "开（页面上有名单里的字就盖）" : "关（不盖黑幕，只吞确定键）")
          .append("   预览严格模式: ").append(Prefs.strictPreview(this) ? "开（读不到片名的自动预览也盖）" : "关")
          .append("   遮罩时长: ").append(Prefs.coverSec(this)).append(" 秒").append('\n');
        statusText.setText(sb.toString());
        listKeywords();
        listTitles();
        listApps();
        updateToggles();
    }

    /** 把两个开关的当前状态写进按钮文字（家长不用去翻上面的状态栏，也不用猜）。 */
    private void updateToggles() {
        if (btnCover != null) {
            boolean on = Prefs.blPlayBlock(this);
            btnCover.setText("内容兜底：" + (on ? "开" : "关") + "（按一下" + (on ? "关" : "开") + "）");
            btnCover.setAlpha(on ? 1f : 0.5f);
        }
        if (btnStrict != null) {
            boolean on = Prefs.strictPreview(this);
            btnStrict.setText("预览严格模式：" + (on ? "开" : "关") + "（按一下" + (on ? "关" : "开") + "）");
            btnStrict.setAlpha(on ? 1f : 0.5f);
        }
        if (btnCoverSec != null) {
            int sec = Prefs.coverSec(this);
            btnCoverSec.setText("黑名单遮罩时长：" + sec + " 秒（按一下改，1~10）");
            btnCoverSec.setAlpha(sec >= 10 ? 1f : 0.85f);
        }
    }

    private void listKeywords() {
        listWords(1001, Prefs.blKeywords(this), "blKeywords", "关键字");
    }

    private void listTitles() {
        listWords(1003, Prefs.blTitles(this), "blTitles", "片名");
    }

    private void listWhKeywords() {
        listWords(1004, Prefs.whKeywords(this), "whKeywords", "白名单关键字");
    }

    private void listWhTitles() {
        listWords(1005, Prefs.whTitles(this), "whTitles", "白名单片名");
    }

    /** 四个内容名单共用一套渲染（box id / 数据 / 存储键 / 提示名）。 */
    private void listWords(int boxId, List<String> words, final String key, String what) {
        LinearLayout box = (LinearLayout) root.findViewById(boxId);
        if (box == null) return;
        box.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < words.size(); i++) {
            final String k = words.get(i);
            if (i % 5 == 0) { line = Ui.row(this); line.setLayoutParams(Ui.mwrap()); box.addView(line); }
            Button b = Ui.ghostBtn(this, k + " ✕");
            final String label = what;
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!clearConfirmed(label + "「" + k + "」")) return;
                    Prefs.delList(ParentActivity.this, key, k); done();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            b.setLayoutParams(lp);
            if (line != null) line.addView(b);
        }
        if (words.isEmpty()) box.addView(Ui.tv(this, "（空）", 14, Ui.DIM, false));
    }

    private void listApps() {
        listAppNames(1002, Prefs.appBlack(this), "appBlack");
    }

    private void listAppsWhite() {
        listAppNames(1006, Prefs.appWhite(this), "appWhite");
    }

    /** APP 黑/白名单共用渲染：显示应用中文名，点一下移出。 */
    private void listAppNames(int boxId, List<String> pk, final String key) {
        LinearLayout box = (LinearLayout) root.findViewById(boxId);
        if (box == null) return;
        box.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < pk.size(); i++) {
            final String k = pk.get(i);
            if (i % 3 == 0) { line = Ui.row(this); line.setLayoutParams(Ui.mwrap()); box.addView(line); }
            String label = k;
            try {
                label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(k, 0)));
            } catch (Throwable ignored) { }
            final String lbl = label;
            Button b = Ui.ghostBtn(this, label + " ✕");
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!clearConfirmed("应用「" + lbl + "」")) return;
                    Prefs.delList(ParentActivity.this, key, k); done();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            b.setLayoutParams(lp);
            if (line != null) line.addView(b);
        }
        if (pk.isEmpty()) box.addView(Ui.tv(this, "（空）", 14, Ui.DIM, false));
    }

    private void pickApp() {
        pickAppList("点一下加入 / 移出【应用黑名单】（★=已在名单里，打开要家长 PIN）",
                new String[]{"appBlack"}, new boolean[]{true});
    }

    private void pickAppWhite() {
        pickAppList("点一下加入 / 移出【应用白名单】（★=已在名单里，不过滤内容）",
                new String[]{"appWhite"}, new boolean[]{true});
    }

    /**
     * APP 名单选择的覆盖层。keys 目前只传一个键（黑名单或白名单），保留数组是为了以后能合并展示。
     */
    private void pickAppList(String title, final String[] keys, final boolean[] isBlack) {
        if (appMask != null) return;
        rememberFocus();
        final String key = keys[0];
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16));
        box.addView(Ui.tv(this, title, 20, Ui.TEXT, true));
        box.addView(Ui.gap(this, 8));
        List<ResolveInfo> list = Ui.launchables(this);
        LinearLayout line = null;
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            final String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (n % 2 == 0) { line = Ui.row(this); line.setLayoutParams(Ui.mwrap()); box.addView(line); }
            n++;
            final boolean in = Prefs.split(Prefs.sp(this).getString(key, "")).contains(pkg);
            Button b = Ui.btn(this, (in ? "★ " : "＋ ") + ri.loadLabel(getPackageManager()), in ? Ui.OKC : 0x33FFFFFF);
            b.setTextColor(in ? 0xFF0B1020 : Ui.TEXT);
            Ui.bindAction(null, b, new Runnable() {
                @Override public void run() {
                    if (appMask == null) return;
                    if (Prefs.split(Prefs.sp(ParentActivity.this).getString(key, "")).contains(pkg)) {
                        Prefs.delList(ParentActivity.this, key, pkg);
                    } else {
                        Prefs.addList(ParentActivity.this, key, pkg);
                    }
                    Ui.removeOverlay(appMask);
                    appMask = null;
                    done();
                    Ui.toast(ParentActivity.this, pkg + " 已更新");
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            b.setLayoutParams(lp);
            if (line != null) line.addView(b);
        }
        box.addView(Ui.gap(this, 10));
        LinearLayout acts = Ui.row(this);
        acts.setLayoutParams(Ui.mwrap());
        Button close = Ui.btn(this, "关闭", Ui.OKC);
        Ui.bindAction(null, close, new Runnable() {
            @Override public void run() { closeApps(); }
        });
        close.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        acts.addView(close);
        box.addView(acts);
        appMask = Ui.overlay(this, box, 1020, 650);
    }

    private void closeApps() {
        Ui.removeOverlay(appMask);
        appMask = null;
        done();
    }

    private int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
