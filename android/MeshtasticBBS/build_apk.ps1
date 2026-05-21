#Requires -Version 5.1
param(
    [ValidateSet("client", "server")]
    [string]$Variant = "client",
    [ValidateSet("release", "debug")]
    [string]$BuildType = "release"
)
<#!
.SYNOPSIS
    MeshBBS Android APK 一鍵建置腳本
.DESCRIPTION
    自動下載 Gradle 8.9、設定 JDK、編譯簽章 APK。
    若 ADB 裝置已連接則自動安裝到手機。
#>

$ErrorActionPreference = "Stop"
$projectDir = $PSScriptRoot
$apkPrefix = if ($Variant -eq "server") { "MeshBBS-server" } else { "MeshBBS" }
$variantTaskName = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$buildTaskName = $BuildType.Substring(0, 1).ToUpper() + $BuildType.Substring(1)
$gradleTask = "assemble$variantTaskName$buildTaskName"
$apkOutputDir = "$projectDir\app\build\outputs\apk\$Variant\$BuildType"

function Write-Step  { param($msg) Write-Host "  >> $msg" -ForegroundColor Cyan }
function Write-OK    { param($msg) Write-Host "  ✓  $msg" -ForegroundColor Green }
function Write-Fail  { param($msg) Write-Host "  ✗  $msg" -ForegroundColor Red }
function Write-Info  { param($msg) Write-Host "     $msg" -ForegroundColor DarkGray }
function Read-SimpleProperties {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) {
        return $map
    }
    foreach ($line in Get-Content $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed -split "=", 2
        if ($parts.Count -eq 2) {
            $map[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $map
}

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════╗" -ForegroundColor Magenta
Write-Host "║    MeshBBS Android APK 建置工具                 ║" -ForegroundColor Magenta
Write-Host "╚══════════════════════════════════════════════════╝" -ForegroundColor Magenta
Write-Host ""
Write-Info "目前輸出模式：$Variant"
Write-Info "建置類型：$BuildType"

Write-Step "尋找 Android Studio JDK (JBR)…"
$jbrCandidates = @(
    "C:\Program Files\Android\Android Studio1\jbr",
    "C:\Program Files\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "C:\Program Files\Android Studio\jbr"
)
$javaHome = $null
foreach ($c in $jbrCandidates) {
    if (Test-Path "$c\bin\java.exe") { $javaHome = $c; break }
}
if (-not $javaHome) {
    Write-Fail "找不到 Android Studio JBR。請確認 Android Studio 已安裝。"
    Write-Info "下載：https://developer.android.com/studio"
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:PATH      = "$javaHome\bin;$env:PATH"
$releaseFile   = "$javaHome\release"
$javaVer       = if (Test-Path $releaseFile) {
    (Get-Content $releaseFile | Where-Object { $_ -match "^JAVA_VERSION" } | Select-Object -First 1) -replace '"', ''
} else { "Java found at $javaHome" }
Write-OK "JDK：$javaHome"
Write-Info "$javaVer"

$trustStore = "$env:USERPROFILE\.gradle\meshbbs-truststore.jks"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$signingDir = "$projectDir\signing"
$signingProps = "$signingDir\signing.properties"
$signingConfig = Read-SimpleProperties -Path $signingProps
$storeFileSetting = if ($signingConfig.ContainsKey("storeFile")) { $signingConfig["storeFile"] } else { "signing/meshbbs-release.jks" }
$storeFileRelative = $storeFileSetting -replace "\\", "/"
$releaseKeyStore = if ([System.IO.Path]::IsPathRooted($storeFileSetting)) {
    $storeFileSetting
} else {
    Join-Path $projectDir ($storeFileSetting -replace "/", "\")
}
$releaseStorePass = if ($env:MESHBBS_RELEASE_STOREPASS) {
    $env:MESHBBS_RELEASE_STOREPASS
} elseif ($signingConfig.ContainsKey("storePassword")) {
    $signingConfig["storePassword"]
} else {
    $null
}
$releaseKeyAlias = if ($env:MESHBBS_RELEASE_KEYALIAS) {
    $env:MESHBBS_RELEASE_KEYALIAS
} elseif ($signingConfig.ContainsKey("keyAlias")) {
    $signingConfig["keyAlias"]
} else {
    "meshbbs-release"
}
$releaseKeyPass = if ($env:MESHBBS_RELEASE_KEYPASS) {
    $env:MESHBBS_RELEASE_KEYPASS
} elseif ($signingConfig.ContainsKey("keyPassword")) {
    $signingConfig["keyPassword"]
} elseif ($releaseStorePass) {
    $releaseStorePass
} else {
    $null
}
if ($BuildType -eq "release") {
    if (-not (Test-Path $keytool)) {
        Write-Fail "找不到 keytool，無法建立 release 簽章。"
        exit 1
    }
    if (-not $releaseStorePass -or -not $releaseKeyPass) {
        Write-Fail "缺少 release signing 密碼。請設定環境變數 MESHBBS_RELEASE_STOREPASS / MESHBBS_RELEASE_KEYPASS，或先建立 signing/signing.properties。"
        Write-Info "可參考 signing/signing.properties.example"
        exit 1
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $releaseKeyStore) | Out-Null
    if (-not (Test-Path $releaseKeyStore)) {
        Write-Step "建立 MeshBBS release 簽章 keystore…"
        & $keytool -genkeypair -v `
            -keystore $releaseKeyStore `
            -storepass $releaseStorePass `
            -keypass $releaseKeyPass `
            -alias $releaseKeyAlias `
            -keyalg RSA `
            -keysize 4096 `
            -validity 10000 `
            -dname "CN=MeshBBS, OU=Local Build, O=MeshBBS, L=Taipei, ST=Taiwan, C=TW" | Out-Null
        Write-OK "已建立簽章：$releaseKeyStore"
    } else {
        Write-OK "使用既有簽章：$releaseKeyStore"
    }
    Set-Content -Path $signingProps -Encoding ASCII -Value @(
        "storeFile=$storeFileRelative",
        "storePassword=$releaseStorePass",
        "keyAlias=$releaseKeyAlias",
        "keyPassword=$releaseKeyPass"
    )
}
$avastCert = Get-ChildItem Cert:\CurrentUser\Root,Cert:\LocalMachine\Root -ErrorAction SilentlyContinue |
    Where-Object { $_.Subject -like "*Avast Web/Mail Shield Root*" } |
    Select-Object -First 1
if ($avastCert -and (Test-Path $keytool)) {
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path $trustStore) | Out-Null
        $baseStore = Join-Path $javaHome "lib\security\cacerts"
        if (-not (Test-Path $trustStore) -or ((Get-Item $trustStore).Length -lt 1024)) {
            Copy-Item -LiteralPath $baseStore -Destination $trustStore -Force
        }
        $certFile = Join-Path $env:TEMP "meshbbs-avast-root.cer"
        Export-Certificate -Cert $avastCert -FilePath $certFile -Force | Out-Null
        & $keytool -importcert -noprompt -trustcacerts `
            -alias avast-webmail-shield-root `
            -file $certFile `
            -keystore $trustStore `
            -storepass changeit 2>$null | Out-Null
        $tlsOpts = "-Djavax.net.ssl.trustStore=$trustStore -Djavax.net.ssl.trustStorePassword=changeit -Dcom.sun.net.ssl.checkRevocation=false -Dcom.sun.security.enableCRLDP=false"
        $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $tlsOpts) -join " ").Trim()
        Write-OK "Gradle TLS truststore 已準備：$trustStore"
    } catch {
        Write-Info "Gradle TLS truststore 準備失敗，改用預設 JBR truststore：$_"
    }
} else {
    $tlsOpts = "-Dcom.sun.net.ssl.checkRevocation=false -Dcom.sun.security.enableCRLDP=false"
    $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $tlsOpts) -join " ").Trim()
}

