# KidGuard · 电视盒子家长控制

给家里的安卓电视盒子/电视装一个「家长控制」：**管时间**（每天累计看多久，到点锁死，家长输 PIN 才能加时）、
**管内容**（关键字/片名的黑/白名单全局生效，命中就盖黑幕并吃掉遥控器按键，不退出孩子正在看的 App）、
**管应用**（APP 黑名单里的应用打开就要家长 PIN；APP 白名单里的应用豁免内容过滤）。

纯 Java + 手工构建，**不需要 root**，不依赖 Gradle / AndroidX，APK 只有 ~85 KB。
在 **小米盒子 MiTV-ASTP0（Android 9 / API 28，1920x1080，无 root）** 上实测通过。

> ⚠️ **第一次装完请立刻改掉默认 PIN `1234`**（家长面板里改，或 `adb … --es cmd setpin --es val 你的PIN`）。

---

## 名单模型（先看这个，两张「大名单」各管各的）

```
黑名单（大类）
├── 内容黑名单          ← 命中就盖「已屏蔽」黑幕 + 吞确定键
│   ├── 关键字黑名单        （模糊匹配：「熊出没」能拦住「熊出没之怪兽计划」）
│   └── 全名黑名单          （整名匹配：只拦写全名的这一部）
└── APP 黑名单          ← 打开要家长 PIN（不是过滤内容，是加家长锁）

白名单（大类）
├── 内容白名单          ← 命中就放行，优先级高于内容黑名单
│   ├── 关键字白名单
│   └── 全名白名单
└── APP 白名单          ← 唯一豁免内容过滤的地方：放行所有内容，每日时长限制照常
```

三条关键规则：

1. **内容黑/白名单是全局规则** —— 不管在哪个 App（B站、优酷、夸克网盘…）里出现这些词/片名都会判，
   不是「只在名单里的 App 才管」。**唯一豁免是 APP 白名单。**
2. **内容白名单优先于内容黑名单** —— 例如把「布鲁伊」加进内容白名单，就不会被「动画」这类大范围词误伤。
3. **APP 黑名单 = 打开要家长 PIN**；家长在电视上按【菜单键】输 PIN 或在电脑控制台点「本次放行」即可开锁
   （默认 30 分钟）。开锁**只开锁**，内容过滤与每日时长限制照常生效。

---

## 功能

| 模块 | 说明 |
| --- | --- |
| **每日时长限制** | 累计亮屏时长到上限就推到锁屏并钉住。每天 **04:00** 换天（孩子熬夜看不会算进第二天，也不会因为半夜跨点而漏算）。家长放行一次 **+30 分钟**（步长可调）。 |
| **内容黑名单** | 关键字 + 片名两张表，**全局生效**。无障碍服务读屏，命中就盖「已屏蔽」黑幕并**吞掉确定/方向键**，黑幕挡 **1~10 秒**（家长可调，默认 10）。不杀进程、不退出 App。 |
| **内容白名单** | 关键字 + 片名两张表，命中就放行、优先于内容黑名单。 |
| **APP 黑名单** | 名单里的 App 一打开就盖「这个要家长开锁」黑幕，遥控器按不动；按【菜单键】输 PIN 或电脑「本次放行」开锁 **30 分钟**。**开锁只开锁**，内容过滤与时长限制照常。 |
| **APP 白名单** | 唯一豁免内容过滤的地方（夸克网盘这类「片子都是自己挑的」适合放进去），每日时长限制照常。 |
| **强锁 + 应用挂起** | Device Owner 权限；到点除了锁屏，还会用 `setPackagesSuspended` 把视频 App 挂起，绕不过去。 |
| **锁定任务模式** | 锁屏期间 `startLockTask` 钉屏，遥控器回不了桌面。 |
| **家长面板** | 电视上用遥控器操作，菜单键唤出 PIN 键盘。 |
| **电脑控制台** | 电脑上开网页改配置（四张名单增删、时间规则、同步按钮）。 |

## 电脑控制台（重点）

