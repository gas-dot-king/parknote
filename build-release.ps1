# 아맞다주차! Google Play용 서명 AAB 빌드 스크립트
# 사전 조건: keystore.properties와 업로드 키를 저장소 밖에 준비해야 합니다.

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Properties = Join-Path $Root 'keystore.properties'
$Gradle = Join-Path $Root 'gradlew.bat'

if (-not (Test-Path $Properties)) {
    throw "keystore.properties가 없습니다. keystore.properties.example을 복사해 업로드 키 정보를 설정하세요."
}
if (-not (Test-Path $Gradle)) {
    throw "gradlew.bat가 없습니다. Gradle wrapper를 먼저 준비하세요."
}

& $Gradle --no-daemon clean bundleRelease
if ($LASTEXITCODE -ne 0) { throw "bundleRelease가 실패했습니다 ($LASTEXITCODE)." }

$Aab = Join-Path $Root 'app\build\outputs\bundle\release\app-release.aab'
if (-not (Test-Path $Aab)) { throw "AAB를 찾을 수 없습니다: $Aab" }
$Dist = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force $Dist | Out-Null
$Output = Join-Path $Dist 'AMatdaParking-release.aab'
Copy-Item -LiteralPath $Aab -Destination $Output -Force

$JavaHome = $env:JAVA_HOME
if (-not $JavaHome) { $JavaHome = "$env:LOCALAPPDATA\Android\jdk21" }
$Jarsigner = Join-Path $JavaHome 'bin\jarsigner.exe'
if (-not (Test-Path $Jarsigner)) { $Jarsigner = 'jarsigner' }
& $Jarsigner -verify -verbose -certs $Output
if ($LASTEXITCODE -ne 0) { throw "AAB 서명 검증이 실패했습니다 ($LASTEXITCODE)." }

Get-FileHash -LiteralPath $Output -Algorithm SHA256
Write-Host ""
Write-Host "PLAY AAB -> $Output"