Write-Step "尋找 Android SDK…"
$sdkCandidates = @(
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:USERPROFILE\AppData\Local\Android\Sdk"
)
$androidHome = $null
foreach ($c in $sdkCandidates) {
    if (Test-Path "$c\platform-tools\adb.exe") { $androidHome = $c; break }
}
if (-not $androidHome) {
    Write-Fail "找不到 Android SDK。請先在 Android Studio 的 SDK Manager 下載 SDK。"
    exit 1
}
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:PATH = "$androidHome\platform-tools;$env:PATH"
Write-OK "Android SDK：$androidHome"

Write-Step "準備 Gradle 8.9…"
$gradleVer      = "8.9"
$gradleName     = "gradle-$gradleVer"
$gradleZipName  = "$gradleName-bin.zip"
$gradleUrl      = "https://services.gradle.org/distributions/$gradleZipName"
$gradleCacheDir = "$env:USERPROFILE\.gradle_standalone"
$gradleExtract  = "$gradleCacheDir\$gradleName"
$gradleExe      = "$gradleExtract\bin\gradle.bat"
if (Test-Path $gradleExe) {
    Write-OK "Gradle 已在快取：$gradleExtract"
} else {
    $gradleZip = "$env:TEMP\$gradleZipName"
    if (-not (Test-Path $gradleZip)) {
        Write-Info "下載 Gradle $gradleVer（約 130 MB，請稍等）…"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $wc = New-Object System.Net.WebClient
        $wc.DownloadFile($gradleUrl, $gradleZip)
    }
    New-Item -ItemType Directory -Force -Path $gradleCacheDir | Out-Null
    Expand-Archive -Path $gradleZip -DestinationPath $gradleCacheDir -Force
    Write-OK "Gradle $gradleVer 解壓縮完成"
}