双击 `6-电脑控制台.bat` → 本机浏览器打开控制台页面。它能做的事和电视遥控器一样多：

- 改每日上限、加时步长、黑名单遮罩时长、方向键防抖窗口
- 四张名单的**增删**：内容黑名单关键字/片名、内容白名单关键字/片名、APP 黑名单、APP 白名单
- APP 黑名单的「**本次放行 30 分钟**」与「取消全部放行」
- **同步按钮**（两个方向）：
  - **下载配置**：电视 → 电脑（拉回当前配置，存为本地备份）
  - **上传配置**：电脑 → 电视（把网页上的配置整包下发）

桥接方式是 `adb` + 广播命令，本地监听 `127.0.0.1`（默认 8899，被占用自动 +1），
不需要装 Python / Node，只要有 `adb`。

### 电视 IP 自动发现

**不用自己找电视 IP。** 控制台启动时会自动扫描电脑所在 `/24` 网段里开着 ADB 调试
（5555 端口）的设备，读型号确认，**优先选装了 KidGuard 的那台**，然后把地址记进
`dist\config\电视IP.txt` 供下次直接使用。

- 纯 TCP 并发探测（`TcpClient.ConnectAsync` + `Task.WaitAll`），不依赖 `adb mdns`
  ——实测这台小米盒子的 mDNS 发现列表是空的，靠不住。
- 253 个地址约 **1~2 秒**扫完。
- 电视换网络后 IP 变了：记录里的地址连不上时会**自动重扫并更新**记录。
- 页面上有「🔍 自动找电视」按钮可随时手动重扫，结果列出来让人挑；
  只有一个候选且确认装了 KidGuard 时自动连上。
- 启动时不自动找：`6-电脑控制台.bat -NoScan`（页面上手动找仍可用）。
- 前提：电视的「ADB 网络调试」开着、电视和电脑在同一个 WiFi。
- 命令行脚本 `tools\kg.ps1` 同样支持：不给 `-Ip` 时会在设备列表为空时自动扫网段。

## 安装

1. 电视开 **开发者选项 → USB 调试**（小米盒子：设置 → 关于 → 连按版本号）。
2. 电脑双击 `0-连接体检.bat`，确认连上（IP 会自己找，不用填）。
3. 双击 `1-安装部署.bat`：装 APK → 授予 **Device Owner** → 开**无障碍服务** → 拉起守卫。
4. 双击 `2-查看状态.bat` 看当前状态；`3-家长操作.bat` 做家长操作；`6-电脑控制台.bat` 开网页控制台。
5. **改 PIN**。

`adb` 会自动在以下位置查找：脚本目录下的 `adb\`、工程 `toolchain\sdk\platform-tools\`、
`%LOCALAPPDATA%\Android\Sdk\platform-tools\`、`%ProgramFiles%\Android\platform-tools\`、最后 `PATH`。
本仓库出于体积考虑**不带 adb**，装一个平台工具或自行放进 `dist\tools\adb\` 即可。

## 命令（`adb` 广播）

统一格式，`cmd` 命令名、`val` 参数、`pin` 家长 PIN：

```bat
adb shell am broadcast -n com.kidguard.tv/.CommandReceiver -a com.kidguard.tv.CMD ^
    --es cmd status
adb shell am broadcast -n com.kidguard.tv/.CommandReceiver -a com.kidguard.tv.CMD ^
    --es cmd limit --es val 90 --es pin 1234
