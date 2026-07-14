# 아맞다주차! 개발·직접 설치용 APK build script (Google Play 출시는 build-release.ps1 사용)
# Usage:  powershell -ExecutionPolicy Bypass -File build.ps1
# Output: dist\AMatdaParking.apk

$ErrorActionPreference = "Stop"

# ---- config -----------------------------------------------------------
$VersionCode = 11
$VersionName = "2.5"
$MinSdk      = 26
$TargetSdk   = 35
# -----------------------------------------------------------------------

$Root = $PSScriptRoot
$Sdk  = $env:ANDROID_HOME
if (-not $Sdk)  { $Sdk  = "$env:LOCALAPPDATA\Android\Sdk" }
$Jdk  = $env:JAVA_HOME
if (-not $Jdk)  { $Jdk  = "$env:LOCALAPPDATA\Android\jdk21" }

$Bt       = Join-Path $Sdk "build-tools\35.0.0"
$Platform = Join-Path $Sdk "platforms\android-35\android.jar"
$Aapt2    = Join-Path $Bt "aapt2.exe"
$Zipalign = Join-Path $Bt "zipalign.exe"
$Java     = Join-Path $Jdk "bin\java.exe"
$Javac    = Join-Path $Jdk "bin\javac.exe"
$Jar      = Join-Path $Jdk "bin\jar.exe"
$Keytool  = Join-Path $Jdk "bin\keytool.exe"

foreach ($p in @($Aapt2, $Zipalign, $Platform, $Java, $Javac)) {
    if (-not (Test-Path $p)) { throw "Missing tool: $p" }
}

$Build = Join-Path $Root "build"
$Dist  = Join-Path $Root "dist"
if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force $Build, $Dist | Out-Null

function Run($exe, $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "FAILED ($LASTEXITCODE): $exe $($arguments -join ' ')" }
}

Write-Host "[1/6] compiling resources (aapt2)..."
Run $Aapt2 @("compile", "--dir", "$Root\app\res", "-o", "$Build\res.zip")
Run $Aapt2 @("link",
    "-o", "$Build\base.apk",
    "-I", $Platform,
    "--manifest", "$Root\app\AndroidManifest.xml",
    "--java", "$Build\gen",
    "--min-sdk-version", "$MinSdk",
    "--target-sdk-version", "$TargetSdk",
    "--version-code", "$VersionCode",
    "--version-name", "$VersionName",
    "$Build\res.zip")

Write-Host "[2/6] compiling java..."
$Sources = @(Get-ChildItem -Recurse "$Root\app\src" -Filter *.java | ForEach-Object { $_.FullName })
$Sources += @(Get-ChildItem -Recurse "$Build\gen" -Filter *.java | ForEach-Object { $_.FullName })
New-Item -ItemType Directory -Force "$Build\classes" | Out-Null
Run $Javac (@("-classpath", $Platform, "-encoding", "UTF-8",
    "-source", "1.8", "-target", "1.8", "-nowarn",
    "-d", "$Build\classes") + $Sources)

Write-Host "[3/6] shrinking + dexing (R8)..."
Run $Jar @("cf", "$Build\classes.jar", "-C", "$Build\classes", ".")
Run $Java @("-cp", "$Bt\lib\d8.jar", "com.android.tools.r8.R8",
    "--release", "--lib", $Platform, "--min-api", "$MinSdk",
    "--pg-conf", "$Root\rules.pro",
    "--output", $Build, "$Build\classes.jar")

Write-Host "[4/6] packaging..."
Run $Jar @("uf", "$Build\base.apk", "-C", $Build, "classes.dex")
Run $Zipalign @("-f", "-p", "4", "$Build\base.apk", "$Build\aligned.apk")

Write-Host "[5/6] signing..."
$Keystore = Join-Path $Root "debug.keystore"
if (-not (Test-Path $Keystore)) {
    Write-Host "      generating debug keystore (first run only)..."
    Run $Keytool @("-genkeypair", "-keystore", $Keystore,
        "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10950", "-dname", "CN=Android Debug,O=Android,C=US")
}
$Apk = Join-Path $Dist "AMatdaParking.apk"
Run $Java @("-jar", "$Bt\lib\apksigner.jar", "sign",
    "--ks", $Keystore, "--ks-pass", "pass:android",
    "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
    "--out", $Apk, "$Build\aligned.apk")

Write-Host "[6/6] verifying signature..."
Run $Java @("-jar", "$Bt\lib\apksigner.jar", "verify", $Apk)

$SizeKb = [math]::Round((Get-Item $Apk).Length / 1KB)
Write-Host ""
Write-Host "DONE -> $Apk ($SizeKb KB)"
Write-Host "Install: copy to phone and tap it, or run:"
Write-Host "  $Sdk\platform-tools\adb.exe install -r `"$Apk`""
