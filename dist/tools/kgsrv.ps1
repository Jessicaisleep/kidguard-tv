<#
  KidGuard 电脑控制台 —— 本地桥（浏览器界面 + adb）
  --------------------------------------------------------------------------
  用法：双击 dist\6-电脑控制台.bat
        （等价于 powershell -NoProfile -ExecutionPolicy Bypass -File tools\kgsrv.ps1）

  设计要点：
   - 只监听 127.0.0.1，局域网里别的机器连不上；
   - 页面上嵌一次性 token，所有 /api 调用必须带 X-KG-Token（别的网页偷调会被挡）；
   - 对电视的一切操作都走 adb broadcast，和 kg.ps1 是同一套命令；
   - 配置文件放在 dist\config\ ，上传前自动把电视当前配置备份到 dist\config\备份\ 。
   - **电视 IP 自动发现**：扫本机所在 /24 网段的 5555 端口 → adb connect → 读机型/包名
     确认是电视（优先装了 com.kidguard.tv 的那台）→ 记进 config\电视IP.txt。
     不依赖 adb mdns（实测这台小米盒子的 mDNS 发现为空），纯 TCP 探测，1.5 秒左右扫完 253 台。
#>
[CmdletBinding()]
param(
  [int]$Port = 8899,
  [switch]$NoBrowser,
  [switch]$NoScan,               # 启动时不自动找电视（默认会找）
  [int]$ScanMs = 1200            # 每台主机的探测超时（毫秒）；调大更保险、调小更快
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Root      = Split-Path -Parent $PSScriptRoot            # dist\
$script:CfgDir    = Join-Path $script:Root 'config'
$script:BakDir    = Join-Path $script:CfgDir '备份'
$script:LocalName = 'KidGuard-配置.txt'
$script:IPFile    = Join-Path $script:CfgDir '电视IP.txt'
$script:HtmlFile  = Join-Path $PSScriptRoot 'console.html'

$script:Adb  = $null
$script:Dev  = ''
$script:Sdk  = 28
$script:Pkg  = 'com.kidguard.tv'
$script:Recv = 'com.kidguard.tv/.CommandReceiver'
$script:RemoteCfg = '/sdcard/kidguard/config.txt'
$script:RemoteIn  = '/sdcard/kidguard/config.in.txt'
$script:Token = ([guid]::NewGuid().ToString('N')).Substring(0, 16)

# 自动找电视：AutoScan 由 -NoScan 关闭；ScannedOnce 保证一次运行只扫一遍
$script:AutoScan    = (-not $NoScan)
$script:ScannedOnce = $false
$script:LastScan    = $null      # 最近一次扫描结果（页面「自动找电视」用）

# 允许从控制台发起的命令（白名单，防止页面被改写后乱发命令）
$script:Allowed = @(
  'ping','status','get','apps','dump','owner','acc','sync','conf','conf-set',
  'setpin','limit','add','step','reset','free','lock','unlock','start','suspend','resume','release',
  'guard-on','guard-off','enforce-on','enforce-off','bl-on','bl-off','hardlock','targets',
  'blkw-add','blkw-del','bltitle-add','bltitle-del','blpkg-add','blpkg-del','wl-add','wl-del',
  'blwatch-on','blwatch-off','strictprev-on','strictprev-off','allow-clear','cover-now','test-blk'
)

function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Stamp { return (Get-Date).ToString('yyyyMMdd-HHmmss') }
function Stamp2 { return (Get-Date).ToString('MM-dd HH:mm:ss') }

function Read-Utf8([string]$p) {
  try { if (Test-Path -LiteralPath $p) { return [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8) } } catch { }
  return ''
}
function Write-Utf8([string]$p, [string]$t) {
  $d = Split-Path -Parent $p
  if ($d -and -not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
  [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
}
function Get-LocalPath { return (Join-Path $script:CfgDir $script:LocalName) }

# ---------------------------------------------------------------- adb 基础
function Find-Adb {
  $cands = @(
    (Join-Path $PSScriptRoot 'adb\adb.exe'),
    (Join-Path $PSScriptRoot '..\..\toolchain\sdk\platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path $env:ProgramFiles 'Android\platform-tools\adb.exe')
  )
  $x86 = ${env:ProgramFiles(x86)}
  if ($x86) { $cands += (Join-Path $x86 'Android\platform-tools\adb.exe') }
  foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path } }
  $g = Get-Command adb.exe -ErrorAction SilentlyContinue
  if ($g) { return $g.Source }
  return $null
}
function AdbRaw([string[]]$a) { return @(& $script:Adb @a 2>&1 | ForEach-Object { $_.ToString() }) }
function AdbDev([string[]]$a) { return @(& $script:Adb -s $script:Dev @a 2>&1 | ForEach-Object { $_.ToString() }) }
function Sh([string]$c) { return AdbDev @('shell', $c) }

function Get-Devices {
  $out = @()
  if (-not $script:Adb) { return $out }
  foreach ($l in (AdbRaw @('devices'))) {
    if ($l -match '^(\S+)\s+(device|unauthorized|offline|recovery|sideload)\s*$') {
      $out += [pscustomobject]@{ Id = $Matches[1]; State = $Matches[2] }
    }
  }
  return $out
}

# ------------------------------------------------- 电视 IP 自动发现（扫网段）
# 为什么要自己扫：这台小米盒子的 adb mdns 发现是空的（adb mdns services 无输出），
# 所以靠不了 mdns。改为并发 TCP 探测本机 /24 网段里开着 5555 的主机（约 1.5 秒扫完 253 台），
# 再逐个 adb connect，用「机型 + 是否装了 com.kidguard.tv」打分挑出电视。
function Get-LocalV4 {
  $out = @()
  try {
    $nics = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
      Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' }
    foreach ($a in $nics) {
      foreach ($p in $a.GetIPProperties().UnicastAddresses) {
        if ($p.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
        $ip = $p.Address.ToString()
        if ($ip -like '127.*' -or $ip -like '169.254.*') { continue }
        $out += $ip
      }
    }
  } catch { }
  return $out
}

# 并发探测：返回开着 5555 的 IP 列表
function Scan-Port5555([int]$timeoutMs) {
  $lans = @(Get-LocalV4)
  if ($lans.Count -eq 0) { return @() }
  $targets = @()
  foreach ($ip in $lans) {
    $o = $ip.Split('.')
    for ($i = 1; $i -le 254; $i++) {
      $t = $o[0] + '.' + $o[1] + '.' + $o[2] + '.' + $i
      if ($lans -notcontains $t) { $targets += $t }
    }
  }
  if ($targets.Count -eq 0) { return @() }
  $n = $targets.Count
  $tasks = New-Object 'System.Threading.Tasks.Task[]' $n
  $clients = New-Object 'System.Net.Sockets.TcpClient[]' $n
  for ($i = 0; $i -lt $n; $i++) {
    $c = New-Object System.Net.Sockets.TcpClient
    $clients[$i] = $c
    try { $tasks[$i] = $c.ConnectAsync($targets[$i], 5555) } catch { }
  }
  try { $null = [System.Threading.Tasks.Task]::WaitAll($tasks, $timeoutMs) } catch { }
  $open = @()
  for ($i = 0; $i -lt $n; $i++) {
    $c = $clients[$i]
    $isOpen = $false
    try { if ($c) { $isOpen = $c.Connected } } catch { }
    if ($isOpen) { $open += $targets[$i] }
    try { if ($c) { $c.Close() } } catch { }
  }
  return $open
}

# 扫描 + 连上去认机型，返回候选列表（按「像电视」的程度降序）
function Find-TvByScan {
  $open = @(Scan-Port5555 $ScanMs)
  if ($open.Count -eq 0) { return @() }
  $cands = @()
  foreach ($ip in $open) {
    $serial = $ip + ':5555'
    $conn = (& $script:Adb connect $serial 2>&1 | Out-String).Trim()
    Start-Sleep -Milliseconds 250
    $state = ''
    foreach ($d in (Get-Devices)) { if ($d.Id -eq $serial) { $state = $d.State } }
    if ($state -ne 'device') {
      # 不是 adb 设备（或需要授权）：不列进候选，顺手断开免得占着
      if ($state -eq '') { $null = AdbRaw @('disconnect', $serial) }
      continue
    }
    $model = (& $script:Adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
    $brand = (& $script:Adb -s $serial shell getprop ro.product.brand 2>&1 | Out-String).Trim()
    $kg    = (& $script:Adb -s $serial shell 'pm path com.kidguard.tv' 2>&1 | Out-String).Trim()
    $hasKg = ($kg -like '*package:*')
    $score = 0
    if ($hasKg) { $score += 100 }                                  # 装了 KidGuard 基本就是它
    if ($model -match '(?i)tv|box|mitv|mibox|shield|magic') { $score += 10 }
    if ($brand -match '(?i)xiaomi|redmi|mi') { $score += 3 }
    $cands += [pscustomobject]@{
      ip = $ip; serial = $serial; model = $model; brand = $brand; kidguard = $hasKg; score = $score
      conn = $conn
    }
  }
  return @($cands | Sort-Object -Property score -Descending)
}

# 扫描并返回排好序的候选（给页面「自动找电视」用）
function Scan-Tv {
  $t0 = Get-Date
  $cands = @(Find-TvByScan)
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  return @{ cands = $cands; ms = $ms; lan = (@(Get-LocalV4) -join ', ') }
}

function Ensure-Device {
  if (-not $script:Adb) { return $false }
  $me = (Get-Devices) | Where-Object { $_.Id -eq $script:Dev -and $_.State -eq 'device' }
  if ($me) { return $true }
  $ip = (Read-Utf8 $script:IPFile).Trim()
  if ($ip -match '^(\d{1,3}\.){3}\d{1,3}$') {
    $script:Dev = "$ip`:5555"
    $null = AdbRaw @('connect', $script:Dev)
    Start-Sleep -Milliseconds 500
    $me = (Get-Devices) | Where-Object { $_.Id -eq $script:Dev -and $_.State -eq 'device' }
    if ($me) { return $true }
    Say ("  电视 " + $ip + " 连不上（记录里的 IP 可能已变），开始扫描网段…") 'Yellow'
  }
  # 记录里的 IP 没用 → 扫网段自动找（每次运行最多扫一次，避免每次操作都等 1.5 秒）
  if ($script:AutoScan -and -not $script:ScannedOnce) {
    $script:ScannedOnce = $true
    $c = @(Find-TvByScan)
    if ($c.Count -ge 1) {
      $best = $c[0]
      $script:Dev = $best.serial
      Write-Utf8 $script:IPFile $best.ip
      Say ("  自动找到电视  " + $best.ip + "  " + $best.model + "  (已记进 config\电视IP.txt)") 'Green'
      return $true
    }
    Say '  扫描完毕：网段里没找到开着 ADB 的电视' 'Yellow'
  }
  if (-not $script:Dev) {
    $g = @((Get-Devices) | Where-Object { $_.State -eq 'device' })
    if ($g.Count -ge 1) { $script:Dev = $g[0].Id }
  }
  if (-not $script:Dev) { return $false }
  $me = (Get-Devices) | Where-Object { $_.Id -eq $script:Dev -and $_.State -eq 'device' }
  return [bool]$me
}

# ------------------------------------------------------- KidGuard 指令通道
function Kg-Raw([string]$cmd, [string]$val = $null, [string]$pinArg = $null) {
  $a = @('shell', 'am', 'broadcast', '-n', $script:Recv, '-a', 'com.kidguard.tv.CMD', '--es', 'cmd', $cmd)
  # 注意：这里必须用 -and 判断非空。若传空串会拼出 "--es val" 后面没有值，
  # am 直接报错、广播根本不发出去，所有只读命令（status/apps/conf）都会拿到空结果。
  if ($pinArg) { $a += @('--es', 'pin', $pinArg) }
  if ($val -and $val.Trim().Length -gt 0) { $a += @('--es', 'val', $val) }
  if ($script:Sdk -ge 31) { $a += @('--include-stopped-packages') }
  return (AdbDev $a)
}

function Kg([string]$cmd, [string]$val = $null, [string]$pinArg = $null) {
  $out = Kg-Raw $cmd $val $pinArg
  $txt = ($out -join "`n")
  $i = $txt.IndexOf('data="')
  if ($i -lt 0) { return '' }
  $s = $txt.Substring($i + 6)
  $j = $s.LastIndexOf('"')
  if ($j -ge 0) { $s = $s.Substring(0, $j) }
  try { $s = $s -replace '\r', '' } catch { }
  return $s.Trim()
}

function Get-RemoteText([string]$remote) {
  $sb = New-Object System.Text.StringBuilder
  foreach ($l in (AdbDev @('shell', 'cat', $remote))) { [void]$sb.AppendLine($l.TrimEnd("`r")) }
  return $sb.ToString()
}

# ------------------------------------------------------------- 文本解析
function Parse-Status([string]$t) {
  $h = @{}
  foreach ($line in ($t -split "`n")) {
    $s = $line.TrimEnd()
    if ($s -eq '' -or $s.StartsWith('===')) { continue }
    $i = $s.IndexOfAny([char[]]@(' ', "`t"))
    if ($i -lt 1) { continue }
    $h[$s.Substring(0, $i)] = $s.Substring($i).Trim()
  }
  return $h
}

function Parse-Conf([string]$t) {
  $h = [ordered]@{}
  foreach ($line in ($t -split "`n")) {
    $s = $line.Trim()
    if ($s -eq '' -or $s.StartsWith('#')) { continue }
    $i = $s.IndexOf('=')
    if ($i -le 0) { continue }
    $h[$s.Substring(0, $i).Trim().ToLower()] = $s.Substring($i + 1).Trim()
  }
  return $h
}

function Diff-Conf([string]$tvText, [string]$localText) {
  $tv = Parse-Conf $tvText
  $lo = Parse-Conf $localText
  $keys = @()
  foreach ($k in $tv.Keys) { $keys += $k }
  foreach ($k in $lo.Keys) { if ($keys -notcontains $k) { $keys += $k } }
  $out = @()
  foreach ($k in $keys) {
    $a = if ($tv.Contains($k)) { [string]$tv[$k] } else { '' }
    $b = if ($lo.Contains($k)) { [string]$lo[$k] } else { '' }
    $out += [pscustomobject]@{ key = $k; tv = $a; local = $b; same = ($a -eq $b) }
  }
  return $out
}

# ------------------------------------------------------------- 状态快照
function Get-State {
  $st = [ordered]@{
    adb = [bool]$script:Adb; tv = $false; device = $script:Dev; sdk = $script:Sdk
    msg = ''; localPath = (Get-LocalPath); localText = ''; confText = ''
    conf = @{}; status = @{}; diff = @(); backups = @()
    lanIP = (@(Get-LocalV4) -join ', '); autoScan = [bool]$script:AutoScan
  }
  $st.localText = Read-Utf8 (Get-LocalPath)
  if (-not $script:Adb) { $st.msg = '找不到 adb.exe（dist\tools\adb\adb.exe 应该在）'; return $st }
  if (-not (Ensure-Device)) {
    $st.msg = '没连上电视：确认电视已开「ADB 网络调试」、和电脑在同一 WiFi，然后点上面的「自动找电视」'
    return $st
  }
  $st.sdk = [int](0 + ((Sh 'getprop ro.build.version.sdk') | Select-Object -First 1))
  $null = Kg 'status'
  $st.status = Parse-Status (Get-RemoteText '/sdcard/kidguard/status.txt')
  $null = Kg 'conf'
  $st.confText = Get-RemoteText $script:RemoteCfg
  $st.conf = Parse-Conf $st.confText
  $st.diff = Diff-Conf $st.confText $st.localText
  $st.tv = ($st.status.Count -gt 0)
  if (-not $st.tv) { $st.msg = '电视连上了，但 KidGuard 没响应（可能没装或没启动）' }
  $st.backups = Get-Backups
  return $st
}

function Get-Backups {
  $out = @()
  try {
    if (Test-Path -LiteralPath $script:BakDir) {
      foreach ($f in (Get-ChildItem -LiteralPath $script:BakDir -Filter *.txt | Sort-Object Name -Descending | Select-Object -First 40)) {
        $out += [pscustomobject]@{ name = $f.Name; size = $f.Length; time = $f.LastWriteTime.ToString('MM-dd HH:mm:ss') }
      }
    }
  } catch { }
  return $out
}

# --------------------------------------------------------------- 操作派发
function Invoke-Op([string]$op, $a) {
  $r = @{ ok = $true; msg = ''; out = ''; state = $null }
  try {
    switch ($op) {
      'state' { $r.state = Get-State; $r.msg = $r.state.msg }

      'connect' {
        $ip = [string]$a.ip
        if ($ip -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { return @{ ok = $false; msg = 'IP 格式不对（例如 192.168.1.50）' } }
        $o = AdbRaw @('connect', "$ip`:5555")
        Write-Utf8 $script:IPFile $ip
        $script:Dev = "$ip`:5555"
        Start-Sleep -Milliseconds 400
        $ok = Ensure-Device
        return @{ ok = $ok; msg = (($o -join ' ') + ' → ' + $(if ($ok) { '已连上' } else { '连不上' })) ; state = (Get-State) }
      }

      'scan' {
        if (-not $script:Adb) { return @{ ok = $false; msg = '找不到 adb.exe' } }
        $pick = [string]$a.ip
        if ($pick) {
          # 页面点了候选列表里的某一台 → 连它
          if ($pick -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { return @{ ok = $false; msg = 'IP 格式不对' } }
          $o = AdbRaw @('connect', "$pick`:5555")
          Write-Utf8 $script:IPFile $pick
          $script:Dev = "$pick`:5555"
          Start-Sleep -Milliseconds 400
          $ok = Ensure-Device
          Say ("  [扫描] 选用 " + $pick + " → " + $(if ($ok) { '已连上' } else { '连不上' })) $(if ($ok) { 'Green' } else { 'Red' })
          return @{ ok = $ok; msg = ($pick + ' → ' + $(if ($ok) { '已连上' } else { '连不上' })); out = $script:LastScan; state = (Get-State) }
        }
        Say '  [扫描] 正在扫本机网段里的 5555 端口…' 'DarkGray'
        $sc = Scan-Tv
        $script:LastScan = $sc.cands
        $n = @($sc.cands).Count
        if ($n -eq 0) {
          Say '  [扫描] 没找到' 'Yellow'
          return @{ ok = $false; msg = ('扫了本机网段（' + $sc.lan + '），' + $sc.ms + ' ms，没找到开着 ADB 调试的电视。检查电视的「ADB 网络调试」是否开着。'); out = @() }
        }
        $best = @($sc.cands)[0]
        Say ('  [扫描] 找到 ' + $n + ' 台，最优 ' + $best.ip + '  ' + $best.model + '  (' + $sc.ms + ' ms)') 'Green'
        $script:LastScan = $sc.cands
        return @{ ok = $true; msg = ('找到 ' + $n + ' 台候选，用时 ' + $sc.ms + ' ms。点一个连上：'); out = $sc.cands; state = $null }
      }

      'do' {
        $cmd = [string]$a.cmd
        $val = [string]$a.val
        $pin = [string]$a.pin
        if ($script:Allowed -notcontains $cmd) { return @{ ok = $false; msg = "不允许的命令：$cmd" } }
        if (-not (Ensure-Device)) { return @{ ok = $false; msg = '没连上电视' } }
        if ($val -eq '') { $val = $null }
        if ($pin -eq '') { $pin = $null }
        # "__ALL__"：整张名单清空（页面上「清空白名单」按钮用）
        if ($val -eq '__ALL__') {
          $map = @{ 'wl-del' = 'white'; 'blkw-del' = 'blkw'; 'bltitle-del' = 'bltitle'; 'blpkg-del' = 'blpkg' }
          if (-not $map.ContainsKey($cmd)) { return @{ ok = $false; msg = "不支持批量清除：$cmd" } }
          $st0 = Get-State
          $items = @()
          $cur = [string]$st0.conf[$map[$cmd]]
          if ($cur) { $items = @($cur -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }) }
          if ($items.Count -eq 0) { return @{ ok = $true; msg = '这张名单本来就是空的'; state = $st0 } }
          foreach ($one in $items) { $null = Kg $cmd $one $pin }
          return @{ ok = $true; msg = ("已清空 " + $items.Count + " 项：" + ($items -join '、')); state = (Get-State) }
        }
        $data = Kg $cmd $val $pin
        $bad = ($data -match '^(FAIL|DENIED|UNKNOWN|ERROR)')
        return @{ ok = (-not $bad); msg = $data; state = (Get-State) }
      }

      'download' {
        if (-not (Ensure-Device)) { return @{ ok = $false; msg = '没连上电视' } }
        $null = Kg 'conf'
        $txt = Get-RemoteText $script:RemoteCfg
        if ($txt.Trim().Length -lt 10) {
          return @{ ok = $false; msg = '电视没有返回配置。若电视上装的是旧版 APK（没有 conf 命令），请先重新部署一次。' }
        }
        $lp = Get-LocalPath
        if (Test-Path -LiteralPath $lp) {
          Write-Utf8 (Join-Path $script:BakDir ("本机-" + (Stamp) + ".txt")) (Read-Utf8 $lp)
        }
        Write-Utf8 $lp $txt
        Say ("  [下载] 电视 → 本机  " + $lp) 'Green'
        return @{ ok = $true; msg = '已把电视配置下载到本机'; out = $txt; state = (Get-State) }
      }

      'upload' {
        if (-not (Ensure-Device)) { return @{ ok = $false; msg = '没连上电视' } }
        $lp = Get-LocalPath
        if (-not (Test-Path -LiteralPath $lp)) { return @{ ok = $false; msg = '本机还没有配置文件：先「从电视下载配置」，或「保存到本机」' } }
        $pin = [string]$a.pin
        if (-not $pin) { return @{ ok = $false; msg = '上传需要家长 PIN' } }
        $null = Kg 'conf'
        $tvTxt = Get-RemoteText $script:RemoteCfg
        if ($tvTxt.Trim().Length -gt 10) {
          Write-Utf8 (Join-Path $script:BakDir ("电视-" + (Stamp) + ".txt")) $tvTxt
        }
        $tmp = Join-Path $env:TEMP 'kidguard-config-in.txt'
        Write-Utf8 $tmp (Read-Utf8 $lp)
        $push = AdbDev @('push', $tmp, $script:RemoteIn)
        $data = Kg 'conf-set' $null $pin
        $bad = ($data -match '^(FAIL|DENIED)')
        Say ("  [上传] 本机 → 电视  " + $(if ($bad) { '失败' } else { '成功' })) $(if ($bad) { 'Red' } else { 'Green' })
        return @{ ok = (-not $bad); msg = $(if ($bad) { $data } else { '已上传到电视（电视当前配置已自动备份到 config\备份\）' }); out = (($push -join "`n") + "`n" + $data); state = (Get-State) }
      }

      'saveLocal' {
        $t = [string]$a.text
        if ($t.Trim().Length -lt 5) { return @{ ok = $false; msg = '内容太短，没保存' } }
        Write-Utf8 (Get-LocalPath) $t
        Say '  [保存] 本机配置文件已更新' 'Green'
        return @{ ok = $true; msg = '已保存到本机配置文件'; state = (Get-State) }
      }

      'apps' {
        if (-not (Ensure-Device)) { return @{ ok = $false; msg = '没连上电视' } }
        $data = Kg 'apps'
        $list = @()
        foreach ($line in ($data -split "`r?`n")) {
          $p = $line.Split('|')
          if ($p.Count -ge 3) { $list += [pscustomobject]@{ pkg = $p[0]; kind = $p[1]; name = $p[2] } }
        }
        return @{ ok = ($list.Count -gt 0); msg = ("已装应用 " + $list.Count + " 个"); out = $list }
      }

      'backups' { return @{ ok = $true; msg = ''; out = (Get-Backups) } }

      'loadBackup' {
        $n = [string]$a.name
        $src = Join-Path $script:BakDir $n
        if (-not (Test-Path -LiteralPath $src)) { return @{ ok = $false; msg = "找不到备份 $n" } }
        $lp = Get-LocalPath
        if (Test-Path -LiteralPath $lp) { Write-Utf8 (Join-Path $script:BakDir ("本机-" + (Stamp) + ".txt")) (Read-Utf8 $lp) }
        Write-Utf8 $lp (Read-Utf8 $src)
        return @{ ok = $true; msg = "已把备份 $n 载入本机配置文件（改完点「上传」才会生效）"; state = (Get-State) }
      }

      default { return @{ ok = $false; msg = "未知操作 $op" } }
    }
  } catch {
    return @{ ok = $false; msg = ("服务器异常：" + $_.Exception.Message) }
  }
  return $r
}

# ------------------------------------------------------------ HTTP 小工具
function Read-Request($stream) {
  $ms = New-Object System.IO.MemoryStream
  $b = New-Object byte[] 1
  $head = ''
  while ($true) {
    $n = $stream.Read($b, 0, 1)
    if ($n -le 0) { break }
    [void]$ms.WriteByte($b[0])
    if ($ms.Length -ge 4) {
      $arr = $ms.ToArray()
      $L = $arr.Length
      if ($arr[$L - 4] -eq 13 -and $arr[$L - 3] -eq 10 -and $arr[$L - 2] -eq 13 -and $arr[$L - 1] -eq 10) { break }
    }
    if ($ms.Length -gt 200000) { break }
  }
  $arr = $ms.ToArray()
  if ($arr.Length -ge 4) { $head = [System.Text.Encoding]::UTF8.GetString($arr, 0, $arr.Length - 4) } else { $head = [System.Text.Encoding]::UTF8.GetString($arr) }
  $lines = $head -split "`r`n"
  $method = 'GET'; $path = '/'
  $headers = @{}
  if ($lines.Count -gt 0 -and $lines[0] -match '^(\S+)\s+(\S+)') { $method = $Matches[1]; $path = $Matches[2] }
  for ($i = 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^([^:]+):\s*(.*)$') { $headers[$Matches[1].Trim().ToLower()] = $Matches[2].Trim() }
  }
  $len = 0
  if ($headers.ContainsKey('content-length')) { $null = [int]::TryParse($headers['content-length'], [ref]$len) }
  $body = ''
  if ($len -gt 0) {
    $buf = New-Object byte[] $len
    $got = 0
    while ($got -lt $len) {
      $n = $stream.Read($buf, $got, $len - $got)
      if ($n -le 0) { break }
      $got += $n
    }
    $body = [System.Text.Encoding]::UTF8.GetString($buf, 0, $got)
  }
  if ($path.Contains('?')) { $path = $path.Substring(0, $path.IndexOf('?')) }
  return @{ method = $method; path = $path; headers = $headers; body = $body }
}

function Send-Bytes($stream, [int]$code, [string]$ctype, [byte[]]$body) {
  $codeText = 'OK'
  if ($code -eq 403) { $codeText = 'Forbidden' }
  if ($code -eq 404) { $codeText = 'Not Found' }
  if ($code -eq 500) { $codeText = 'Server Error' }
  $head = "HTTP/1.1 $code $codeText`r`nContent-Type: $ctype`r`nContent-Length: $($body.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
  $hb = [System.Text.Encoding]::ASCII.GetBytes($head)
  $stream.Write($hb, 0, $hb.Length)
  if ($body.Length -gt 0) { $stream.Write($body, 0, $body.Length) }
  $stream.Flush()
}
function Send-Text($stream, [int]$code, [string]$ctype, [string]$text) {
  Send-Bytes $stream $code $ctype ([System.Text.Encoding]::UTF8.GetBytes($text))
}

function Handle-Client($client) {
  $stream = $client.GetStream()
  $stream.ReadTimeout = 15000
  $req = Read-Request $stream
  if ($req.method -eq 'GET' -and ($req.path -eq '/' -or $req.path -eq '/index.html')) {
    $html = Read-Utf8 $script:HtmlFile
    if ($html -eq '') { Send-Text $stream 500 'text/plain; charset=utf-8' 'console.html 不见了（应该在 tools\console.html）'; return }
    $html = $html.Replace('__KG_TOKEN__', $script:Token)
    Send-Text $stream 200 'text/html; charset=utf-8' $html
    return
  }
  if ($req.path -eq '/favicon.ico') { Send-Text $stream 200 'image/x-icon' ''; return }
  if ($req.path -eq '/api/call') {
    $tok = ''
    if ($req.headers.ContainsKey('x-kg-token')) { $tok = [string]$req.headers['x-kg-token'] }
    if ($tok -ne $script:Token) { Send-Text $stream 403 'application/json' '{"ok":false,"msg":"token 不对"}'; return }
    $op = ''; $args = @{}
    try {
      $j = ConvertFrom-Json $req.body
      $op = [string]$j.op
      if ($j.args) { $args = $j.args }
    } catch {
      Send-Text $stream 200 'application/json' '{"ok":false,"msg":"请求体不是合法 JSON"}'; return
    }
    $t0 = Get-Date
    $res = Invoke-Op $op $args
    $ms = [int]((Get-Date) - $t0).TotalMilliseconds
    Say ("  [{0}] {1}  ({2} ms)" -f (Stamp2), $op, $ms) 'DarkGray'
    $json = ConvertTo-Json $res -Depth 8 -Compress
    Send-Text $stream 200 'application/json; charset=utf-8' $json
    return
  }
  Send-Text $stream 404 'text/plain; charset=utf-8' 'not found'
}

# ------------------------------------------------------------------- main
Say ''
Say '  KidGuard 电脑控制台' 'Cyan'
Say ('  ' + ('-' * 46)) 'DarkCyan'

$script:Adb = Find-Adb
if (-not $script:Adb) { Say '  [失败] 找不到 adb.exe（应为 dist\tools\adb\adb.exe）' 'Red' }
else { Say ("  adb       " + $script:Adb) 'DarkGray' }

if (Test-Path -LiteralPath $script:HtmlFile) { Say '  页面      tools\console.html' 'DarkGray' } else { Say '  [失败] 缺少 tools\console.html' 'Red' }
Say ("  配置目录  " + $script:CfgDir) 'DarkGray'

# 开机先尽量连上：先试 config\电视IP.txt 里的历史 IP，连不上就自动扫网段
if ($script:Adb) {
  if (-not $script:AutoScan) {
    Say '  电视      跳过自动查找（-NoScan）；在页面上填 IP 或点「自动找电视」' 'DarkGray'
  } elseif (Ensure-Device) {
    Say ("  电视      " + $script:Dev + " 已连接") 'Green'
  } else {
    Say '  电视      暂未连接：确认电视开着「ADB 网络调试」且和电脑同一 WiFi，然后点页面上的「自动找电视」' 'Yellow'
  }
}

$listener = $null
$usedPort = $Port
for ($try = 0; $try -lt 12; $try++) {
  try {
    $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $usedPort)
    $l.Start()
    $listener = $l
    break
  } catch {
    $usedPort++
    if ($usedPort -gt 65500) { $usedPort = 8899 }
  }
}
if (-not $listener) { Say '  [失败] 起不了本地端口，换台机器或先关掉占用 8899 的程序' 'Red'; exit 1 }

$url = "http://127.0.0.1:$usedPort/"
Say ''
Say ("  控制台地址  " + $url) 'Cyan'
Say '  关掉这个窗口 = 关掉控制台（电视上的设置不受影响）' 'DarkGray'
Say ''

if (-not $NoBrowser) { try { Start-Process $url } catch { } }

$stop = $false
try {
  while (-not $stop) {
    $client = $listener.AcceptTcpClient()
    try { Handle-Client $client } catch { Say ('  [错误] ' + $_.Exception.Message) 'Red' }
    finally { try { $client.Close() } catch { } }
  }
} finally {
  try { $listener.Stop() } catch { }
  Say ''
  Say '  控制台已停止。' 'DarkGray'
}