```

> Android 9 起**必须带 `-n com.kidguard.tv/.CommandReceiver`**，否则会被
> `Background execution not allowed` 拦掉。

**免 PIN（只读）**：`ping` `status` `get` `apps` `dump` `owner` `acc` `sync` `conf` `help`
**需要 PIN**：`setpin` `limit` `add` `step` `reset` `free` `lock` `unlock` `suspend` `resume` `release`
`guard-on/off` `enforce-on/off` `hardlock` `dirmin` `coversec` `verbose`
`blkw-add/del` `bltitle-add/del`（内容黑名单）`whkw-add/del` `whtitle-add/del`（内容白名单）
`blpkg-add/del`（APP 黑名单，打开要 PIN）`wl-add/del`（APP 白名单，豁免内容过滤）
`blwatch-on/off` `bl-on/off` `strictprev-on/off` `cover-now` `allow` `allow-clear` `conf-set` `test-blk` `a11y-dump` `shot`
每条命令都会把结果写进 `/sdcard/kidguard/status.txt`（电脑端状态页读的就是它）。

## 重新出包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
```

手工链路：`aapt2 compile` → `aapt2 link` → `javac`（`-source/-target 8`）→ `d8` → 塞 `classes.dex`
→ `zipalign` → `apksigner`。成功打印 `BUILD_OK`，产物 `dist\KidGuard-1.0.0.apk`。

构建脚本默认走 **ASCII 联接点 `D:\kg`**（`build.ps1` 里的 `$asciiRoot`）。原因是 aapt2 遇到非 ASCII
路径会失败，所以工程放在中文目录里时，请建一个 ASCII 联接点再构建：

```bat
mklink /J D:\kg "D:\你的中文目录\kidguard"
```

换机器改 `tools\build.ps1` / `tools\make-dist.ps1` / `tools\make_icon.py` 里的绝对路径即可。

## 目录

```
app\                     源码（无 Gradle，纯手工构建）
  ├─ AndroidManifest.xml
  ├─ res\{values,xml,mipmap-*}\
  └─ src\com\kidguard\tv\
     ├─ Prefs.java                    所有配置项 + SharedPreferences 读写
     ├─ Usage.java                    亮屏时长累计、04:00 换天
     ├─ Admin.java                    Device Owner：挂起/恢复应用、白名单
     ├─ GuardService.java             前台守卫：计时、到点动作
     ├─ GuardActivity.java            锁屏界面 + PIN
     ├─ ParentActivity.java           家长面板（遥控器操作）+ PIN 开锁页
     ├─ GuardAccessibilityService.java 内容守卫 + APP 门锁（读屏 + 盖黑幕 + 吞键）
     ├─ CommandReceiver.java          广播命令总入口
     ├─ Conf.java                     配置整包导出/导入（电脑控制台同步用）
     ├─ ScreenShot.java / ShotActivity.java  抓帧工具
     └─ Ui.java / Log.java / BootReceiver.java / GuardAdminReceiver.java
tools\                   构建与打包脚本
dist\                    交付内容（双击的 .bat、使用说明、APK、电脑控制台）
keys\                    签名密钥（不入库）
```

## 已知问题 / 坑

- **不能带 `-n` 的广播无效**（Android 9 后台限制），见上。
- **MediaProjection 抓帧在部分 ROM 上不可用**（`MediaProjectionPermissionActivity` 缺失），
  自带的抓帧只是诊断辅助，管控逻辑不依赖它。
- **`adb shell input keyevent` 在部分 ROM 上不经过无障碍按键过滤器**，
  要复现真实遥控器行为，用 `sendevent /dev/input/event0 …` 注入。
- **长按连发可能绕过无障碍过滤器**：部分 ROM 的长按 repeat 直接派发给 App，
  不经过 `onKeyEvent`，表现为「按一下滑很多格」。KidGuard 里做了异步化 + 方向键防抖
  （`dirmin`，默认 300 ms）来缓解。
- 黑幕只挡**视觉 + 遥控器**，不杀进程。孩子如果会用 ADB 关掉无障碍，需要靠 Device Owner 兜底。
- **APP 门锁的前台切换**：被锁的 App 一旦切到后台（按 HOME 回桌面、切到别的 App），
  门锁会自动撤掉并收黑幕（`APPLOCK auto-clear`），换回该 App 会重新上锁。
  家长界面（PIN 页/面板）在前台时黑幕暂时收起、**锁保留**，离开后立刻盖回来。

## License

MIT，见 [LICENSE](LICENSE)。