Write-Step "編譯 $BuildType APK（首次需下載相依套件，約 3-10 分鐘）…"
Set-Location $projectDir
$apkArchiveDir = "$projectDir\apk_archive"
New-Item -ItemType Directory -Force -Path $apkArchiveDir | Out-Null
if (Test-Path $apkOutputDir) {
    Get-ChildItem -Path $apkOutputDir -Include "MeshBBS-*.apk","MeshBBS-server-*.apk" -File -ErrorAction SilentlyContinue |
        ForEach-Object {
            $archivePath = Join-Path $apkArchiveDir $_.Name
            if (-not (Test-Path $archivePath)) {
                Copy-Item -Path $_.FullName -Destination $archivePath
            }
        }
}

$logFile = "$projectDir\build_output.log"
Write-Info "完整 log 存於：$logFile"
Write-Host ""
$gradleCmd = "`"$gradleExe`" --project-dir `"$projectDir`" clean $gradleTask --no-daemon --rerun-tasks 2>&1"
cmd /c $gradleCmd | Tee-Object -FilePath $logFile
$exitCode = $LASTEXITCODE
Write-Host ""
if ($exitCode -ne 0) {
    Write-Fail "編譯失敗（exit code $exitCode）"
    Write-Host ""
    Write-Host "── 錯誤摘要（最後 40 行 log）──" -ForegroundColor Yellow
    Get-Content $logFile -ErrorAction SilentlyContinue | Select-Object -Last 40 | ForEach-Object { Write-Host "  $_" }
    exit $exitCode
}

$apkSrc = Get-ChildItem -Path $apkOutputDir -Filter '*.apk' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'app-*.apk' -or $_.Name -eq 'app-debug.apk' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $apkSrc -or -not (Test-Path $apkSrc)) {
    Write-Fail "找不到 APK 輸出檔案（$apkSrc）"
    exit 1
}

$repoFile = "$projectDir\app\src\main\java\com\meshtastic\bbs\data\MeshtasticRepository.kt"
$buildId  = "unknown"
if (Test-Path $repoFile) {
    $match = Select-String -Path $repoFile -Pattern 'BUILD\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($match) { $buildId = $match.Matches[0].Groups[1].Value }
}

$apkName = "$apkPrefix-$buildId.apk"
$apkDir  = Split-Path $apkSrc
$apkPath = "$apkDir\$apkName"
Copy-Item -Path $apkSrc -Destination $apkPath -Force
Copy-Item -Path $apkPath -Destination (Join-Path $apkArchiveDir $apkName) -Force
Get-ChildItem -Path $apkArchiveDir -Include "MeshBBS-*.apk","MeshBBS-server-*.apk" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $apkPath } |
    ForEach-Object {
        Copy-Item -Path $_.FullName -Destination (Join-Path $apkDir $_.Name) -Force
    }

$apkSize = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Write-OK "APK 建置完成！（${apkSize} MB）"
Write-Info "版本：$buildId"
Write-Info "檔案：$apkPath"

Write-Host ""
Write-Step "偵測已連接的 Android 裝置…"
$adb = "$androidHome\platform-tools\adb.exe"
$ErrorActionPreference = "Continue"
$devices = if ($env:MESHBBS_INSTALL_APK -eq "0") { @() } else { & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } }
if ($devices) {
    Write-OK "找到裝置，開始安裝 APK…"
    $installLines = & $adb install -r $apkPath 2>&1
    $installResult = ($installLines | Out-String)
    if ($installResult -match "Success") {
        Write-OK "APK 安裝成功！"
    } else {
        Write-Info "安裝訊息：$installResult"
    }
} else {
    Write-Info "未偵測到 USB 裝置（手機未連接或未開啟 USB 偵錯）"
    Write-Info "APK 已在：$apkPath"
}
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "══════════════════════════════════════════════════════" -ForegroundColor DarkGray
Write-Host "  版本：$buildId" -ForegroundColor Cyan
Write-Host "  APK：$apkPath" -ForegroundColor Yellow
Write-Host "══════════════════════════════════════════════════════" -ForegroundColor DarkGray
Write-Host ""

if ($env:MESHBBS_OPEN_APK_DIR -ne "0") {
    $answer = Read-Host "是否開啟 APK 所在資料夾？[Y/n]"
    if ($answer -ne "n" -and $answer -ne "N") {
        explorer.exe $apkDir
    }
}
