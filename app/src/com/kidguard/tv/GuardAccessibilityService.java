package com.kidguard.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内容守卫（电视版）。
 *
 * 电视上没有鼠标，只有「焦点框」——遥控器上下左右移动的那个高亮框（有的 App 画成描边，有的画成放大）。
 * 所以判定依据 = 焦点框当前框住的那一项的文案，而不是鼠标悬停位置。
 *
 * 三层：
 *   ① 焦点框停在黑名单项（关键字名单 ∪ 片名名单）上时，孩子按「确定」→ 我们把确认键吞掉
 *      （onKeyEvent 返回 true），目标 App 根本收不到这次点击 → 表现是「点了没反应」，不会把他踢出去。
 *      焦点框挪开了但页面上还带着黑名单字（例如焦点停在「播放」按钮上、片名在旁边的标题里），
 *      第二道就靠页面级命中缓存（pageHit）继续吞确认键——所以**连点也放不出来**。
 *   ② 万一内容还是播起来了（有些 App 焦点一停就自动预览），兜底只做一件事：**盖一层「已屏蔽」黑幕**
 *      （时长家长可调，默认 10 秒，见 watchdog / cover）。两个开关各管各的、互不重复：
 *        ·「内容兜底」blPlayBlock＝页面上真的有黑名单字才盖；
 *        ·「预览严格模式」strictPreview＝读不到片名的自动预览（艺术字海报）也盖。
 *      **绝不按返回、绝不切集、绝不发媒体键、不记违规**——用户明确要求「彻底去掉切集这件事」。
 *      注意播放器开始播之后常常一个无障碍事件都不发，所以除了事件驱动，
 *      还有一条 3 秒的定时巡检（`watchTick`）负责发现「已经播起来了」。
 *
 * 名单语义：关键字 = 模糊包含（「熊出没」命中「熊出没之怪兽计划」）；
 *           片名   = 完整片名（写全名更准）。两个名单任一命中都算拦。
 */
public class GuardAccessibilityService extends AccessibilityService {

    /** 两次「已屏蔽」遮罩之间至少间隔多久（遮罩本身 10 秒，这里是给轮空留的余量）。 */
    private static final long BLOCK_COOLDOWN = 2500L;
    /** 焦点命中的有效期：超过这个时间没有刷新过焦点，就不再吞确认键（防止焦点移开后误吞）。 */
    private static final long FOCUS_TTL = 8000L;
    private static final int MAX_NODES = 300;
    /**
     * 孩子按过确定之后的「这是我自己挑的」宽限时间。
     * 预览严格模式（{@link Prefs#strictPreview}）只盖「没按确定就自己放起来的」；
     * 在这个时间内按过确定，就认定是他自己点开想看，不动它。
     */
    private static final long CONFIRM_GRACE = 20000L;
    /**
     * 「预览严格模式」要求画面连续出声多久才算「真的在预览」。
     * 真机反馈（m03316 BUG3）：这条判据原来太灵敏——孩子拿遥控器扫一遍海报墙，
     * 每张海报的自动预览都会立刻触发一次 10 秒黑幕，「一下切很多」。
     * 现在要求连续播放 {@link #PREVIEW_MIN_MS} 才动手，路过式的预览不再拦。
     */
    private static final long PREVIEW_MIN_MS = 5000L;
    /** 预览严格模式拦过一次之后，至少歇这么久再拦第二次（免得黑幕一个接一个）。 */
    private static final long PREVIEW_COOLDOWN = 20000L;
    /**
     * 刚吞过一次确定键之后，这个时间内再来确定键一律继续吞。
     * 真机反馈（m03359）：「点播放没反应，但连续快速点播放能放出来」——
     * 快速连点时中间会插进无障碍事件，把焦点命中刷掉一帧，那一帧的确定键就漏过去了。
     */
    private static final long REPEAT_SWALLOW_MS = 1500L;
    /**
     * 焦点框没挪位置时，即使这一帧读到的文案变了/读空了，也维持上一次的命中判定这么久。
     * （自动预览、动画、动态文字都会让同一格反复读出不同内容。）
     */
    private static final long STICKY_HIT_MS = 4000L;

    private long lastBlock = 0L;
    /** 这一次连续播放是从什么时候开始的（停播就清零）。 */
    private long playSince = 0L;
    /** 上一次「预览严格模式」触发黑幕的时刻。 */
    private long lastPreviewCover = 0L;
    /** 孩子最后一次按「确定」的时刻（onKeyEvent 里记录；watchdog 在工作线程上读它，所以是 volatile）。 */
    private volatile long lastConfirmAt = 0L;
    /** WATCH 巡检日志节流：没命中时最多 10 秒打一条，免得日志被刷爆。 */
    private long lastWatchLog = 0L;

    /**
     * 方向键防抖：上一次方向键的键码与时刻。
     * 真机反馈（m01824）：B站/优酷 的搜索键盘和选集条「一下滑很多」（一次按下走好几格），
     * 但当天 SWALLOW=0（我们一次都没吞键）→ 说明是**一次按下产生了多个按键事件**
     * （遥控器长按连发 / 无障碍过滤后的重复投递 / 事件延迟后成串到达），
     * 所以在 onKeyEvent 里对同一方向键做时间去抖，见那边的说明。
     */
    private int lastDirCode = -1;
    private long lastDirAt = 0L;
    /** 上一次按键事件（任意键）的时刻，只用来打 K 行诊断日志。 */
    private long lastKeyAt = 0L;

    /** 当前运行中的服务实例（给广播命令 cover-now / a11y-dump 做手工测试用）。 */
    public static volatile GuardAccessibilityService I = null;

    /**
     * 「已屏蔽」遮罩时长不再写死：改成家长端调（{@link Prefs#coverSec}，1~10 秒，默认 10），见 {@link #coverMs()}。
     * 命中被拦的内容（或严格预览判出「没按确定就自己放起来的预览」）时，盖一层黑幕 + 提示，
     * **不退出 App、不记违规、不累计**；这段时间里遥控器按不动，到期自动放开。
     */

    /** 家长按【菜单键】输对 PIN 后，允许这个 App 播放多久（30 分钟）。 */
    public static final long ALLOW_MS = 30L * 60L * 1000L;
    /** 遮罩到期时刻（0 / 过去时间 = 没在遮罩）。 */
    private volatile long coverUntil = 0L;

    /**
     * 「页面级命中」缓存：watchdog 是用**整窗文字**判黑名单的（首页/详情页的片名都在整窗文字里），
     * 而 onKeyEvent 的 freshHit() 只认**焦点节点自己**的文字。
     * 在「奶龙」详情页焦点停在「播放」按钮上（按钮文字不含「奶龙」）→ freshHit() 必然是空，
     * 于是遮罩轮空的那一两秒里按「确定」根本吞不住 —— 孩子趁空档猛按就能把片子点起来。
     * 所以把 watchdog 算出来的页面级命中留一份，按确定时用它兜底。
     */
    private static final long PAGE_HIT_MS = 12000L;
    private volatile String pageHitPkg = null;
    private volatile String pageHitWord = null;
    private volatile String pageHitKind = null;
    private volatile long pageHitAt = 0L;
    /** 现场整窗扫描的节流（连点时不要每一下都把树走一遍）。 */
    private static final long SLOW_HIT_MS = 400L;
    private long lastSlowHitAt = 0L;
    /** 遮罩针对的包名（家长输 PIN 放行的就是它）。 */
    private volatile String coverPkg = null;
    /** 遮罩原因（词条名 / 自动预览）。 */
    private volatile String coverWhy = "";
    /** playingFor() 最近一次的探测明细，只用来打日志。 */
    private volatile String audioInfo = "";
    /** 遮罩上的倒计时文本框（每 500ms 刷一次）。 */
    private TextView coverLeft;

