<#
  KidGuard 电视管控 —— PC 端控制台
  一次装好，之后家长用遥控器就能操作；这个脚本用于首次部署 / 查看状态 / 应急操作 / 卸载交还。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action doctor  -Ip 192.168.1.50
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action install -Ip 192.168.1.50 -Pin 1357 -Limit 60 -Step 30
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action status  -Ip 192.168.1.50
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action unlock  -Ip 192.168.1.50
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action monitor -Ip 192.168.1.50
    powershell -NoProfile -ExecutionPolicy Bypass -File kg.ps1 -Action uninstall -Ip 192.168.1.50
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidateSet('doctor','install','status','unlock','monitor','uninstall','help')]
  [string]$Action,
  [string]$Ip = '',
  [int]$Port = 5555,
  [string]$Serial = '',
  [string]$Pin = '',
  [int]$Limit = 0,
  [int]$Step = 0,
  [string]$Apk = '',
  [switch]$HardLock,
  [switch]$NoHardLock,
  [switch]$Yes
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$script:Adb = $null
$script:Dev = ''
$script:Sdk = 0
$script:Pkg = 'com.kidguard.tv'
$script:Recv = 'com.kidguard.tv/.CommandReceiver'

function Say  ([string]$m,[string]$c='Gray'){ Write-Host $m -ForegroundColor $c }
function Title([string]$m){ Write-Host ''; Write-Host ('=' * 62) -ForegroundColor DarkCyan; Write-Host "  $m" -ForegroundColor Cyan; Write-Host ('=' * 62) -ForegroundColor DarkCyan }
function Ok   ([string]$m){ Say "[ OK ] $m" Green }
function Warn ([string]$m){ Say "[警告] $m" Yellow }
function Bad  ([string]$m){ Say "[失败] $m" Red }
function Info ([string]$m){ Say "       $m" Gray }
function Step ([string]$m){ Say "  >> $m" White }

