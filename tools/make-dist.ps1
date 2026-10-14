$ErrorActionPreference = 'Stop'
$src = 'D:\kg\dist'
$out = 'D:\DSH工作区\安卓7以上小米盒子电视精简系统包\KidGuard-交付包.zip'

# 1) 清掉打包用的临时文件（下划线开头）
Get-ChildItem $src -Recurse -File -Filter '_*' | ForEach-Object { "清理临时文件 " + $_.FullName; Remove-Item $_.FullName -Force }

# 1.5) 防复发：.bat 必须是 CRLF（裸 LF 的批处理在 cmd + chcp 65001 下会闪退）
Get-ChildItem $src -Filter '*.bat' -File | ForEach-Object {
  $t = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
  if ($t -match "(?<!\r)\n") {
    $t2 = $t -replace "(?<!\r)\n", "`r`n"
    [System.IO.File]::WriteAllText($_.FullName, $t2, (New-Object System.Text.UTF8Encoding($false)))
    "换行符修正为 CRLF：" + $_.Name
  }
}
# 1.6) 防复发：会被 Windows PowerShell 5.1 执行的 .ps1 必须带 UTF-8 BOM（否则中文直接报解析错误）
foreach ($p in @("$src\tools\kg.ps1", "$src\tools\kgsrv.ps1")) {
  $b = [System.IO.File]::ReadAllBytes($p)
  $hasBom = ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
  if (-not $hasBom) {
    [System.IO.File]::WriteAllText($p, [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8), (New-Object System.Text.UTF8Encoding($true)))
    "已补 UTF-8 BOM：" + (Split-Path -Leaf $p)
  }
}

# 2) 要装进交付包的文件（不含 .idsig、不含 config\ 用户数据）
$include = @(
  '0-连接体检.bat', '1-安装部署.bat', '2-查看状态.bat', '3-家长操作.bat',
  '4-实时监控.bat', '5-卸载交还.bat', '6-电脑控制台.bat',
  '使用说明.txt', 'KidGuard-1.0.0.apk',
  'tools\kg.ps1', 'tools\kgsrv.ps1', 'tools\console.html', 'tools\last-home.txt',
  'tools\adb\adb.exe', 'tools\adb\AdbWinApi.dll', 'tools\adb\AdbWinUsbApi.dll', 'tools\adb\libwinpthread-1.dll'
)

# 3) 旧包留档
if (Test-Path $out) {
  $bak = "$out.$(Get-Date -Format yyyyMMdd-HHmm).bak"
  Copy-Item $out $bak -Force
  "旧包已留档：$bak"
}

# 4) 打包
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $out) { Remove-Item $out -Force }
$zip = [System.IO.Compression.ZipFile]::Open($out, 'Create')
try {
  foreach ($rel in $include) {
    $full = Join-Path $src $rel
    if (-not (Test-Path $full)) { throw "交付包里缺少文件：$rel" }
    $name = $rel -replace '\\', '/'
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $full, $name, [System.IO.Compression.CompressionLevel]::Optimal)
  }
} finally { $zip.Dispose() }

# 5) 复核
$z = [System.IO.Compression.ZipFile]::OpenRead($out)
'--- 交付包内容 ---'
$z.Entries | Sort-Object FullName | ForEach-Object { '  ' + $_.FullName.PadRight(32) + $_.Length.ToString().PadLeft(8) + 'B' }
"条目数 = $($z.Entries.Count)"
$z.Dispose()
"包大小 = $((Get-Item $out).Length) 字节（" + [math]::Round((Get-Item $out).Length / 1MB, 2) + " MB）"
