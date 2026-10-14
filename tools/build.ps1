# KidGuard 手工构建（不用 Gradle）
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = Split-Path -Parent $here          # kidguard\

# aapt2 / d8 等原生工具不认中文路径，这里用 ASCII 联接点(D:\kg)映射进去
$asciiRoot = "D:\kg"
if ($proj -notmatch '^[\x20-\x7E]+$') {
    if (-not (Test-Path $asciiRoot)) {
        cmd /c mklink /J "$asciiRoot" "$proj" | Out-Null
    }
    $tgt = ""
    try { $tgt = (Get-Item $asciiRoot -Force).Target } catch { }
    if ($tgt -and ($tgt.TrimEnd('\') -ne $proj.TrimEnd('\'))) {
        cmd /c rmdir "$asciiRoot" | Out-Null
        cmd /c mklink /J "$asciiRoot" "$proj" | Out-Null
    }
    if (-not (Test-Path $asciiRoot)) { throw "无法创建 ASCII 联接点 $asciiRoot -> $proj" }
    $proj = $asciiRoot
    Write-Host "ASCII 构建根: $proj"
}

$app  = Join-Path $proj "app"
$tc   = Join-Path $proj "toolchain"
$sdk  = Join-Path $tc "sdk"
$jdkHome = (Get-ChildItem (Join-Path $tc "jdk") -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkHome

$javac     = Join-Path $jdkHome "bin\javac.exe"
$keytool   = Join-Path $jdkHome "bin\keytool.exe"
$aapt2     = Join-Path $sdk "build-tools\34.0.0\aapt2.exe"
$zipalign  = Join-Path $sdk "build-tools\34.0.0\zipalign.exe"
$apksigner = Join-Path $sdk "build-tools\34.0.0\apksigner.bat"
$d8        = Join-Path $sdk "build-tools\34.0.0\d8.bat"
$androidJar= Join-Path $sdk "platforms\android-34\android.jar"

$build = Join-Path $proj "build"
$dist  = Join-Path $proj "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
foreach ($d in @("compiled", "classes", "dex", "gen")) { New-Item -ItemType Directory -Force -Path (Join-Path $build $d) | Out-Null }
$apkRes = Join-Path $build "res-link.apk"

Write-Host "[1/7] aapt2 compile res ..."
& $aapt2 compile --dir (Join-Path $app "res") -o (Join-Path $build "compiled\res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link ..."
& $aapt2 link -o $apkRes -I $androidJar --manifest (Join-Path $app "AndroidManifest.xml") `
    --java (Join-Path $build "gen") --min-sdk-version 21 --target-sdk-version 28 `
    --version-code 1 --version-name "1.0.0" `
    (Join-Path $build "compiled\res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac ..."
$srcs = Get-ChildItem (Join-Path $app "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$gen  = Get-ChildItem (Join-Path $build "gen") -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$all  = @($srcs) + @($gen)
& $javac -nowarn -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d (Join-Path $build "classes") $all
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8 dex ..."
$classes = Get-ChildItem (Join-Path $build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --min-api 21 --lib $androidJar --output (Join-Path $build "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] 打包 classes.dex ..."
$unsigned = Join-Path $build "unsigned.apk"
Copy-Item $apkRes $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
$dexFiles = Get-ChildItem (Join-Path $build "dex") -Filter *.dex
foreach ($d in $dexFiles) {
    $entryName = $d.Name
    $old = $zip.GetEntry($entryName)
    if ($old) { $old.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $d.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
}
$zip.Dispose()

Write-Host "[6/7] zipalign ..."
$aligned = Join-Path $build "aligned.apk"
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] 签名 ..."
$keys = Join-Path $proj "keys"
New-Item -ItemType Directory -Force -Path $keys | Out-Null
$ks = Join-Path $keys "kidguard.jks"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -v -keystore $ks -alias kidguard -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass kidguard2025 -keypass kidguard2025 `
        -dname "CN=KidGuard, OU=Family, O=Home, L=Home, ST=Home, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$out = Join-Path $dist "KidGuard-1.0.0.apk"
if (Test-Path $out) { Remove-Item $out -Force }
& $apksigner sign --ks $ks --ks-pass pass:kidguard2025 --key-pass pass:kidguard2025 `
    --v1-signing-enabled true --v2-signing-enabled true --out $out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --print-certs $out
Write-Host ""
Write-Host ("APK -> {0}  ({1:N2} MB)" -f $out, ((Get-Item $out).Length / 1MB))
Write-Host "BUILD_OK"