# ---------------------------------------------------------------- adb 基础
function Find-Adb {
  $cands = @(
    (Join-Path $PSScriptRoot 'adb\adb.exe'),
    (Join-Path $PSScriptRoot '..\..\toolchain\sdk\platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path $env:ProgramFiles 'Android\platform-tools\adb.exe')
  )
  $x86 = ${env:ProgramFiles(x86)}
  if($x86){ $cands += (Join-Path $x86 'Android\platform-tools\adb.exe') }
  foreach($c in $cands){ if($c -and (Test-Path $c)){ return (Resolve-Path $c).Path } }
  $g = Get-Command adb.exe -ErrorAction SilentlyContinue
  if($g){ return $g.Source }
  return $null
}
# ------------------------------------------------------------ adb 临时目录（防 adb.log 打不开）
# 2026-10-26 实测到的坑：adb 守护进程启动时要在 %TEMP% 里创建 adb.log。某些机器上这个
# 文件会被安全软件 / 清理工具占住，adb 直接报
#   main.cpp:54 cannot open ...\Temp\adb.log: Permission denied
# 守护进程起不来 → 后面每条 adb 命令都报「连不上电视 / failed to start daemon」，
# 看起来像网络或电视的问题，其实跟网络无关（ping 通、5555 也通）。
# 对策：① 把 TEMP/TMP 指到脚本旁边的可写目录；② 万一还是失败，换目录自愈重试一次。
$script:AdbTmp = $null
function Init-AdbTemp {
  $cands = @((Join-Path $PSScriptRoot '_tmp'))
  try { $cands += (Join-Path $env:TEMP 'kidguard-tmp') } catch { }
  foreach ($c in $cands) {
    try {
      if (-not (Test-Path -LiteralPath $c)) { New-Item -ItemType Directory -Force -Path $c | Out-Null }
      $probe = Join-Path $c ('.kgprobe_' + [guid]::NewGuid().ToString('N').Substring(0, 8))
      [System.IO.File]::WriteAllText($probe, 'x')
      Remove-Item -LiteralPath $probe -Force
      $env:TEMP = $c
      $env:TMP  = $c
      $script:AdbTmp = $c
      return $c
    } catch { }
  }
  return $null
}
$script:AdbTmp = Init-AdbTemp

# 带自愈的 adb 调用：守护进程起不来时换目录重启一次
function Run-Adb([string[]]$a) {
  $o = @(& $script:Adb @a 2>&1 | ForEach-Object { $_.ToString() })
  $txt = ($o -join "`n")
  if ($txt -match 'adb\.log' -or $txt -match 'failed to start daemon' -or $txt -match 'cannot connect to daemon') {
    $alt = Join-Path $PSScriptRoot '_tmp2'
    try {
      if (-not (Test-Path -LiteralPath $alt)) { New-Item -ItemType Directory -Force -Path $alt | Out-Null }
      $probe = Join-Path $alt ('.kgprobe_' + [guid]::NewGuid().ToString('N').Substring(0, 8))
      [System.IO.File]::WriteAllText($probe, 'x')
      Remove-Item -LiteralPath $probe -Force
      $env:TEMP = $alt; $env:TMP = $alt
      $null = (& $script:Adb kill-server 2>&1)
      Start-Sleep -Milliseconds 600
      $o = @(& $script:Adb @a 2>&1 | ForEach-Object { $_.ToString() })
    } catch { }
  }
  return $o
}
function AdbRaw([string[]]$a){ return ,@(Run-Adb $a) }
function AdbDev([string[]]$a){ return ,@(Run-Adb ((@('-s',$script:Dev)) + $a)) }
function Sh([string]$c){ return ,(AdbDev @('shell', $c)) }
function GetProp([string]$k){ $v = @(Sh "getprop $k"); if($v.Count -eq 0){ return '' } ; return ($v[0]).Trim() }
function Get-Sdk { $v = 0; [void][int]::TryParse((GetProp 'ro.build.version.sdk'), [ref]$v); return $v }
function Get-HomeActivity {
  if($script:Sdk -ge 24){ return ((Sh 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME') -join ' ') }
  return ((Sh 'pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME') -join ' ')
}
function Set-HomeActivity([string]$comp){
  if($script:Sdk -ge 24){ return ((Sh "cmd package set-home-activity $comp") -join ' ') }
  return 'ERR system older than Android 7.0'
}
function Get-Devices {
  $d = AdbRaw @('devices')
  $list = @()
  foreach($l in $d){
    if($l -match '^(\S+)\s+(device|unauthorized|offline|recovery|sideload)\s*$'){ $list += [pscustomobject]@{ Id = $Matches[1]; State = $Matches[2] } }
  }
  return $list
}

# ------------------------------- 电视 IP 自动发现（扫本机 /24 网段的 5555）
# 为什么不靠 adb mdns：实测这台小米盒子的 mDNS 发现是空的（adb mdns services 无输出）。
# 纯 TCP 并发探测，253 台主机约 1.5 秒扫完。
function Get-LocalV4 {
  $out = @()
  try {
    $nics = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
      Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' }
    foreach($a in $nics){
      foreach($p in $a.GetIPProperties().UnicastAddresses){
        if($p.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork){ continue }
        $ip = $p.Address.ToString()
        if($ip -like '127.*' -or $ip -like '169.254.*'){ continue }
        $out += $ip
      }
    }
  } catch { }
  return $out
}
function Scan-Port5555([int]$timeoutMs = 1500) {
  $lans = @(Get-LocalV4)
  if($lans.Count -eq 0){ return @() }
  $targets = @()
  foreach($ip in $lans){
    $o = $ip.Split('.')
    for($i = 1; $i -le 254; $i++){
      $t = $o[0] + '.' + $o[1] + '.' + $o[2] + '.' + $i
      if($lans -notcontains $t){ $targets += $t }
    }
  }
  if($targets.Count -eq 0){ return @() }
  $n = $targets.Count
  $tasks = New-Object 'System.Threading.Tasks.Task[]' $n
  $clients = New-Object 'System.Net.Sockets.TcpClient[]' $n
  for($i = 0; $i -lt $n; $i++){
    $c = New-Object System.Net.Sockets.TcpClient
    $clients[$i] = $c
    try { $tasks[$i] = $c.ConnectAsync($targets[$i], 5555) } catch { }
  }
  try { $null = [System.Threading.Tasks.Task]::WaitAll($tasks, $timeoutMs) } catch { }
  $open = @()
  for($i = 0; $i -lt $n; $i++){
    $c = $clients[$i]
    $isOpen = $false
    try { if($c){ $isOpen = $c.Connected } } catch { }
    if($isOpen){ $open += $targets[$i] }
    try { if($c){ $c.Close() } } catch { }
  }
  return $open
}
# 扫描 + 认机型，返回候选（装了 KidGuard 的排最前）
function Find-TvByScan {
  $open = @(Scan-Port5555 1500)
  if($open.Count -eq 0){ return @() }
  $cands = @()
  foreach($ip in $open){
    $serial = $ip + ':5555'
    $null = AdbRaw @('connect', $serial)
    Start-Sleep -Milliseconds 250
    $state = ''
    foreach($d in (Get-Devices)){ if($d.Id -eq $serial){ $state = $d.State } }
    if($state -ne 'device'){ continue }
    $model = (& $script:Adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
    $kg = (& $script:Adb -s $serial shell 'pm path com.kidguard.tv' 2>&1 | Out-String).Trim()
    $hasKg = ($kg -like '*package:*')
    $score = 0
    if($hasKg){ $score += 100 }
    if($model -match '(?i)tv|box|mitv|mibox|shield|magic'){ $score += 10 }
    $cands += [pscustomobject]@{ ip = $ip; serial = $serial; model = $model; kidguard = $hasKg; score = $score }
  }
  return @($cands | Sort-Object -Property score -Descending)
}

function Test-Dev {
  $list = Get-Devices
  $me = $list | Where-Object { $_.Id -eq $script:Dev }
  if(-not $me){ Bad "设备 $($script:Dev) 不在 adb 列表里"; return $false }
  if($me.State -eq 'unauthorized'){
    Bad '设备未授权。请在电视上把弹出的「允许 USB 调试」对话框点允许（或用遥控器勾选「一律允许」）。'
    return $false
  }
  if($me.State -ne 'device'){ Bad "设备状态异常：$($me.State)"; return $false }
  return $true
}
function Connect-Device {
  if($Serial){ $script:Dev = $Serial; return (Test-Dev) }
  if($Ip){
    Step "连接 $Ip`:$Port ..."
    for($i=1; $i -le 3; $i++){
      $r = AdbRaw @('connect', "$Ip`:$Port")
      if(($r -join ' ') -match 'connected'){ break }
      Start-Sleep -Milliseconds 700
    }
    $script:Dev = "$Ip`:$Port"
    return (Test-Dev)
  }
  $list = Get-Devices
  $good = @($list | Where-Object { $_.State -eq 'device' })
  if($good.Count -eq 0){
    # 先自动扫一遍网段（不依赖 adb mdns：实测这台小米盒子的 mDNS 发现是空的）
    Step '没有现成设备，正在扫描本机网段里的 5555 端口…'
    $cands = @(Find-TvByScan)
    if($cands.Count -ge 1){
      for($i=0; $i -lt $cands.Count; $i++){
        $tag = ''
        if($cands[$i].kidguard){ $tag = '  ← 已装 KidGuard' }
        Info ("[$i] " + $cands[$i].ip + "  " + $cands[$i].model + $tag)
      }
      $pick = 0
      if($cands.Count -gt 1){
        $sel = Read-Host '输入要用的序号（直接回车 = 第一个）'
        if($sel.Trim() -ne '' -and -not [int]::TryParse($sel, [ref]$pick)){ $pick = 0 }
        if($pick -lt 0 -or $pick -ge $cands.Count){ $pick = 0 }
      }
      $script:Dev = $cands[$pick].serial
      Ok ("自动找到设备 " + $script:Dev)
      return (Test-Dev)
    }
    Bad '没有检测到可用的电视设备。'
    Info '请检查：① 电视 设置→关于 连按版本号 打开开发者选项'
    Info '        ② 开发者选项 / 账号与安全 里打开「ADB调试 · 网络调试」'
    Info '        ③ 电视和这台电脑在同一个 WiFi（同一网段）'
    $ans = Read-Host '也可以现在直接输入电视的 IP 地址（不知道就直接回车放弃）'
    if($ans -match '^(\d{1,3}\.){3}\d{1,3}$'){
      Step "尝试连接 $ans`:$Port ..."
      for($i=1; $i -le 3; $i++){
        $r = AdbRaw @('connect', "$ans`:$Port")
        if(($r -join ' ') -match 'connected'){ break }
        Start-Sleep -Milliseconds 700
      }
      $script:Dev = "$ans`:$Port"
      return (Test-Dev)
    }
    return $false
  }
  if($good.Count -eq 1){ $script:Dev = $good[0].Id; Ok "自动选择设备 $($script:Dev)"; return (Test-Dev) }
  Warn '检测到多个设备，请指定一个：'
  for($i=0; $i -lt $good.Count; $i++){ Info "[$i] $($good[$i].Id)" }
  $sel = Read-Host '请输入序号'
  $idx = 0
  if(-not [int]::TryParse($sel, [ref]$idx) -or $idx -lt 0 -or $idx -ge $good.Count){ Bad '序号无效'; return $false }
  $script:Dev = $good[$idx].Id
  return (Test-Dev)
}

# ------------------------------------------------------- KidGuard 指令通道
function Kg([string]$cmd, [string]$val = $null, [string]$pinArg = $null){
  $a = @('shell','am','broadcast','-n',$script:Recv,'-a','com.kidguard.tv.CMD','--es','cmd',$cmd)
  if($pinArg){ $a += @('--es','pin',$pinArg) }
  if($null -ne $val){ $a += @('--es','val',$val) }
  if($script:Sdk -ge 31){ $a += @('--include-stopped-packages') }
  $out = AdbDev $a
  $data = ''
  foreach($l in $out){
    if($l -match 'data="(.*)"\s*$'){ $data = $Matches[1] }
  }
  return [pscustomobject]@{ Lines = $out; Data = $data }
}
function KgVal([string]$cmd, [string]$val = $null, [string]$pinArg = $null){
  $r = Kg $cmd $val $pinArg
  if($r.Data -match 'ERR'){ return '' }
  return $r.Data
}
function Get-StatusText {
  $p1 = '/sdcard/kidguard/status.txt'
  $p2 = '/sdcard/Android/data/com.kidguard.tv/files/status.txt'
  $t = Sh "cat $p1"
  if(($t -join '') -notmatch 'KidGuard|日期|已用'){ $t = Sh "cat $p2" }
  return ($t -join "`n")
}
function Get-StatusMap([string]$txt){
  # 应用输出的格式是「标签<空格>值」，也用冒号兼容
  $m = @{}
  foreach($l in ($txt -split "`n")){
    $s = $l.Trim()
    if(-not $s){ continue }
    if($s -match '^([^\s:：]+)\s*[:：]?\s+(.+)$'){ $m[$Matches[1]] = $Matches[2].Trim() }
  }
  return $m
}
function Ask-Pin([string]$prompt = '请输入 4 位以上家长 PIN'){
  if($Pin){ return $Pin }
  while($true){
    $s = Read-Host $prompt
    if($s -match '^\d{4,12}$'){ return $s }
    Warn 'PIN 必须是 4~12 位数字'
  }
}
function Confirm-Yes([string]$msg){
  if($Yes){ return $true }
  $s = Read-Host "$msg  输入 YES 继续"
  return ($s -eq 'YES')
}

# ------------------------------------------------------------------ doctor
function Do-Info {
  $sdk = 0
  [void][int]::TryParse((GetProp 'ro.build.version.sdk'), [ref]$sdk)
  $script:Sdk = $sdk
  Info "型号      : $(GetProp 'ro.product.brand') $(GetProp 'ro.product.model')"
  Info "系统      : Android $(GetProp 'ro.build.version.release')  (SDK $sdk)   TV特征=$(GetProp 'ro.build.characteristics')"
  Info "Android版本标识: $(GetProp 'ro.build.display.id')"
  $id = Sh 'id'
  if(($id -join ' ') -match 'uid=0'){ Info 'Shell 权限 : root（可以用更彻底的方式部署）' }
  else { Info 'Shell 权限 : 普通 shell（uid=2000）—— 正常，本方案不需要 root' }
  $pp = (Sh "pm path $($script:Pkg)") -join ''
  Info "已装 KidGuard: $pp"
  return $sdk
}
function Do-Owner {
  $d = Sh 'dumpsys device_policy'
  $txt = $d -join "`n"
  $owner = ''
  if($txt -match 'Device Owner:\s*\n?\s*(?:ComponentInfo\{)?([^\r\n]*)'){ $owner = $Matches[1].Trim().TrimEnd('}') }
  if($txt -match 'mDeviceOwnerName'){ }
  if($owner){ Info "设备所有者 : $owner" } else { Info '设备所有者 : 无' }
  return $owner
}
function Do-Accounts {
  $a = Sh 'dumpsys account'
  $n = 0
  foreach($l in $a){ if($l -match 'Account \{name='){ $n++ } }
  if($n -gt 0){
    Warn "电视上还有 $n 个登录账号（小米账号等）。"
    Info '设备所有者(Device Owner)必须在「没有账号」的设备上才能设置。'
    Info '处理办法（任选其一）：'
    Info '  A) 电视 设置→账号与安全 → 删掉小米账号（推荐，最快）'
    Info '  B) 打开 KidGuard → 家长设置 → 「清除账号数据」（用 dpm 清，不恢复出厂）'
    Info '  C) 恢复出厂设置后再跑本脚本'
  } else { Ok '没有登录账号 —— 可以直接设为设备所有者' }
  return $n
}
function Do-Home {
  $h = Get-HomeActivity
  $line = ''
  foreach($l in ($h -split "`n")){ if($l -match 'packageName=|name=') { $line = $l.Trim() } }
  if(-not $line){ $line = '(未识别)' }
  Info "当前桌面 : $line"
}
function Do-Accessibility {
  $s = Sh 'settings get secure enabled_accessibility_services'
  $v = ($s -join '').Trim()
  if($v -match 'kidguard'){ Ok "无障碍服务已启用 : $v" ; return $true }
  if($v -and $v -ne 'null'){ Warn "无障碍服务已被占用 : $v （KidGuard 会追加进去，不覆盖）" ; return $false }
  Info '无障碍服务 : 未启用'
  return $false
}
function Do-Doctor {
  Title '环境体检'
  if(-not $script:Adb){ Bad '找不到 adb.exe，无法继续'; return $false }
  Ok "adb : $($script:Adb)"
  $v = (AdbRaw @('version') | Select-Object -First 1)
  Info "版本 : $v"
  if(-not (Connect-Device)){ return $false }
  Ok "已连接 : $($script:Dev)"
  Do-Info | Out-Null
  Do-Owner | Out-Null
  $acc = Do-Accounts
  Do-Home
  [void](Do-Accessibility)
  Info ''
  Info '—— 部署条件小结 ——'
  if($script:Sdk -lt 21){ Bad "这台设备的 Android 版本太老（SDK $($script:Sdk)），不支持本方案" ; return $false }
  if($script:Sdk -lt 24){ Warn "SDK $($script:Sdk) < 24：不支持「挂起应用」功能，强锁模式会退化为「弹窗遮挡 + 返回键」" }
  if($acc -gt 0){ Warn '需要先清掉账号才能设为设备所有者（见上面的处理办法）' }
  else { Ok '账号条件满足' }
  return $true
}

# ----------------------------------------------------------------- install
function Do-Install {
  Title '安装部署 KidGuard'
  if(-not $script:Adb){ Bad '找不到 adb.exe，无法继续'; return }
  if(-not (Connect-Device)){ return }
  [void](Do-Info)
  if($script:Sdk -lt 21){ Bad "Android 版本过老（SDK $($script:Sdk)），停止"; return }

  $pkgPath = Sh "pm path $($script:Pkg)"
  $installed = (($pkgPath -join '') -match 'package:')

  # --- 1. 参数
  Title '第 1 步 / 共 6 步：确认参数'
  if($Limit -le 0){ $Limit = 60 }
  if($Step  -le 0){ $Step  = 30 }
  if($Limit -gt 1440){ $Limit = 1440 }
  $pin = Ask-Pin '请设置家长 PIN（4~12 位数字，遥控器上要用它加时）'
  $script:Sdk = Get-Sdk
  Info "每日上限 : $Limit 分钟"
  Info "每次加时 : $Step 分钟"
  Info "家长 PIN : $($pin.Substring(0,2))****"
  if($NoHardLock){ $hl = $false } elseif($HardLock){ $hl = $true } else { $hl = $true }
  Info "强锁模式 : $(if($hl){'开（时间到后挂起所有应用，最严）'}else{'关（只靠锁屏遮挡）'})"
  Info "设备     : $($script:Dev)  (Android SDK $($script:Sdk))"
  Say ''
  Warn '部署后这台电视的桌面会被 KidGuard 取代，且无法在电视上卸载（需要有电脑 + adb 才能交还）。'
  Warn '孩子无法通过遥控器绕过，但「机身 reset 孔 / recovery 双清 / 刷机」属于硬件级操作，应用层拦不住。'
  if(-not (Confirm-Yes '确认开始部署？')){ Warn '已取消'; return }

  # --- 2. 安装 APK
  Title '第 2 步 / 共 6 步：安装应用'
  $apkPath = $Apk
  if(-not $apkPath){
    $c = Join-Path $PSScriptRoot '..\KidGuard-1.0.0.apk'
    if(Test-Path $c){ $apkPath = (Resolve-Path $c).Path }
  }
  if(-not $apkPath -or -not (Test-Path $apkPath)){ Bad '找不到 APK，请用 -Apk 参数指定完整路径'; return }
  # adb 对含中文/空格的本地路径不友好：先复制到纯英文临时目录
  $stage = Join-Path $env:PUBLIC 'KidGuard'
  New-Item -ItemType Directory -Force -Path $stage | Out-Null
  $staged = Join-Path $stage 'KidGuard-1.0.0.apk'
  Copy-Item $apkPath $staged -Force
  Info "APK 已复制到 $staged"
  Step '正在安装（设备可能提示授权，请留意电视屏幕）...'
  $ia = @('install','-r','-t')
  if($script:Sdk -ge 23){ $ia += '-g' }
  $ia += $staged
  $r = AdbDev $ia
  if(($r -join ' ') -match 'Success'){ Ok '安装成功' }
  else {
    Bad "安装失败：$($r -join ' ')"
    Info '如果是 INSTALL_FAILED_TEST_ONLY，说明安装命令少了 -t 参数（本脚本已带，可换个 adb 再试）'
    Info '如果是 INSTALL_FAILED_UPDATE_INCOMPATIBLE，说明这台电视上装过签名不同的 KidGuard：先 adb uninstall com.kidguard.tv'
    Info '如果是 INSTALL_FAILED_USER_RESTRICTED / 被拦截，请到 电视 设置→账号与安全→「安装未知来源应用」里允许，或在小米电视「安全中心」里放行'
    return
  }

  # --- 3. 权限与无障碍
  Title '第 3 步 / 共 6 步：授权与无障碍'
  Sh 'am start -n com.kidguard.tv/.ParentActivity' | Out-Null
  Start-Sleep -Seconds 2
  $r = Kg 'ping'
  if($r.Data){ Ok "应用已响应 : $($r.Data)" } else { Warn '应用广播无响应，继续尝试（可能是首次启动较慢）' }

  $r = Kg 'setpin' $null $pin
  if($r.Data -match 'OK'){ Ok '家长 PIN 已写入' } else { Warn "写入 PIN 返回：$($r.Data)" }

  $ao = Sh 'appops set com.kidguard.tv GET_USAGE_STATS allow'
  if(($ao -join '') -match 'Error|Exception'){ Warn "授予使用情况访问权限失败：$($ao -join ' ')" } else { Ok '已授予「使用情况访问」权限（用于防拔电重置计时）' }

  $exist = (Sh 'settings get secure enabled_accessibility_services') -join ''
  $exist = $exist.Trim()
  $svc = 'com.kidguard.tv/com.kidguard.tv.GuardAccessibilityService'
  if($exist -match 'kidguard'){ Ok '无障碍服务已经启用' }
  else {
    $new = if($exist -and $exist -ne 'null'){ "$exist`:$svc" } else { $svc }
    $w = Sh "settings put secure enabled_accessibility_services $new"
    $w2 = Sh 'settings put secure accessibility_enabled 1'
    $chk = ((Sh 'settings get secure enabled_accessibility_services') -join '').Trim()
    if($chk -match 'kidguard'){ Ok '已自动启用「KidGuard 内容守卫」无障碍服务' }
    else {
      Warn '自动启用无障碍失败，需要在电视上手动开一次：'
      Info '  电视 设置 → 无障碍/辅助功能 → KidGuard 内容守卫 → 打开'
      Info "  （也可以执行 adb shell settings put secure enabled_accessibility_services $new）"
    }
  }

  # --- 4. 设备所有者
  Title '第 4 步 / 共 6 步：设为设备所有者（防卸载、防改设置的关键）'
  $owner = Do-Owner
  if($owner -match 'kidguard'){ Ok 'KidGuard 已经是设备所有者' }
  else {
    $acc = Do-Accounts
    if($acc -gt 0){
      Warn '设备上还有账号，set-device-owner 会失败。'
      if(-not (Confirm-Yes '仍然尝试一次？')){ Warn '已跳过设备所有者设置 —— 管控能力会大打折扣，建议清掉账号后重跑本步骤'; }
      else { $tryIt = $true }
    }
    $r = Sh 'dpm set-device-owner com.kidguard.tv/.GuardAdminReceiver'
    $t = $r -join "`n"
    if($t -match 'Success'){ Ok '已成为设备所有者（Device Owner）' }
    else {
      Bad "设置设备所有者失败：$t"
      if($t -match 'already provisioned'){ Info '→ 设备已被初始化过且存在账号。请删掉账号（或恢复出厂）后重试' }
      if($t -match 'already has device owner'){ Info '→ 已经存在别的设备所有者应用，需要先 remove-active-admin' }
      if($t -match 'not allowed|Cannot'){ Info '→ 部分定制系统会限制，见《使用说明》故障排查一节' }
    }
  }

  # --- 5. 参数下发 + 接管桌面
  Title '第 5 步 / 共 6 步：下发策略并接管桌面'
  $hlv = '0'
  if($hl){ $hlv = '1' }
  $ops = @()
  $ops += ,@('limit', "$Limit")
  $ops += ,@('step', "$Step")
  $ops += ,@('hardlock', $hlv)
  $ops += ,@('sync', '')
  foreach($op in $ops){
    $r = Kg $op[0] $op[1] $pin
    if($r.Data -match 'OK'){ Ok "$($op[0]) -> $($r.Data)" } else { Warn "$($op[0]) -> $($r.Data)" }
  }
  Sh 'dumpsys deviceidle whitelist +com.kidguard.tv' | Out-Null
  Ok '已加入电池优化白名单（防后台被杀）'
  # 不接管桌面：KidGuard 只在后台当守卫，原来的桌面（当贝桌面影视版 / 小米桌面）保持不动。
  # 到点锁定后靠系统的「锁定任务模式」把锁屏钉在最前面，所以不需要抢 HOME 键。
  $homeNow = Get-HomeActivity
  Ok "默认桌面保持不动：$homeNow"
  try { Set-Content -LiteralPath (Join-Path $PSScriptRoot 'last-home.txt') -Value $homeNow -Encoding UTF8 } catch {}
  Sh 'am start -n com.kidguard.tv/.GuardActivity' | Out-Null
  Start-Sleep -Seconds 2

  # --- 6. 验收
  Title '第 6 步 / 共 6 步：验收'
  Show-Status
  Say ''
  Title '部署完成，请照着检查这几件事'
  Info '1) 电视桌面还是原来那个（当贝桌面影视版 / 小米桌面）—— KidGuard 不抢桌面，只在后台当守卫。'
  Info '2) 在电视的应用列表里找到「KidGuard」打开，按遥控器【菜单键】，数字键输 PIN 能进家长设置。'
  Info '3) 拔掉电视电源再插上，等 1 分钟：KidGuard 应该自动在后台起来（到点照样会锁定）。'
  Info '4) 在电视上「设置 → 应用」里试着卸载 KidGuard：应该卸载不掉。'
  Info '5) 家长设置里把「每日上限」临时改成 1 分钟，等 1 分钟：锁屏应该自己跳出来，'
  Info '   这时候按【Home 键】/【返回键】都回不去桌面（画面一直被锁屏钉住）。'
  Info '   测完用「加时一次」解锁，再把上限改回 60。'
  Info '6) 打开酷喵（优酷电视版），把焦点框停在「熊出没」上按【确定】：应该完全没反应（不弹窗、不退出）。'
  Say ''
  Warn '如果第 3 步（断电重启）没有自动起来，说明自启动被系统拦了。'
  Info '  处理：电视 设置→应用→KidGuard→自启动/后台运行 打开；或在小米电视「安全中心」里给 KidGuard 加白。'
  Say ''
  Ok '家长 PIN 请自己记牢。忘了 PIN 时用本脚本的「3-家长操作.bat」仍旧可以操作（只要 adb 还连得上）。'
}

# ------------------------------------------------------------------ status
function Show-Status {
  Title "KidGuard 状态  @ $($script:Dev)"
  $txt = Get-StatusText
  if(-not $txt.Trim()){
    Warn '拿不到状态文件（可能应用没在运行）。尝试唤醒应用...'
    Sh 'am start -n com.kidguard.tv/.GuardActivity' | Out-Null
    Start-Sleep -Seconds 2
    $txt = Get-StatusText
  }
  if($txt.Trim()){ Say $txt 'White' } else { Bad '仍然拿不到状态。可能未安装 KidGuard。' }
  Say ''
  $m = Get-StatusMap $txt
  if($m.Count -gt 0){
    $used = $m['今日已用']; $lim = $m['每日上限']; $rem = $m['剩余']
    if($used){ Ok "今日已用 $used  /  上限 $lim  /  剩余 $rem" }
    if($m['是否已锁定'] -match '^true'){ Warn '当前处于锁定状态' }
    if($m['DeviceOwner'] -match '^true'){ Ok 'DeviceOwner : 是（防卸载/防改设置已生效）' } else { Warn 'DeviceOwner : 否（管控能力不完整）' }
    if($m['无障碍已启用'] -match '^true'){ Ok '无障碍内容守卫 : 已启用' } else { Warn '无障碍内容守卫 : 未启用（优酷关键字拦截不会生效）' }
  }
  Say ''
  $o = Do-Owner
  [void](Do-Accessibility)
  Do-Home
  $cur = Get-HomeActivity
  if($cur -match 'kidguard'){ Warn '默认桌面 : 仍是 KidGuard（建议交还给原桌面，点菜单 3 或运行卸载交还）' }
  else { Ok "默认桌面 : $((($cur -split "`n") | Where-Object { $_ -match 'packageName=|name=' } | Select-Object -First 1))（保持原样）" }
  $top = (Sh 'dumpsys activity activities | grep -i mResumedActivity') -join ''
  if(-not $top.Trim()){ $top = (Sh 'dumpsys window | grep -i mCurrentFocus') -join '' }
  Info "当前前台 : $($top.Trim())"
}
function Do-Status {
  if(-not (Connect-Device)){ return }
  $script:Sdk = Get-Sdk
  Show-Status
}

# ------------------------------------------------------------------ unlock
# ------------------------------------------------------- 黑名单名单类型选择
function Ask-BlKind {
  Say '  1) 关键字名单（模糊匹配）' White
  Say '  2) 片名名单（整名匹配）' White
  $k = Read-Host '选择名单'
  if($k -eq '1'){ return 'blkw' }
  if($k -eq '2'){ return 'bltitle' }
  Warn '无效选择'; return $null
}

function Do-Unlock {
  if(-not (Connect-Device)){ return }
  $script:Sdk = Get-Sdk
  $pin = Ask-Pin
  while($true){
    Title '家长应急操作'
    Say '  1) 加时 30 分钟' White
    Say '  2) 今天完全放行（今天不再锁）' White
    Say '  3) 立即解除锁定' White
    Say '  4) 修改每日可用上限' White
    Say '  5) 修改家长 PIN' White
    Say '  6) 今日已用清零' White
    Say '  7) 取消「今天放行」' White
    Say '  8) 开关「强锁模式」' White
    Say '  9) 查看当前状态' White
    Say ' 10) 加黑名单词条（关键字 / 片名）' White
    Say ' 11) 删黑名单词条' White
    Say ' 12) 开关「内容兜底」（黑名单命中/自动预览时盖黑幕）' White
    Say ' 13) 开关「预览严格模式」（焦点一停就自动播放的小窗预览也算命中）' White
    Say ' 14) 取消全部「家长临时放行」' White
    Say ' 15) 立刻盖一次黑幕（测通道用）' White
    Say ' 16) 设置黑名单遮罩时长（1~10 秒，默认 10）' White
    Say '  0) 退出' White
    $sel = Read-Host '请选择'
    switch($sel){
      '1' { $r = Kg 'add' '30' $pin; Ok "add -> $($r.Data)" }
      '2' { $r = Kg 'free' '1'  $pin; Ok "free -> $($r.Data)" }
      '3' { $r = Kg 'unlock' $null $pin; Ok "unlock -> $($r.Data)" }
      '4' {
        $v = Read-Host '请输入新的每日上限（分钟，例如 90）'
        $r = Kg 'limit' $v $pin; Ok "limit -> $($r.Data)"
      }
      '5' {
        $p2 = Read-Host '请输入新的家长 PIN（4~12 位数字）'
        $r = Kg 'setpin' $null $p2
        if($r.Data -match 'OK'){ Ok "PIN 已更新 -> $($r.Data)"; $pin = $p2 } else { Warn "setpin -> $($r.Data)" }
      }
      '6' { $r = Kg 'reset' $null $pin; Ok "reset -> $($r.Data)" }
      '7' { $r = Kg 'free' '0' $pin; Ok "free off -> $($r.Data)" }
      '8' {
        $cur = KgVal 'status'
        $now = (Kg 'hardlock' '1' $pin).Data
        Ok "hardlock 切换 -> $now"
      }
      '9' { Show-Status }
      '10' {
        $kind = Ask-BlKind
        if($kind){
          $w = Read-Host $(if($kind -eq 'blkw'){'请输入关键字（模糊匹配，例如：熊出没）'}else{'请输入完整片名（整名匹配）'})
          if($w.Trim().Length -gt 0){
            $r = Kg "$kind-add" $w.Trim() $pin; Ok "$kind-add -> $($r.Data)"
          }
        }
      }
      '11' {
        $kind = Ask-BlKind
        if($kind){
          $map = Get-StatusMap (Get-StatusText)
          if($kind -eq 'blkw'){ Say "当前关键字名单：$($map['内容黑名单关键字'])" Gray }
          else { Say "当前片名名单：$($map['内容黑名单片名'])" Gray }
          $w = Read-Host '请输入要删除的词条（原样照抄）'
          if($w.Trim().Length -gt 0){
            $r = Kg "$kind-del" $w.Trim() $pin; Ok "$kind-del -> $($r.Data)"
          }
        }
      }
      '12' {
        $map = Get-StatusMap (Get-StatusText)
        $cur = $map['播放页兜底']
        if($cur -eq 'true' -or $cur -eq 'false'){
          $r = Kg $(if($cur -eq 'true'){'blwatch-off'}else{'blwatch-on'}) $null $pin
          Ok "内容兜底 -> $($r.Data)"
        } else { Warn "没读到「播放页兜底」状态（拿到的是 '$cur'），先按 9 看状态" }
      }
      '13' {
        $map = Get-StatusMap (Get-StatusText)
        $cur = $map['预览严格模式']
        if($cur -like 'true*' -or $cur -like 'false*'){
          $r = Kg $(if($cur -like 'true*'){'strictprev-off'}else{'strictprev-on'}) $null $pin
          Ok "预览严格模式 -> $($r.Data)"
        } else { Warn "没读到「预览严格模式」状态（拿到的是 '$cur'），先按 9 看状态" }
      }
      '14' {
        $r = Kg 'allow-clear' $null $pin; Ok "取消全部放行 -> $($r.Data)"
      }
      '15' {
        $r = Kg 'cover-now' $null $pin; Ok "已盖黑幕（电视上应该出现「这个先不能看」）-> $($r.Data)"
      }
      '16' {
        $map = Get-StatusMap (Get-StatusText)
        $cur = $map['黑名单遮罩']
        Say "当前：$cur" Gray
        $v = Read-Host '请输入遮罩秒数（1~10，回车取消）'
        if($v.Trim().Length -gt 0){
          $r = Kg 'coversec' $v.Trim() $pin; Ok "coversec -> $($r.Data)"
        }
      }
      '0' { return }
      default { Warn '无效选择' }
    }
    Say ''
  }
}

# ----------------------------------------------------------------- monitor
function Do-Monitor {
  if(-not (Connect-Device)){ return }
  $script:Sdk = Get-Sdk
  Title '实时监控（Ctrl+C 退出）'
  Info '每 3 秒刷新：当前前台应用 + 今日已用时间 + 是否锁定；有新日志会滚动显示。'
  $lastLog = ''
  while($true){
    $t = Get-StatusText
    $m = Get-StatusMap $t
    $top = ((Sh 'dumpsys activity activities | grep -i mResumedActivity') -join '')
    if(-not $top.Trim()){ $top = ((Sh 'dumpsys window | grep -i mCurrentFocus') -join '') }
    $top = $top.Trim()
    if($top.Length -gt 90){ $top = $top.Substring(0,90) }
    $line = "[{0:HH:mm:ss}] 已用={1} 上限={2} 剩余={3} 锁定={4} | {5}" -f (Get-Date), $m['今日已用'], $m['每日上限'], $m['剩余'], $m['是否已锁定'], $top
    if($m['是否已锁定'] -match '^true'){ Say $line 'Yellow' } else { Say $line 'Gray' }
    $lg = (Sh 'tail -n 6 /sdcard/kidguard/kidguard.log') -join "`n"
    if($lg -ne $lastLog){
      foreach($l in ($lg -split "`n")){ if($l.Trim()){ Say "      log> $($l.Trim())" 'DarkGray' } }
      $lastLog = $lg
    }
    Start-Sleep -Seconds 3
  }
}

# --------------------------------------------------------------- uninstall
function Do-Uninstall {
  Title '卸载 / 交还设备'
  if(-not (Connect-Device)){ return }
  $script:Sdk = Get-Sdk
  Warn '本操作会把电视交还给原来的桌面，并删除 KidGuard。'
  Warn '顺序很重要：先撤销管控策略 → 再移除设备所有者 → 最后卸载。'
  if(-not (Confirm-Yes '确认卸载交还？')){ Warn '已取消'; return }
  $pin = Ask-Pin '请输入家长 PIN（用于撤销策略）'

  Step '1/5 撤销管控策略（解除挂起、恢复设置/状态栏/账号/安装权限）'
  $r = Kg 'release' $null $pin
  Ok "release -> $($r.Data)"

  Step '2/5 关闭无障碍服务'
  $exist = ((Sh 'settings get secure enabled_accessibility_services') -join '').Trim()
  if($exist -match 'kidguard'){
    $parts = @()
    foreach($p in ($exist -split ':')){ if($p -and $p -notmatch 'kidguard'){ $parts += $p } }
    if($parts.Count -gt 0){ Sh "settings put secure enabled_accessibility_services $($parts -join ':')" | Out-Null }
    else { Sh 'settings delete secure enabled_accessibility_services' | Out-Null }
    Ok '已移除 KidGuard 无障碍服务'
  } else { Info '无障碍服务里没有 KidGuard，跳过' }

  Step '3/5 恢复原来的桌面'
  # 部署时把当时的桌面记在 last-home.txt；有记录就照它恢复，没有才退回小米桌面。
  $want = 'com.mitv.tvhome'
  $f = Join-Path $PSScriptRoot 'last-home.txt'
  if(Test-Path $f){
    $txt = (Get-Content -LiteralPath $f -Raw)
    $m = [regex]::Match($txt, '([A-Za-z0-9_\.]+)/([A-Za-z0-9_\.]+)')
    if($m.Success){ $want = "$($m.Groups[1].Value)/$($m.Groups[2].Value)" }
  }
  Info "按记录恢复桌面 : $want"
  $o = Set-HomeActivity $want
  if($o -match 'Error|Exception'){ $o = Set-HomeActivity 'com.mitv.tvhome' }
  if($o -match 'Error|Exception'){ $o = Set-HomeActivity 'com.mitv.tvhome/.ui.MiTVHomeActivity' }
  Info "set-home-activity -> $o"
  $chk = Get-HomeActivity
  if($chk -match 'kidguard'){
    Warn '自动恢复桌面失败。请在电视上手动操作一次：'
    Info '  电视 设置 → 应用 → 默认应用 → 主屏幕 → 选择「小米电视桌面」或「当贝桌面影视版」'
  } else { Ok "默认桌面已恢复：$chk" }

  Step '4/5 移除设备所有者与设备管理器'
  $r = Sh 'dpm remove-active-admin com.kidguard.tv/.GuardAdminReceiver'
  $t = $r -join ' '
  if($t -match 'Success'){ Ok '已移除设备所有者' }
  else {
    Warn "remove-active-admin -> $t"
    Info '若提示 not testOnly：说明这个 APK 没带 testOnly，只能恢复出厂。本 APK 带了。'
    Info '若提示 not an active admin / unknown：说明本来就没有，可忽略。'
  }

  Step '5/5 卸载应用'
  $r = Sh "pm uninstall $($script:Pkg)"
  if(($r -join '') -match 'Success'){ Ok '已卸载 KidGuard' }
  else {
    Warn "pm uninstall 返回：$($r -join ' ')"
    $r2 = AdbDev @('uninstall',$script:Pkg)
    if(($r2 -join '') -match 'Success'){ Ok '已卸载 KidGuard' } else { Bad "卸载失败：$($r2 -join ' ')" }
  }
  Sh 'rm -rf /sdcard/kidguard' | Out-Null
  Say ''
  Title '交还完成，请检查'
  Do-Owner
  Do-Home
  $pp2 = (Sh "pm path $($script:Pkg)") -join ''
  Info "残留检查: $pp2  （应该为空）"
  Warn '如果「设置」或系统界面还有异常，重启一次电视即可。'
}

# -------------------------------------------------------------------- main
function Show-Help {
  Title 'KidGuard 控制台 —— 用法'
  Say '  0-连接体检.bat     → -Action doctor    : 检查 adb/连接/机型/账号条件'
  Say '  1-安装部署.bat     → -Action install   : 安装 + 授权 + 设为设备所有者 + 接管桌面'
  Say '  2-查看状态.bat     → -Action status    : 看今天用了多久、锁没锁、谁在管'
  Say '  3-家长操作.bat     → -Action unlock    : 加时/放行/改PIN/改上限/解锁'
  Say '  4-实时监控.bat     → -Action monitor   : 实时看当前前台应用和计时'
  Say '  5-卸载交还.bat     → -Action uninstall : 撤销全部策略并把电视还回去'
  Say ''
  Say '  常用参数： -Ip 192.168.1.50   -Pin 1357   -Limit 60   -Step 30   -Yes(免确认)'
}

$script:Adb = Find-Adb
if($Action -eq 'help'){ Show-Help; exit 0 }
if(-not $script:Adb){ Bad '找不到 adb.exe。请确认 dist\tools\adb\adb.exe 存在，或把 adb 加到 PATH。'; exit 1 }

switch($Action){
  'doctor'    { [void](Do-Doctor) }
  'install'   { Do-Install }
  'status'    { Do-Status }
  'unlock'    { Do-Unlock }
  'monitor'   { Do-Monitor }
  'uninstall' { Do-Uninstall }
}
