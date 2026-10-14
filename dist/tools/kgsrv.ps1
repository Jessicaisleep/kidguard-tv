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
#>
[CmdletBinding()]
param(
  [int]$Port = 8899,
  [switch]$NoBrowser
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

function Ensure-Device {
  if (-not $script:Adb) { return $false }
  $me = (Get-Devices) | Where-Object { $_.Id -eq $script:Dev -and $_.State -eq 'device' }
  if ($me) { return $true }
  $ip = (Read-Utf8 $script:IPFile).Trim()
  if ($ip -match '^(\d{1,3}\.){3}\d{1,3}$') {
    $null = AdbRaw @('connect', "$ip`:5555")
    Start-Sleep -Milliseconds 500
    $script:Dev = "$ip`:5555"
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
  }
  $st.localText = Read-Utf8 (Get-LocalPath)
  if (-not $script:Adb) { $st.msg = '找不到 adb.exe（dist\tools\adb\adb.exe 应该在）'; return $st }
  if (-not (Ensure-Device)) {
    $st.msg = '没连上电视：确认电视已开「ADB 网络调试」、和电脑在同一 WiFi，然后在上面填电视 IP 点连接'
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

# 开机先尽量连上（有历史 IP 就自动连）
if ($script:Adb) {
  if (Ensure-Device) { Say ("  电视      " + $script:Dev + " 已连接") 'Green' }
  else { Say '  电视      暂未连接（在页面上填电视 IP 点「连接」即可）' 'Yellow' }
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