    /** 当前焦点框命中的词条（null = 没命中）。 */
    private volatile String focusHit = null;
    private volatile String focusKind = "";
    private volatile long focusHitAt = 0L;

    /** 已吞掉的按键，连抬手一起吞（否则目标 App 会收到一个孤立的 UP 事件）。 */
    private volatile int swallowCode = -1;
    /** 最近一次真的吞掉确定键的时刻（用来挡住「连续快点」）。 */
    private volatile long lastSwallowAt = 0L;
    /**
     * 最近一次「确定键的 DOWN」到底吞没吞。
     * DOWN/UP 必须严格配对：只吞 DOWN 不吞 UP，App 会收到一个孤立的 UP（很多 App 把它当点击）；
     * 只吞 UP 不吞 DOWN，App 会收到一个孤立的 DOWN（有些 TV App 会当成「长按」→ 自己连跳好几项，
     * 现象就是孩子说的「一下切很多」）。所以抬手要不要吞，取决于按下时吞没吞。
     */
    private volatile boolean lastConfirmSwallowed = false;
    /** 上次命中时焦点框的位置字符串（焦点没挪地方就维持命中）。 */
    private volatile String focusHitBox = null;

    private View overlay;
    /** 诊断开关：置 true 后，下一次事件会把活动窗口的节点树打进 logcat。 */
    public static volatile boolean DUMP_TREE = false;
    private long lastDiag = 0L;
    private long lastDiag2 = 0L;
    private long lastScan = 0L;
    /** 兜底扫描（遍历活动窗口找 isFocused/isSelected 的节点）的节流间隔。 */
    private static final long SCAN_INTERVAL = 250L;
    /**
     * 兜底的定时巡检间隔。
     * 播放器开始播之后往往**一个无障碍事件都不再发**（实测 Play Music 的预览页就是如此），
     * 光靠事件驱动会漏掉「已经播起来了」这件事，所以这里再加一条固定心跳。
     */
    private static final long WATCH_INTERVAL = 3000L;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * ★★ 后台工作线程（2026-10-10 修复「方向键/封面/选集不受控滑动」）★★
     *
     * 无障碍服务的 onKeyEvent 和 onAccessibilityEvent 跑在**同一条主线程**上，
     * 而按键过滤回调必须等主线程腾出手，才能把「吞掉 / 放行」的结论交回系统。
     * 原来事件回调里**当场爬节点树**（getRootInActiveWindow + updateFocus/scanFocus/watchdog，
     * 优酷首页的节点又多又深）**而且每次 Log.i 都同步写 /sdcard**（FUSE，慢），
     * 主线程一卡就是 0.3~1 秒 ⇒ App 晚收到按键、DOWN/UP 间隔被拉长 ⇒ App 自己触发长按连跳
     * ⇒ 现象就是用户说的「一下滑很多 / 不知道滑哪里去了」。
     *
     * 真机 A/B（同样 3 次按住 0.7s 的注入）：
     *   我们的服务开着 → App 可见按住 1337 / 998 / 731 ms（比物理时长最多 +500ms）
     *   我们的服务关掉 → App 可见按住 829 / 881 / 849 ms（= 物理时长）
     * 也直接量到我们自己的按键回调延迟 lat=315/323/505/1009ms（B站搜索页和当贝桌面只有 1~4ms）。
     *
     * 所以现在：**按键路径只读内存缓存**，所有节点树遍历都搬到这条线程上，并且合并节流
     * （同一时刻只跑一轮，跑的时候又来新需求就再补一轮）。
     */
    private HandlerThread workerThread;
    private volatile Handler work;
    /** 最新一次事件的包名（持续保留，给后台重算用）。 */
    private volatile String pendingPkg = null;
    /** 「有窗口/内容变化」脏标记：决定要不要顺带跑一遍很重的 watchdog 整窗扫描。 */
    private volatile boolean pendingWin = false;
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final Runnable refreshRun = new Runnable() {
        @Override public void run() { doRefresh(); }
    };

    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            // 定时器本身留在主线程（只做重排），真正的巡检（爬树）丢给工作线程
            main.removeCallbacks(this);
            main.postDelayed(this, WATCH_INTERVAL);
            final Handler w = work;
            if (w == null) return;
            w.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (guardActive() && audioActive()) {
                            String p = activePkg();
                            if (p != null && p.length() > 0 && !p.equals(getPackageName())) watchdog(p);
                        }
                    } catch (Throwable t) {
                        Log.w("watchTick: " + t);
                    }
                }
            });
        }
    };

    /** 当前活动窗口属于哪个包（定时巡检用；拿不到就返回 null）。 */
    private String activePkg() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return null;
            CharSequence p = r.getPackageName();
            return p == null ? null : p.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.bind(this);
        Prefs.ensureDefaults(this);
        I = this;
        // 起工作线程：所有节点树遍历都在它上面跑，主线程只负责按键/事件的「记账」
        try {
            if (workerThread == null) {
                HandlerThread t = new HandlerThread("kg-a11y-work");
                t.start();
                workerThread = t;
                work = new Handler(t.getLooper());
            }
        } catch (Throwable t) {
            Log.w("a11y worker: " + t);
        }
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_FOCUSED
                        | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                        | AccessibilityEvent.TYPE_VIEW_SELECTED
                        | AccessibilityEvent.TYPE_VIEW_CLICKED
                        | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.flags = AccessibilityServiceInfo.DEFAULT
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                info.notificationTimeout = 100;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w("a11y setInfo: " + t);
        }
        main.removeCallbacks(watchTick);
        main.postDelayed(watchTick, WATCH_INTERVAL);
        Log.i("a11y connected");
    }

    // ---------------- 按键过滤：把「确定」吞掉 = 点了没反应 ----------------

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        try {
            if (e == null) return false;
            int code = e.getKeyCode();
            final long now = System.currentTimeMillis();
            // 诊断：每个按键都记一笔（K 行，不节流）。用来回答「一次按下到底来了几个事件」：
            //   rep>0            = 系统/遥控器长按连发
            //   rep=0 且 gap 很小的一串 = 重复投递，或者被无障碍过滤后成串到达
            if (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP) {
                // hold = 这一下在系统看来按住了多久（eventTime - downTime），抬手那行就是整段按住的时长；
                // lat  = 事件产生到我们收到之间的延迟（uptime 时钟），用来看「我们是不是把按键卡住了」。
                Log.i("K " + (e.getAction() == KeyEvent.ACTION_DOWN ? "d" : "u")
                        + " code=" + code + " rep=" + e.getRepeatCount()
                        + " gap=" + (lastKeyAt == 0L ? -1L : now - lastKeyAt) + "ms"
                        + " hold=" + (e.getEventTime() - e.getDownTime()) + "ms"
                        + " lat=" + (SystemClock.uptimeMillis() - e.getEventTime()) + "ms");
                lastKeyAt = now;
            }
            // 遮罩期间（默认 10 秒）：除了音量键和「家长按菜单键」，其余按键全部吞掉
            // —— 孩子看到的是「黑幕 + 这个先不能看」，遥控器按不动；不退出 App、不记违规。
            if (covering()) {
                if (isVolume(code)) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_MENU) {
                        askParentAllow();
                        return true;
                    }
                    // 记下「这一下吞了」：万一遮罩正好在按下与抬起之间结束，
                    // 抬手（UP）也必须继续吞 —— 孤立的 UP 会被 App 当成一次点击。
                    swallowCode = code;
                    lastSwallowAt = now;
                    lastConfirmSwallowed = isConfirm(code);
                    return true;
                }
                if (swallowCode == code) swallowCode = -1;
                return true;
            }
            // ---- 方向键防抖：一次按下只走一格 ----
            // 真机反馈（m01824）：B站/优酷 的搜索键盘和选集条「一下滑很多」，孩子停不住。
            // 当天 SWALLOW=0（吞键与遮罩一次都没触发）→ 问题不在吞键路径，而是**一次按下产生了多个
            // 按键事件**（遥控器长按连发 / 无障碍过滤后的重复投递 / 事件延迟后成串到达）。
            // 对策：同一个方向键在 dirMin 毫秒内的重复「按下」一律吞掉（窗口从上次真正放行的按下起算，
            // 所以按住不放也只是匀速走、不会越按越快），抬手照常放行。
            // 抬手（UP）绝不能跟着吞 —— 只吞 DOWN 不吞 UP 会留下一个孤立的 DOWN，
            // 有些 TV App 会把它当「长按」自己连跳好几项（那正是「一下连跳」的另一个来源）。
            if (isDirection(code) && guardActive()) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    int dm = Prefs.dirMinMs(this);
                    long gap = lastDirAt == 0L ? Long.MAX_VALUE : now - lastDirAt;
                    if (dm > 0 && (e.getRepeatCount() > 0 || (code == lastDirCode && gap < dm))) {
                        Log.i("SWALLOW(方向防抖) code=" + code + " rep=" + e.getRepeatCount()
                                + " gap=" + (gap == Long.MAX_VALUE ? "-" : gap + "ms"));
                        // 注意：这里**不**刷新 lastDirAt —— 窗口从「上一次真正放行的按下」起算，
                        // 于是一直按住不放时大约每 dirMin 毫秒才走一格（限速），而不是越按越快。
                        lastDirCode = code;
                        return true;
                    }
                    lastDirCode = code;
                    lastDirAt = now;
                }
                // UP 落到下面的配对逻辑（那里 swallowCode=-1 会放行）
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                // 抬手吞不吞，主要看按下时吞没吞（DOWN/UP 严格配对），
                // 「连续快点」再补一刀：上一次确定刚被吞、这一下也已经被吞了，那抬手也得跟上。
                if (swallowCode == code
                        || (isConfirm(code) && lastConfirmSwallowed
                            && now - lastSwallowAt < REPEAT_SWALLOW_MS)) {
                    swallowCode = -1;
                    lastConfirmSwallowed = false;
                    return true;
                }
                return false;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (!isConfirm(code)) return false;
            // 记下「孩子自己按了确定」——用来区分「孩子挑的」和「焦点停靠自动放起来的」。
            // 见 watchdog 里 strictPreview 的用法。
            long nt = now;
            lastConfirmAt = nt;
            if (!guardActive()) {
                lastConfirmSwallowed = false;
                return false;
            }
            // ★ 按键路径只读内存缓存，绝不在这里爬节点树（见 worker 字段上的说明）。
            // 按下的这一刻爬一次树要几百毫秒，按键过滤就得等我们几百毫秒，
            // App 于是把「点按」当「按住」自己连跳 —— 那正是用户看到的「一下滑很多」。
            // 命中由后台线程随事件持续刷新；万一缓存刚过期，就让后台补一次，这一下按下面的页面级缓存兜底。
            String hit = freshHit();
            if (hit == null) requestRefresh(null, false);
            if (hit == null && nt - lastSwallowAt < REPEAT_SWALLOW_MS) {
                // 刚吞过一次，紧接着又来（连续快点）：中间那一两帧的焦点命中被刷掉了也算数
                Log.i("SWALLOW(连点) key=" + code + " 距上次吞=" + (nt - lastSwallowAt) + "ms");
                swallowCode = code;
                lastSwallowAt = nt;
                lastConfirmSwallowed = true;
                return true;
            }
            if (hit == null) {
                // 页面级命中（整窗文字带黑名单词，和 watchdog 同一条判据）：
                // 遮罩轮空的那一两秒里焦点常常停在「播放」按钮上，按钮文字不含片名，
                // freshHit() 必然是空的 —— 只有这一层能把那一下「确定」吞住。
                // 不补这一层，孩子就会「先按住等黑幕消失，趁着空档猛按确定」，片子照样点起来。
                String[] ph = pageHitCached();
                if (ph != null) {
                    swallowCode = code;
                    lastSwallowAt = nt;
                    lastConfirmSwallowed = true;
                    Log.i("SWALLOW(页面) key=" + code + " kind=" + ph[1] + " hit=" + ph[0]);
                    return true;
                }
                lastConfirmSwallowed = false;
                return false;
            }
            swallowCode = code;
            lastSwallowAt = nt;
            lastConfirmSwallowed = true;
            Log.i("SWALLOW key=" + code + " kind=" + focusKind + " hit=" + hit);
            return true;
        } catch (Throwable t) {
            Log.e("a11y onKey", t);
            return false;
        }
    }

    /**
     * 页面级命中：把当前活动窗口的整窗文字扫一遍，看有没有黑名单词。
     * 与 {@link #watchdog} 用的是同一条判据（有播放控制条时只认屏幕上方的标题栏，免得被推荐位带偏）。
     * 先用 watchdog 留下的缓存（免费），缓存过期或换了 App 才真的走树，并节流 {@link #SLOW_HIT_MS}。
     *
     * @return {词条, "关键字"|"片名"}；没命中返回 null
     */
    private String[] pageHit(String pkg) {
        long now = System.currentTimeMillis();
        if (pageHitWord != null && now - pageHitAt < PAGE_HIT_MS
                && (pkg == null || pageHitPkg == null || pkg.equals(pageHitPkg))) {
            return new String[]{ pageHitWord, pageHitKind };
        }
        if (now - lastSlowHitAt < SLOW_HIT_MS) return null;
        lastSlowHitAt = now;
        if (pkg != null && Prefs.isAllowed(this, pkg)) return null;
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root == null) return null;
        StringBuilder all = new StringBuilder();
        StringBuilder top = new StringBuilder();
        boolean[] media = new boolean[]{ false };
        boolean[] bar = new boolean[]{ false };
        boolean[] strong = new boolean[]{ false };
        double[] mfrac = new double[]{ 0d };
        int[] budget = new int[]{ MAX_NODES };
        walk(root, all, top, 0, budget, media, bar, strong, mfrac);
        String probe = bar[0] ? top.toString() : all.toString();
        String[] hit = Prefs.matchBlacklist(this, probe);
        if (hit != null) {
            pageHitPkg = pkg;
            pageHitWord = hit[0];
            pageHitKind = hit[1];
            pageHitAt = now;
        } else if (pkg != null && pkg.equals(pageHitPkg)) {
            // 同一页面上已经读不到黑名单词了（换页了）→ 缓存作废，别拿着旧命中乱吞按键
            pageHitWord = null;
        }
        return hit;
    }

    private static boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    /** 四个方向键（「左右切集、上下切集」用的就是它们）。 */
    private static boolean isDirection(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** 音量键在遮罩里也要放行（家长可能想调音量）。 */
    private static boolean isVolume(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    /**
     * 遮罩里家长按了【菜单键】→ 打开家长界面并要求输 PIN；输对了就放行这个 App 30 分钟。
     */
    private void askParentAllow() {
        String pkg = coverPkg == null ? "" : coverPkg;
        Log.i("COVER ask-parent pkg=" + pkg);
        try {
            Intent it = new Intent(this, ParentActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("need_pin", true);
            it.putExtra("allow_pkg", pkg);
            startActivity(it);
        } catch (Throwable t) {
            Log.w("ask parent fail: " + t);
        }
        // 家长输 PIN 的这段时间继续挡着；输完（或取消）由 ParentActivity 调 endCover()
        coverUntil = System.currentTimeMillis() + 120000L;
        if (coverLeft != null) coverLeft.setText("请家长输入 PIN…");
    }

    // ---------------- 事件：维护「焦点框里是什么」 ----------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null) return;
            int type = e.getEventType();
            // DUMP_TREE 诊断：打印节点树是很重的活，丢给工作线程
            if (DUMP_TREE) {
                DUMP_TREE = false;
                final Handler w0 = work;
                if (w0 != null) w0.post(new Runnable() { @Override public void run() { dumpTree(); } });
            }
            long nowD = System.currentTimeMillis();
            // EVT 诊断日志默认关（`verbose 1` 打开）。它要调 e.getSource()，那是一次 IPC，
            // 在内容密集的页面上也要几毫秒到几十毫秒 —— 按键过滤就得等这几毫秒。
            if (Prefs.verbose(this) && nowD - lastDiag > 700L) {
                lastDiag = nowD;
                String srcInfo = "src=null";
                AccessibilityNodeInfo sn = null;
                try { sn = e.getSource(); } catch (Throwable ignored) { }
                if (sn != null) {
                    boolean f = false, s = false;
                    try { f = sn.isFocused(); } catch (Throwable ignored) { }
                    try { s = sn.isSelected(); } catch (Throwable ignored) { }
                    srcInfo = "src=" + sn.getClassName() + " focused=" + f + " selected=" + s
                            + " kids=" + sn.getChildCount() + " srcText=" + brief(aroundText(sn));
                }
                Log.i("EVT t=" + AccessibilityEvent.eventTypeToString(type)
                        + " pkg=" + e.getPackageName() + " cls=" + e.getClassName()
                        + " " + srcInfo
                        + " evText=" + brief(evText(e))
                        + " guard=" + guardActive() + "(bl=" + Prefs.blEnable(this)
                        + ",go=" + Prefs.guardOn(this)
                        + ",pf=" + GuardService.parentForeground
                        + ",pp=" + GuardService.pinPadOpen
                        + ",gr=" + Prefs.inGrace(this) + ")");
            }
            boolean focusEv = type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                    || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    || type == AccessibilityEvent.TYPE_VIEW_SELECTED
                    || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || type == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
            boolean winEv = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            if (!focusEv && !winEv) return;

            CharSequence p = e.getPackageName();
            String pkg = p == null ? "" : p.toString();
            if (pkg.isEmpty() || pkg.equals(getPackageName())) { clearFocus(); return; }

            // ★ 主线程到这里就结束了：真正的判定（焦点命中、整窗扫描）全部丢给工作线程。
            // 主线程 = 无障碍按键过滤回调所在的线程，它必须永远只做微秒级的事，
            // 否则按键被我们拖慢，App 就会把「点按」误判成「按住」自己连跳（A/B 已证实）。
            requestRefresh(pkg, winEv);
        } catch (Throwable t) {
            Log.e("a11y onEvent", t);
        }
    }

    private boolean guardActive() {
        if (!Prefs.blEnable(this) || !Prefs.guardOn(this)) return false;
        if (GuardService.parentBusy()) return false;
        if (Prefs.inGrace(this)) return false;
        return true;
    }

    private String freshHit() {
        if (focusHit == null) return null;
        if (System.currentTimeMillis() - focusHitAt > FOCUS_TTL) return null;
        return focusHit;
    }

    private void clearFocus() {
        focusHit = null;
        focusKind = "";
        focusHitAt = 0L;
        focusHitBox = null;
    }

    /**
     * 按键路径专用的「页面级命中」查询：**只读内存缓存，绝不爬树**。
     * 缓存由 watchdog（后台线程）持续刷新；过期就当没命中，并且顺手催后台补一次
     * （入队是微秒级的，不会阻塞按键过滤）。
     */
    private String[] pageHitCached() {
        if (pageHitWord != null && System.currentTimeMillis() - pageHitAt < PAGE_HIT_MS) {
            return new String[]{ pageHitWord, pageHitKind };
        }
        requestRefresh(null, false);
        return null;
    }

    /**
     * 主线程调用：告诉工作线程「焦点/页面可能有变化，重算一下」。
     * 只做赋值 + 一次入队（合并：同一时刻最多一个重算在跑；跑的过程中又来的需求，收尾时再补一轮）。
     */
    private void requestRefresh(String pkg, boolean winEv) {
        if (pkg != null && pkg.length() > 0) pendingPkg = pkg;
        if (winEv) pendingWin = true;
        Handler w = work;
        if (w == null) return;
        if (refreshQueued.compareAndSet(false, true)) w.post(refreshRun);
    }

    /**
     * 工作线程：重算焦点命中 /（必要时）跑一遍整窗扫描。这是**唯一**爬节点树的地方。
     * 原来这些都在主线程的 onAccessibilityEvent 里直接做，把按键过滤回调拖慢 0.3~1 秒。
     */
    private void doRefresh() {
        String pkg = pendingPkg;
        boolean win = pendingWin;
        pendingWin = false;
        try {
            if (!guardActive()) {
                clearFocus();
            } else if (pkg != null && pkg.length() > 0 && !pkg.equals(getPackageName())) {
                updateFocus(pkg);
                // 页面级命中缓存：原来只有「正在出声 + watchdog」才会写它，
                // 没在播放的详情页就一直是空的 → 吞确定键少一道兜底。这里顺手补上。
                if (focusHit == null && pageHitWord == null) pageHit(pkg);
                if (win && focusHit == null) watchdog(pkg);
            }
        } catch (Throwable t) {
            Log.e("a11y refresh", t);
        } finally {
            refreshQueued.set(false);
            // 这一轮跑的过程中又来了窗口/内容变化 → 再补一轮（合并成一次，不会自旋）
            if (pendingWin && work != null && refreshQueued.compareAndSet(false, true)) {
                work.post(refreshRun);
            }
        }
    }

    /** 该包是否属于「被读屏监控的播放器」。 */
    private boolean monitored(String pkg) {
        if (Prefs.whitelistMode(this)) return Prefs.whitelist(this).contains(pkg);
        return Prefs.blPackages(this).contains(pkg);
    }

    /**
     * 重新计算「焦点框里是什么」。
     * 电视的焦点框常常套在一个容器上，而片名是它里面/旁边的兄弟节点，
     * 所以除了节点自身，还要把 2 层祖先 + 最多 8 个子节点的文案一起看。
     */
    private void updateFocus(String pkg) {
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        boolean dbg = System.currentTimeMillis() - lastDiag2 > 700L;
        if (dbg) lastDiag2 = System.currentTimeMillis();
        if (root == null) {
            focusHit = null; focusKind = ""; focusHitAt = 0L;
            if (dbg) Log.i("FOCUS-TXT pkg=" + pkg + " root=null");
            return;
        }
        AccessibilityNodeInfo node = null;
        try {
            node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Throwable ignored) { }
        // 包名以「焦点节点自己的包」为准：系统栏时钟之类的无关事件也会调到这里，
        // 用事件的包名会把命中归错包，日志和后续判断都会跟着错。
        String own = pkg;
        try {
            if (node != null && node.getPackageName() != null) {
                String s = node.getPackageName().toString();
                if (s != null && s.length() > 0) own = s;
            }
        } catch (Throwable ignored) { }
        if (own == null) own = "";
        String text = node == null ? "" : aroundText(node);
        if (dbg) {
            Log.i("FOCUS-TXT pkg=" + own + " node=" + (node == null ? "null" : String.valueOf(node.getClassName()))
                    + " text=" + brief(text));
        }
        String[] hit = Prefs.matchBlacklist(this, text);
        if (hit == null && System.currentTimeMillis() - lastScan > SCAN_INTERVAL) {
            lastScan = System.currentTimeMillis();
            String[] alt = scanFocus(root, text);
            if (alt != null) {
                hit = new String[]{alt[0], alt[1]};
                if (dbg) Log.i("FOCUS-ALT hit=" + alt[0] + " kind=" + alt[1] + " text=" + brief(alt[2]));
            }
        }
        // 最后一道：焦点节点自身没文案时，按坐标把「压在焦点框上／框内」的文字捞出来。
        // 优酷的焦点框是没有 text/content-desc 的 FrameLayout，片子名画在同一块区域的兄弟节点上。
        if (hit == null && node != null) {
            String geo = rectText(root, node);
            if (geo.length() > 0) {
                String[] gh = Prefs.matchBlacklist(this, geo);
                if (gh != null) {
                    hit = new String[]{gh[0], gh[1]};
                    if (dbg) Log.i("FOCUS-GEO hit=" + gh[0] + " kind=" + gh[1] + " text=" + brief(geo));
                }
            }
        }
        String bx = (node == null ? "" : box(node));
        long now2 = System.currentTimeMillis();
        if (hit != null) {
            focusHit = hit[0];
            focusKind = hit[1];
            focusHitAt = now2;
            focusHitBox = bx;
            Log.i("FOCUS-HIT pkg=" + own + " kind=" + focusKind + " hit=" + focusHit
                    + " box=" + bx + " text=" + brief(text));
        } else if (focusHit != null && bx.length() > 0 && bx.equals(focusHitBox)
                && now2 - focusHitAt < STICKY_HIT_MS) {
            // 焦点框没挪地方，只是这一帧读到的文案变了/读空了
            // （自动预览、动画、动态文字都会让同一格反复读出不同内容）
            // → 维持上一次的命中，别让命中间断。
            focusHitAt = now2;
            Log.i("FOCUS-KEEP hit=" + focusHit + " kind=" + focusKind + " box=" + bx);
        } else {
            focusHit = null;
            focusKind = "";
            focusHitAt = 0L;
            focusHitBox = null;
        }
    }

    /**
     * 兜底：findFocus 给的节点没命中时，广度优先遍历活动窗口，找 isFocused / isSelected 的节点，
     * 把它们「自身 + 祖先 + 子节点」的文案逐一匹配（电视 App 常把焦点框画在容器上，
     * 而片名是它的子节点；有些列表用 selected 而不是 focused 表示当前项）。
     * 返回 {词条, 类型, 命中节点的文案}，未命中返回 null。
     */
    private String[] scanFocus(AccessibilityNodeInfo root, String skipText) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int budget = MAX_NODES;
        while (!q.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            boolean hot = false;
            try { hot = n.isFocused() || n.isSelected(); } catch (Throwable ignored) { }
            if (hot) {
                String t = aroundText(n);
                if (t != null && !t.equals(skipText)) {
                    String[] h = Prefs.matchBlacklist(this, t);
                    if (h != null) return new String[]{h[0], h[1], t};
                }
            }
            int c = 0;
            try { c = n.getChildCount(); } catch (Throwable ignored) { }
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo ch = null;
                try { ch = n.getChild(i); } catch (Throwable ignored) { }
                if (ch != null) q.add(ch);
            }
        }
        return null;
    }

    /**
     * 按坐标兜底取文案：把与焦点框「相交」的节点文字拼起来。
     * 用在焦点框本身没有 text/content-desc 的界面（优酷首页的焦点框就是这种空 FrameLayout，
     * 而片子名画在同一区域的兄弟节点上）。只取相交，不取「附近」，避免把无关的导航词拉进来。
     */
    private String rectText(AccessibilityNodeInfo root, AccessibilityNodeInfo focus) {
        Rect fr = new Rect();
        try { focus.getBoundsInScreen(fr); } catch (Throwable ignored) { }
        if (fr.width() <= 0 || fr.height() <= 0) return "";
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int budget = MAX_NODES;
        while (!q.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            String t = nodeText(n);
            if (t.length() > 0 && sb.length() < 400) {
                Rect r = new Rect();
                try { n.getBoundsInScreen(r); } catch (Throwable ignored) { }
                if (Rect.intersects(r, fr)) sb.append(t).append(' ');
            }
            int c = 0;
            try { c = n.getChildCount(); } catch (Throwable ignored) { }
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo ch = null;
                try { ch = n.getChild(i); } catch (Throwable ignored) { }
                if (ch != null) q.add(ch);
            }
        }
        return sb.toString();
    }

    /** 把活动窗口的节点树打出来（限 80 个节点、深度 12），用于排查「焦点框读不到文案」。 */
    private void dumpTree() {
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root == null) { Log.i("TREE root=null"); return; }
        ArrayDeque<Object[]> q = new ArrayDeque<Object[]>();
        q.add(new Object[]{root, 0});
        int budget = 80;
        while (!q.isEmpty() && budget-- > 0) {
            Object[] it = q.poll();
            AccessibilityNodeInfo n = (AccessibilityNodeInfo) it[0];
            int d = (Integer) it[1];
            boolean f = false, sel = false, ck = false;
            int kids = 0;
            try { f = n.isFocused(); } catch (Throwable ignored) { }
            try { sel = n.isSelected(); } catch (Throwable ignored) { }
            try { ck = n.isClickable(); } catch (Throwable ignored) { }
            try { kids = n.getChildCount(); } catch (Throwable ignored) { }
            Log.i("TREE d=" + d + " " + n.getClassName() + " f=" + f + " s=" + sel + " ck=" + ck
                    + " kids=" + kids + " box=" + box(n) + " t=" + brief(aroundText(n)));
            if (d >= 12) continue;
            for (int i = 0; i < kids; i++) {
                AccessibilityNodeInfo ch = null;
                try { ch = n.getChild(i); } catch (Throwable ignored) { }
                if (ch != null) q.add(new Object[]{ch, d + 1});
            }
        }
        Log.i("TREE done");
    }

    private String aroundText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        appendNode(sb, node);
        AccessibilityNodeInfo p = null;
        try { p = node.getParent(); } catch (Throwable ignored) { }
        for (int i = 0; i < 2 && p != null; i++) {
            appendNode(sb, p);
            try { p = p.getParent(); } catch (Throwable t) { p = null; }
        }
        collect(sb, node, 0, new int[]{24});
        return sb.toString();
    }

    /**
     * 往下收集 3 层、最多 budget 个节点的文案。
     * 电视/列表 App 的焦点框往往套在最外层容器上，文案藏在「容器 → 容器 → TextView」两层以下
     * （Settings 的 dashboard_tile 就是这种：本体无文案，标题在孙节点里）。
     */
    private void collect(StringBuilder sb, AccessibilityNodeInfo n, int depth, int[] budget) {
        if (n == null || depth > 3 || budget[0] <= 0) return;
        int c = 0;
        try { c = n.getChildCount(); } catch (Throwable ignored) { }
        for (int i = 0; i < c && budget[0] > 0; i++) {
            AccessibilityNodeInfo ch = null;
            try { ch = n.getChild(i); } catch (Throwable ignored) { }
            if (ch == null) continue;
            budget[0]--;
            appendNode(sb, ch);
            collect(sb, ch, depth + 1, budget);
        }
    }

    /** 事件自带文案（比遍历节点树便宜也更稳，很多 App 只在事件里给文案）。 */    private static String evText(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.List<CharSequence> l = e.getText();
            if (l != null) for (int i = 0; i < l.size(); i++) if (l.get(i) != null) sb.append(l.get(i)).append(' ');
        } catch (Throwable ignored) { }
        try {
            if (e.getContentDescription() != null) sb.append(e.getContentDescription()).append(' ');
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private void appendNode(StringBuilder sb, AccessibilityNodeInfo n) {
        if (n == null) return;
        try { CharSequence t = n.getText(); if (t != null) sb.append(t).append(' '); } catch (Throwable ignored) { }
        try { CharSequence d = n.getContentDescription(); if (d != null) sb.append(d).append(' '); } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= 26) {
            try { CharSequence h = n.getHintText(); if (h != null) sb.append(h).append(' '); } catch (Throwable ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try { CharSequence pt = n.getPaneTitle(); if (pt != null) sb.append(pt).append(' '); } catch (Throwable ignored) { }
        }
    }

    private String box(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.left + "," + r.top + "-" + r.right + "," + r.bottom;
        } catch (Throwable t) { return "?"; }
    }

    private String brief(String s) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').trim();
        return x.length() > 90 ? x.substring(0, 90) + "…" : x;
    }

    // ---------------- 兜底：内容真的播起来了才动手（盖一层「已屏蔽」10 秒） ----------------
    // 两个开关各管各的，互不依赖、互不重复（用户 2026-02 明确要求）：
    //   「内容兜底」blPlayBlock ：页面上真的有黑名单字（整窗文字命中名单）→ 盖。
    //   「预览严格模式」strictPreview：读不到片名的自动预览 → 盖（见下面的判据三）。
    //   内容兜底不再兼管「自动预览」——那是预览严格模式的事。

    private void watchdog(String pkg) {
        if (!monitored(pkg)) return;
        // 家长刚用 PIN 放行过这个 App（默认 30 分钟）→ 完全不管它
        if (Prefs.isAllowed(this, pkg)) return;
        // 正在遮罩里 → 什么都不用做（10 秒后自己会放开，然后再判一次）
        if (covering()) return;
        // 家长界面 / PIN 键盘在前面时不要动画面（家长正在操作，别拿黑幕去盖他）
        if (GuardService.parentBusy()) return;
        // 两个开关都关 → 这一轮连节点树都不用爬（吞确定键走 onKeyEvent 的现场判定，不靠这里）
        if (!Prefs.blPlayBlock(this) && !Prefs.strictPreview(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastBlock < BLOCK_COOLDOWN) return;
        // 判据一：是「这个 App」在放（系统播放状态，见 playingFor；与音量/静音无关）
        if (!playingFor(pkg)) { playSince = 0L; return; }
        if (playSince == 0L) playSince = now;
        long playedMs = now - playSince;

        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root == null) return;

        StringBuilder all = new StringBuilder();
        StringBuilder top = new StringBuilder();
        boolean[] media = new boolean[]{ false };
        boolean[] bar = new boolean[]{ false };
        boolean[] strong = new boolean[]{ false };
        double[] mfrac = new double[]{ 0d };
        int[] budget = new int[]{ MAX_NODES };
        walk(root, all, top, 0, budget, media, bar, strong, mfrac);
        // 判据二：画面上正带着「被拦的内容」
        //  · 真在放整片（有进度条/播放控制）→ 只认屏幕上方的标题栏文字，免得被播放页的推荐位带偏
        //  · 只是焦点停靠自动预览（没有播放控制）→ 认整窗文字（优酷/B站 首页的预览标题就是这么读到的）
        String probe = bar[0] ? top.toString() : all.toString();
        String[] hit = Prefs.matchBlacklist(this, probe);
        long nl = System.currentTimeMillis();

        if (hit != null) {
            lastWatchLog = nl;
            // 顺手把页面级命中记下来，给 onKeyEvent 吞「确定」用（见 pageHit）；记这一步与开关无关
            pageHitPkg = pkg;
            pageHitWord = hit[0];
            pageHitKind = hit[1];
            pageHitAt = nl;
            if (!Prefs.blPlayBlock(this)) {
                Log.i("WATCH pkg=" + pkg + " 命中 hit=" + hit[0] + " kind=" + hit[1]
                        + " 但内容兜底=关，只记命中不盖（确定键照吞）");
                return;
            }
            Log.i("WATCH pkg=" + pkg + " 命中 hit=" + hit[0] + " kind=" + hit[1]
                    + " seekBar=" + bar[0] + " strong=" + strong[0] + " media=" + media[0]
                    + " 画面占比=" + Math.round(mfrac[0] * 100) + "% " + audioInfo
                    + " top=" + brief(top.toString()));
            cover(pkg, hit[0], hit[1]);
            return;
        }

        // 名单外：这个页面上已经没有黑名单词了（孩子换页了）→ 页面级命中缓存作废
        if (pkg != null && pkg.equals(pageHitPkg)) pageHitWord = null;

        // 判据三（「预览严格模式」单独管这个，与内容兜底无关）：读不到名字也算命中。
        // 有些片子/剧集的标题本身就是一张图（艺术字海报），读屏永远读不出文字，
        // 按名字匹配必然漏。所以这里换一个判据：
        //   「真认出了视频画布（strong，如优酷的 cd='ott-surface-view'）+ 画面不是整屏播放
        //    + 屏幕上没有播放控制条 + 正在出声 + 孩子最近没按过确定 + 连续出声够久」
        //   = 焦点停靠自动预览 → 盖住。
        // 有播放控制条、或者视频画面占了八成屏 = 已经在正经播片了（不管他是自己点开的还是连播的），
        // 那种情况只按名字拦，绝不误伤孩子正在看的片子。
        // 孩子自己按确定挑的（CONFIRM_GRACE 内按过）也不动。
        if (Prefs.strictPreview(this) && !bar[0] && strong[0] && mfrac[0] >= 0.05d && mfrac[0] < 0.8d
                && !byConfirm(nl)
                && playedMs >= PREVIEW_MIN_MS
                && nl - lastPreviewCover >= PREVIEW_COOLDOWN) {
            lastWatchLog = nl;
            lastPreviewCover = nl;
            playSince = 0L;
            Log.i("WATCH pkg=" + pkg + " 命中(严格预览) strong=" + strong[0] + " media=" + media[0]
                    + " seekBar=" + bar[0] + " 画面占比=" + Math.round(mfrac[0] * 100) + "%"
                    + " 连续播放=" + (playedMs / 1000) + "s"
                    + " 距上次按确定=" + (lastConfirmAt > 0L ? ((nl - lastConfirmAt) / 1000 + "s") : "从未")
                    + " " + audioInfo + " top=" + brief(top.toString()));
            cover(pkg, "自动预览", "严格预览");
            return;
        }
        if (nl - lastWatchLog > 10000L) {
            lastWatchLog = nl;
            Log.i("WATCH pkg=" + pkg + " playing=true strong=" + strong[0] + " media=" + media[0]
                    + " seekBar=" + bar[0] + " 画面占比=" + Math.round(mfrac[0] * 100) + "%"
                    + " 连续播放=" + (playedMs / 1000) + "s"
                    // lastPreviewCover 为 0 表示「本次开机还没盖过预览」。原来直接相减会打出
                    // 一个约 -1.79e9 秒的负数（nl 是 currentTimeMillis），排查时极易误判成时钟坏了，
                    // 所以这里显式区分「还没盖过」。
                    + " 预览冷却=" + (lastPreviewCover == 0L ? "还没盖过"
                            : ((PREVIEW_COOLDOWN - (nl - lastPreviewCover)) / 1000) + "s")
                    + " 严格预览=" + (Prefs.strictPreview(this) ? "开" : "关")
                    + " 内容兜底=" + (Prefs.blPlayBlock(this) ? "开" : "关")
                    + " " + audioInfo + " 名单外 top=" + brief(top.toString()));
        }
    }

    // ---------------- 「已屏蔽」遮罩：不退出 App，只让遥控器按不动 ----------------

    /**
     * 本次遮罩实际要盖多久（毫秒）。家长端可调 1~10 秒（{@link Prefs#coverSec}，默认 10）：
     * 太短会出现「刚放开又自己播起来」的轮空，太长孩子干等着，所以交给家长。
     */
    private long coverMs() {
        return Prefs.coverSec(this) * 1000L;
    }

    /** 现在是不是在遮罩里（{@link #coverMs()} 之内）。 */
    private boolean covering() {
        return System.currentTimeMillis() < coverUntil;
    }

    /**
     * 盖一层「已屏蔽」：黑幕 + 词条名 + 倒计时 + 「家长按【菜单键】输 PIN 可允许播放」。
     * 不退出 App、不按返回、不记违规、不累计；时长由家长端决定（{@link Prefs#coverSec}，1~10 秒）后自动放开。
     */
    private void cover(String pkg, String hit, String kind) {
        long ms = coverMs();
        lastBlock = System.currentTimeMillis();
        coverUntil = System.currentTimeMillis() + ms;
        coverPkg = pkg;
        coverWhy = (hit == null || hit.length() == 0) ? "自动预览" : hit;
        Log.i("COVER pkg=" + pkg + " kind=" + kind + " hit=" + coverWhy + " " + ms + "ms");
        // 遮罩是要动 View/WindowManager 的，必须在主线程画。
        // watchdog 现在跑在工作线程上（就是为了不卡按键路径），所以这里显式切回主线程。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showCover();
        } else {
            try {
                main.post(new Runnable() { @Override public void run() { showCover(); } });
            } catch (Throwable t) {
                Log.w("cover post: " + t);
            }
        }
    }

    /** 家长放行后立刻撤掉遮罩（ParentActivity 输对 PIN 时调用）。 */
    public static void endCover() {
        final GuardAccessibilityService s = I;
        if (s == null) return;
        s.coverUntil = 0L;
        try {
            s.main.post(new Runnable() {
                @Override public void run() {
                    s.hideCover();
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 画遮罩（必须在主线程）。 */
    private void showCover() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { @Override public void run() { showCover(); } });
            return;
        }
        hideCover();
        if (Build.VERSION.SDK_INT < 22) return;
        try {
            FrameLayout mask = new FrameLayout(this);
            mask.setBackgroundColor(0xF2000000);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView t1 = new TextView(this);
            t1.setText("这个先不能看");
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
            t1.setTextColor(0xFFFF5252);
            t1.setGravity(Gravity.CENTER);

            TextView t2 = new TextView(this);
            t2.setText("「" + coverWhy + "」已屏蔽");
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            t2.setTextColor(0xFFECEFF1);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dpS(14), 0, 0);

            coverLeft = new TextView(this);
            coverLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            coverLeft.setTextColor(0xFF90A4AE);
            coverLeft.setGravity(Gravity.CENTER);
            coverLeft.setPadding(0, dpS(10), 0, 0);

            TextView t4 = new TextView(this);
            t4.setText("家长：按【菜单键】输入 PIN 可以允许播放");
            t4.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t4.setTextColor(0xFF4FC3F7);
            t4.setGravity(Gravity.CENTER);
            t4.setPadding(0, dpS(22), 0, 0);

            col.addView(t1);
            col.addView(t2);
            col.addView(coverLeft);
            col.addView(t4);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            cp.gravity = Gravity.CENTER;
            mask.addView(col, cp);

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.addView(mask, lp);
            overlay = mask;
            main.removeCallbacks(coverTick);
            main.post(coverTick);
        } catch (Throwable e) {
            Log.w("cover overlay fail: " + e);
            try {
                android.widget.Toast.makeText(this, "这个先不能看：" + coverWhy,
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        }
    }

    private int dpS(int v) {
        try {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return v;
        }
    }

    /** 每 500ms 刷一次遮罩上的倒计时；到点自己收掉。 */
    private final Runnable coverTick = new Runnable() {
        @Override public void run() {
            long left = coverUntil - System.currentTimeMillis();
            if (left <= 0L) {
                Log.i("COVER off pkg=" + coverPkg + " hit=" + coverWhy);
                hideCover();
                return;
            }
            if (coverLeft != null) {
                coverLeft.setText("还有 " + ((left + 999L) / 1000L) + " 秒自动放开");
            }
            main.postDelayed(this, 500L);
        }
    };

    private void hideCover() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { @Override public void run() { hideCover(); } });
            return;
        }
        main.removeCallbacks(coverTick);
        coverLeft = null;
        hide();
    }

    /** 手工测试入口：广播 cover-now 时对当前前台 App 立刻盖一次「已屏蔽」（时长同家长端设置）。 */
    public static String testCover() {
        final GuardAccessibilityService s = I;
        if (s == null) return "无障碍服务没在跑";
        final String pkg = s.activePkg();
        if (pkg == null) return "拿不到前台包名";
        s.main.post(new Runnable() {
            @Override public void run() {
                s.cover(pkg, "手工测试", "PC测试");
            }
        });
        int sec = Prefs.coverSec(s);
        return "已对 " + pkg + " 盖 " + sec + " 秒遮罩（看 logcat 的 COVER 行；这 " + sec
                + " 秒遥控器应只认音量键，按【菜单键】出 PIN）";
    }

    private String nodeText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        try { CharSequence t = n.getText(); if (t != null) sb.append(t); } catch (Throwable ignored) { }
        try {
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(d);
            }
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    private boolean audioActive() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 孩子是不是刚按过确定（= 这次播放是他自己挑的，不是焦点停靠自动放起来的）。
     */
    private boolean byConfirm(long now) {
        return lastConfirmAt > 0L && (now - lastConfirmAt) < CONFIRM_GRACE;
    }

    /**
     * 现在是「这个包」在放吗？
     * 主判据：AudioManager.getActivePlaybackConfigurations()（API 26+）里 usage=USAGE_MEDIA
     *         **且这条播放流的 uid == 这个包的 uid** 的播放器 → 才算「它在放」。
     *         `getClientUid()` 是隐藏 API（公开 SDK 的 android.jar 里没有，直接调会编译不过），
     *         所以用反射按名字取；取不到就退回「只要有 USAGE_MEDIA 在放」。
     * 兜底：isMusicActive()。
     * 全都与音量/静音无关 —— 电视静音时照样能认出来。
     */
    private boolean playingFor(String pkg) {
        boolean music = audioActive();
        int n = -1;
        int media = -1;
        int mine = -1;
        int uidOk = 0;
        int uidWant = uidOf(pkg);
        String usages = "";
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    java.util.List<android.media.AudioPlaybackConfiguration> l = am.getActivePlaybackConfigurations();
                    if (l != null) {
                        n = l.size();
                        media = 0;
                        mine = 0;
                        StringBuilder u = new StringBuilder();
                        for (int i = 0; i < n; i++) {
                            int use = -1;
                            int uid = -1;
                            try {
                                android.media.AudioAttributes a = l.get(i).getAudioAttributes();
                                use = a == null ? -1 : a.getUsage();
                                uid = cfgUid(l.get(i));
                            } catch (Throwable t) {
                                u.append("? ");
                            }
                            u.append(use);
                            if (uid >= 0) {
                                uidOk++;
                                u.append(':').append(uid);
                            }
                            u.append(' ');
                            if (use == android.media.AudioAttributes.USAGE_MEDIA) {
                                media++;
                                if (uidWant > 0 && uid == uidWant) mine++;
                            }
                        }
                        usages = u.toString().trim();
                    }
                }
            }
        } catch (Throwable ignored) { }
        // 能认出 uid（uidOk>0）就精确判「是不是它的流」；认不出才退回宽松判据
        boolean decided = (uidOk > 0 && uidWant > 0) ? (mine > 0) : (media > 0 || music);
        audioInfo = "cfg=" + n + " media=" + media + " mine=" + mine + " uidOk=" + uidOk
                + " uidWant=" + uidWant + " music=" + music + " usage=[" + usages + "]";
        return decided;
    }

    /** 反射取「这条播放流属于哪个 uid」（getClientUid 是隐藏 API，公开 SDK 里没有）。 */
    private static int cfgUid(android.media.AudioPlaybackConfiguration cfg) {
        try {
            java.lang.reflect.Method m = android.media.AudioPlaybackConfiguration.class.getMethod("getClientUid");
            Object o = m.invoke(cfg);
            if (o instanceof Integer) return (Integer) o;
        } catch (Throwable ignored) { }
        return -1;
    }

    /** 某个包的 uid（拿不到返回 -1）。 */
    private int uidOf(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 屏幕像素面积（用于判断「占屏很大 = 视频画布」）。 */
    private long screenArea() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            return (long) dm.widthPixels * (long) dm.heightPixels;
        } catch (Throwable t) {
            return 1920L * 1080L;
        }
    }

    /** 屏幕高度（判断「这条文字在不在画面上方」，播放页的标题都在上方）。 */
    private int screenH() {
        try { return getResources().getDisplayMetrics().heightPixels; } catch (Throwable t) { return 1080; }
    }

    /** 这个节点占整个屏幕多大（0~1）：用来区分「整屏播片」和「首页那种半屏自动预览」。 */
    private double fracOf(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long a = r.width() * (long) r.height();
            long s = screenArea();
            if (s <= 0L) return 0d;
            double f = (double) a / (double) s;
            return f > 1d ? 1d : f;
        } catch (Throwable t) { return 0d; }
    }

    /**
     * 遍历活动窗口：把文字收进 sb（全窗），把屏幕上方的文字另收一份进 top，
     * 并判断这页有没有「视频画布」(media / strong) 和「播放控制/进度条」(bar)。
     * strong = 真的认出了视频画布（类名或内容描述，如优酷的 cd='ott-surface-view'）；
     * media = 上面那条，或者「占屏很大又不可点的容器」这种弱信号。
     * mfrac = strong 画布占全屏的比例（用来区分「整屏播片」和「半屏自动预览」）。
     * 注意：文字收满 6000 字以后仍然继续往下走——否则画布节点排在后面就会被漏掉。
     */
    private void walk(AccessibilityNodeInfo node, StringBuilder sb, StringBuilder top, int depth, int[] budget,
                      boolean[] media, boolean[] bar, boolean[] strong, double[] mfrac) {
        if (node == null || depth > 26 || budget[0] <= 0) return;
        budget[0]--;
        if (sb.length() < 6000) appendNode(sb, node);
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.top < screenH() * 45 / 100 && top.length() < 2000) appendNode(top, node);
        } catch (Throwable ignored) { }
        try {
            CharSequence cn = node.getClassName();
            String cls = cn == null ? "" : cn.toString();
            if (cls.contains("SeekBar") || cls.contains("VideoView") || cls.contains("SurfaceView")
                    || cls.contains("TextureView") || cls.contains("MediaController")
                    || cls.contains("Player") || cls.contains("GLSurface")) {
                media[0] = true;
                strong[0] = true;
                mfrac[0] = Math.max(mfrac[0], fracOf(node));
            }
            if (cls.contains("SeekBar") || cls.contains("MediaController")) bar[0] = true;
        } catch (Throwable ignored) { }
        // 智能电视 App 常把播放画面画在自绘的 View 上（优酷：class=android.view.View、cd='ott-surface-view'），
        // 类名匹配不到，所以再认 content-desc 里的 surface/player/video。
        try {
            CharSequence cd = node.getContentDescription();
            String d = cd == null ? "" : cd.toString().toLowerCase(java.util.Locale.US);
            if (d.contains("surface") || d.contains("player") || d.contains("video")) {
                media[0] = true;
                strong[0] = true;
                mfrac[0] = Math.max(mfrac[0], fracOf(node));
            }
        } catch (Throwable ignored) { }
        // 最后再认「占屏面积很大的无文案容器」= 可能是视频画布。这条是弱信号
        // （首页/播放页的大容器到处都是），所以只用来判「有没有画面」，不算进 mfrac，
        // 也排除 depth==0 的窗口根容器（根容器必然占满全屏）。
        if (!media[0] && depth > 0) {
            try {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() * (long) r.height() >= screenArea() / 3L && !node.isClickable()) media[0] = true;
            } catch (Throwable ignored) { }
        }
        int n = 0;
        try { n = node.getChildCount(); } catch (Throwable ignored) { }
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = null;
            try { ch = node.getChild(i); } catch (Throwable ignored) { }
            if (ch != null) walk(ch, sb, top, depth + 1, budget, media, bar, strong, mfrac);
        }
    }

    private void show(String msg) {
        if (Build.VERSION.SDK_INT >= 22) {
            try {
                final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (overlay != null) {
                    try { wm.removeView(overlay); } catch (Throwable ignored) { }
                    overlay = null;
                }
                TextView t = new TextView(this);
                t.setText(msg);
                t.setTextSize(38);
                t.setTextColor(Color.WHITE);
                t.setGravity(Gravity.CENTER);
                t.setBackgroundColor(0xE6101010);
                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        flags, PixelFormat.TRANSLUCENT);
                wm.addView(t, lp);
                overlay = t;
                main.postDelayed(new Runnable() {
                    @Override public void run() { hide(); }
                }, 2600L);
                return;
            } catch (Throwable e) {
                Log.w("overlay fail: " + e);
            }
        }
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private void hide() {
        try {
            if (overlay != null) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(overlay);
                overlay = null;
            }
        } catch (Throwable ignored) {
            overlay = null;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        hide();
        main.removeCallbacks(watchTick);
        try {
            work = null;
            if (workerThread != null) workerThread.quitSafely();
            workerThread = null;
        } catch (Throwable ignored) { }
        I = null;
        Log.w("a11y destroyed");
        super.onDestroy();
    }
}
