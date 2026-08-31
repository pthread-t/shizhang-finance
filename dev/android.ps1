[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("doctor", "setup", "run", "test", "build-staging", "logs", "stop")]
    [string]$Action = "doctor",

    [ValidateSet("local", "staging")]
    [string]$Backend = "local",

    [switch]$FullMatrix,

    [string]$Version = "0.1.2-rc1",

    [string]$ToolsRoot,

    [string]$StagingServerUrl = "https://staging-ledger.example.com",

    [string]$ProxyUrl = $env:BILL_RECORD_PROXY
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($ToolsRoot)) {
    $ToolsRoot = if ($env:BILL_RECORD_TOOLS_ROOT) {
        $env:BILL_RECORD_TOOLS_ROOT
    } else {
        Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "BillRecordAndroid"
    }
}
if ($StagingServerUrl -notmatch '^https://[A-Za-z0-9.-]+(?::\d+)?(?:/[A-Za-z0-9._~/-]*)?$') {
    throw "-StagingServerUrl must be an HTTPS URL without a query or fragment."
}
$SdkRoot = Join-Path $ToolsRoot "sdk"
$AvdRoot = Join-Path $ToolsRoot "avd"
$AndroidUserRoot = Join-Path $ToolsRoot "user"
$DownloadRoot = Join-Path $ToolsRoot "downloads"
$JdkRoot = Join-Path $ToolsRoot "jdk"
$LocalEnvFile = Join-Path $PSScriptRoot ".env.local"
$DockerProject = "bill-record-local"
$AndroidImage = "ghcr.io/cirruslabs/android-sdk:36"
$GradleCacheVolume = "billrecord_gradle_cache"
$AndroidHomeVolume = "billrecord_android_home"
$MainAvd = "BillRecord_API_36"
$MinAvd = "BillRecord_API_26"
$MainSerial = "emulator-5554"
$MinSerial = "emulator-5556"
$DebugPackage = "com.billrecord.ledger.debug"
$DebugActivity = "$DebugPackage/com.billrecord.ledger.MainActivity"
$DebugExtra = "com.billrecord.ledger.debug.SERVER_URL"
$LocalServerUrl = "http://127.0.0.1:18080"
$StagingSigningRoot = Join-Path ([Environment]::GetFolderPath("UserProfile")) ".billrecord-staging-signing"
$StagingKeystore = Join-Path $StagingSigningRoot "billrecord-staging.p12"
$StagingSigningCredential = Join-Path $StagingSigningRoot "signing-credential.clixml"

$CommandToolsVersion = "15859902"
$CommandToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-$($CommandToolsVersion)_latest.zip"
$CommandToolsSha256 = "90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a"
$JdkUrl = "https://download.visualstudio.microsoft.com/download/pr/f1e5f23f-9d50-4b9f-8ed3-80522ae82bb5/71e8e5f0f13419cc726e470d25e0a0d0/microsoft-jdk-21.0.12.1-windows-x64.zip"
$JdkSha256 = "192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f"
if (-not [string]::IsNullOrWhiteSpace($ProxyUrl)) {
    $env:ALL_PROXY = $ProxyUrl
    $env:HTTP_PROXY = $ProxyUrl
    $env:HTTPS_PROXY = $ProxyUrl
    $env:NO_PROXY = "127.0.0.1,localhost"
}

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-JavaHome {
    if (-not (Test-Path -LiteralPath $JdkRoot)) { return $null }
    $java = Get-ChildItem -LiteralPath $JdkRoot -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\bin\\java\.exe$" } |
        Select-Object -First 1
    if ($null -eq $java) { return $null }
    return (Split-Path -Parent (Split-Path -Parent $java.FullName))
}

function Set-AndroidProcessEnvironment {
    $javaHome = Get-JavaHome
    if ($null -eq $javaHome) { throw "Portable JDK is missing. Run: .\dev\android.ps1 setup" }
    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $SdkRoot
    $env:ANDROID_SDK_ROOT = $SdkRoot
    $env:ANDROID_AVD_HOME = $AvdRoot
    $env:ANDROID_USER_HOME = $AndroidUserRoot
}

function Get-SdkTool([string]$RelativePath) {
    $path = Join-Path $SdkRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) { throw "Android tool is missing: $path. Run setup first." }
    return $path
}

function Invoke-VerifiedDownload {
    param([string]$Uri, [string]$Destination, [string]$ExpectedSha256)
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Step "Downloading $Uri"
        & curl.exe --proxy $PreferredProxy --fail --location --silent --show-error --output $Destination $Uri
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Uri" }
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Destination. Expected $ExpectedSha256, observed $actual."
    }
}

function Install-PortableJdk {
    if ($null -ne (Get-JavaHome)) { return }
    New-Item -ItemType Directory -Path $DownloadRoot, $JdkRoot -Force | Out-Null
    $zip = Join-Path $DownloadRoot "microsoft-jdk-21-windows-x64.zip"
    Write-Step "Downloading the portable Microsoft OpenJDK 21"
    Invoke-VerifiedDownload -Uri $JdkUrl -Destination $zip -ExpectedSha256 $JdkSha256
    Expand-Archive -LiteralPath $zip -DestinationPath $JdkRoot
    if ($null -eq (Get-JavaHome)) { throw "Portable JDK extraction did not produce bin\java.exe." }
}

function Install-CommandTools {
    $sdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (Test-Path -LiteralPath $sdkManager) { return }
    New-Item -ItemType Directory -Path $DownloadRoot, (Join-Path $SdkRoot "cmdline-tools") -Force | Out-Null
    $zip = Join-Path $DownloadRoot "commandlinetools-win-$CommandToolsVersion.zip"
    Invoke-VerifiedDownload -Uri $CommandToolsUrl -Destination $zip -ExpectedSha256 $CommandToolsSha256
    $staging = Join-Path $ToolsRoot "commandline-tools-$CommandToolsVersion"
    if (Test-Path -LiteralPath $staging) { throw "Incomplete command-tools staging directory exists: $staging" }
    Expand-Archive -LiteralPath $zip -DestinationPath $staging
    $source = (Resolve-Path (Join-Path $staging "cmdline-tools")).Path
    $destination = Join-Path $SdkRoot "cmdline-tools\latest"
    if (Test-Path -LiteralPath $destination) { throw "Unexpected command-tools destination exists: $destination" }
    Move-Item -LiteralPath $source -Destination $destination
}

function Install-SdkPackages([int]$ApiLevel) {
    Set-AndroidProcessEnvironment
    $sdkManager = Get-SdkTool "cmdline-tools\latest\bin\sdkmanager.bat"
    Write-Step "Accepting Android SDK licenses"
    ((1..100) | ForEach-Object { "y" }) | & $sdkManager --licenses | Out-Host
    $packages = @("platform-tools", "emulator", "system-images;android-$ApiLevel;google_apis;x86_64")
    if ($ApiLevel -eq 36) { $packages += @("platforms;android-36", "build-tools;36.0.0") }
    Write-Step "Installing Android SDK packages for API $ApiLevel"
    & $sdkManager @packages
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
}

function Ensure-Avd([string]$Name, [int]$ApiLevel) {
    Set-AndroidProcessEnvironment
    New-Item -ItemType Directory -Path $AvdRoot, $AndroidUserRoot -Force | Out-Null
    $config = Join-Path $AvdRoot "$Name.avd\config.ini"
    if (Test-Path -LiteralPath $config) { return }
    $avdManager = Get-SdkTool "cmdline-tools\latest\bin\avdmanager.bat"
    Write-Step "Creating AVD $Name"
    "no" | & $avdManager create avd --name $Name --package "system-images;android-$ApiLevel;google_apis;x86_64" --device "pixel_6"
    if ($LASTEXITCODE -ne 0) { throw "avdmanager failed with exit code $LASTEXITCODE" }
}

function Assert-Docker {
    $recoveryScript = Join-Path $PSScriptRoot "docker-recover.ps1"
    if (-not (Test-Path -LiteralPath $recoveryScript)) { throw "Docker recovery helper is missing: $recoveryScript" }
    & $recoveryScript
}

function New-LocalEnvironmentFile {
    if (Test-Path -LiteralPath $LocalEnvFile) { return }
    $random = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $secretA = [Convert]::ToHexString($random).ToLowerInvariant()
    $random = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $secretB = [Convert]::ToHexString($random).ToLowerInvariant()
    $random = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $secretC = [Convert]::ToHexString($random).ToLowerInvariant()
    @"
APP_DOMAIN=http://localhost
HTTP_PORT=127.0.0.1:18080
HTTPS_PORT=127.0.0.1:18480
POSTGRES_DB=bill_record
POSTGRES_USER=bill_record
POSTGRES_PASSWORD=$secretA
JWT_SECRET=$secretB
BACKUP_PASSWORD=$secretC
BACKUP_INTERVAL_SECONDS=86400
TZ=Asia/Shanghai
"@ | Set-Content -LiteralPath $LocalEnvFile -Encoding utf8NoBOM
}

function Invoke-Compose([string[]]$Arguments) {
    & docker compose --project-name $DockerProject --env-file $LocalEnvFile `
        -f (Join-Path $ProjectRoot "docker-compose.yml") `
        -f (Join-Path $PSScriptRoot "docker-compose.local.yaml") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code $LASTEXITCODE" }
}

function Start-LocalBackend {
    Assert-Docker
    New-LocalEnvironmentFile
    Write-Step "Starting the isolated local backend"
    Invoke-Compose @("up", "-d", "--build")
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        try {
            $health = Invoke-WebRequest -Uri "http://127.0.0.1:18080/health" -TimeoutSec 3 -NoProxy
            if ($health.StatusCode -eq 200 -and $health.Content -match '"status"\s*:\s*"ok"') { return }
        } catch { Start-Sleep -Seconds 2 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Local backend did not become healthy at http://127.0.0.1:18080/health"
}

function Invoke-AndroidBuild([string[]]$Tasks) {
    Assert-Docker
    & docker volume inspect $GradleCacheVolume *> $null
    if ($LASTEXITCODE -ne 0) { & docker volume create $GradleCacheVolume | Out-Host }
    & docker volume inspect $AndroidHomeVolume *> $null
    if ($LASTEXITCODE -ne 0) { & docker volume create $AndroidHomeVolume | Out-Host }
    $taskText = ($Tasks -join " ")
    Write-Step "Running Gradle in the Android SDK container: $taskText"
    $dockerArguments = @("run", "--rm", "--volume", "${ProjectRoot}:/workspace", "--volume", "${GradleCacheVolume}:/root/.gradle", "--volume", "${AndroidHomeVolume}:/root/.android", "--workdir", "/workspace")
    $gradleArguments = "./gradlew $taskText --no-daemon"
    if ($Tasks -contains ":app:assembleStaging") {
        Set-StagingSigningEnvironment
        $dockerArguments += @(
            "--volume", "${StagingKeystore}:/signing/billrecord-staging.p12:ro",
            "--env", "ANDROID_STAGING_KEYSTORE_PATH=/signing/billrecord-staging.p12",
            "--env", "ANDROID_STAGING_KEYSTORE_PASSWORD",
            "--env", "ANDROID_STAGING_KEY_ALIAS",
            "--env", "ANDROID_STAGING_KEY_PASSWORD"
        )
    } else {
        # Managed AVDs are x86_64; emulator builds do not need phone ARM libraries.
        $gradleArguments += " -PbillRecord.developmentAbi=x86_64"
    }
    if ($Tasks -contains ":app:assembleStaging") {
        $gradleArguments += " -PbillRecord.stagingServerUrl=$StagingServerUrl"
    }
    $dockerArguments += @($AndroidImage, "bash", "-lc", $gradleArguments)
    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Android container build failed with exit code $LASTEXITCODE" }
}

function New-RandomSigningPassword {
    $alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Ensure-StagingSigningKey {
    if ((Test-Path -LiteralPath $StagingKeystore) -and (Test-Path -LiteralPath $StagingSigningCredential)) { return }
    Set-AndroidProcessEnvironment
    New-Item -ItemType Directory -Path $StagingSigningRoot -Force | Out-Null
    $password = New-RandomSigningPassword
    $keytool = Join-Path (Get-JavaHome) "bin\keytool.exe"
    & $keytool -genkeypair -alias billrecord-staging -keyalg RSA -keysize 3072 -validity 3650 -storetype PKCS12 `
        -keystore $StagingKeystore -storepass $password -keypass $password `
        -dname "CN=Bill Record Staging, OU=Pre-release, O=Bill Record, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the staging signing key." }
    [PSCredential]::new("billrecord-staging", (ConvertTo-SecureString $password -AsPlainText -Force)) |
        Export-Clixml -LiteralPath $StagingSigningCredential -Force
}

function Set-StagingSigningEnvironment {
    Ensure-StagingSigningKey
    $credential = Import-Clixml -LiteralPath $StagingSigningCredential
    $password = $credential.GetNetworkCredential().Password
    $env:ANDROID_STAGING_KEYSTORE_PASSWORD = $password
    $env:ANDROID_STAGING_KEY_ALIAS = $credential.UserName
    $env:ANDROID_STAGING_KEY_PASSWORD = $password
}

function Export-StagingApk {
    if ($Version -ne "0.1.2-rc1") { throw "This source tree declares staging version 0.1.2-rc1; requested $Version." }
    $source = Join-Path $ProjectRoot "app\build\outputs\apk\staging\app-staging.apk"
    if (-not (Test-Path -LiteralPath $source)) { throw "Staging APK was not produced: $source" }
    $dist = Join-Path $ProjectRoot "dist"
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $destination = Join-Path $dist "bill-record-staging-$Version.apk"
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $(Split-Path -Leaf $destination)" | Set-Content -LiteralPath "$destination.sha256" -Encoding ascii
    Write-Host "Staging APK: $destination" -ForegroundColor Green
    Write-Host "SHA-256: $hash"
}

function Start-Avd([string]$Name, [string]$Serial, [int]$Port, [switch]$Headless) {
    Set-AndroidProcessEnvironment
    $adb = Get-SdkTool "platform-tools\adb.exe"
    $emulator = Get-SdkTool "emulator\emulator.exe"
    $known = & $adb devices | Select-String "^$([regex]::Escape($Serial))\s+device$"
    if ($null -eq $known) {
        Write-Step "Starting Android emulator $Name"
        $arguments = if ($Headless) {
            @("-avd", $Name, "-port", $Port, "-no-window", "-no-snapshot", "-gpu", "swiftshader_indirect")
        } else {
            @("-avd", $Name, "-port", $Port, "-no-snapshot-save", "-gpu", "swiftshader_indirect")
        }
        Start-Process -FilePath $emulator -ArgumentList $arguments | Out-Null
    }
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        Start-Sleep -Seconds 2
        $boot = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -join ""
        if ($boot.Trim() -eq "1") { return }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Emulator $Serial did not finish booting."
}

function Install-And-Launch([string]$ServerUrl, [string]$Serial = $MainSerial) {
    Set-AndroidProcessEnvironment
    $adb = Get-SdkTool "platform-tools\adb.exe"
    $apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK was not produced: $apk" }
    Write-Step "Installing the debug APK while preserving app data"
    & $adb -s $Serial install -r $apk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb install failed." }
    if ($ServerUrl -eq $LocalServerUrl) { & $adb -s $Serial reverse tcp:18080 tcp:18080 | Out-Host }
    & $adb -s $Serial shell am start -n $DebugActivity --es $DebugExtra $ServerUrl | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Unable to launch the debug app." }
}

function Invoke-DeviceTests([string]$AvdName, [string]$Serial, [int]$Port) {
    Start-Avd -Name $AvdName -Serial $Serial -Port $Port -Headless
    $adb = Get-SdkTool "platform-tools\adb.exe"
    $mainApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    & $adb -s $Serial install -r $mainApk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Unable to install main test APK on $Serial" }
    & $adb -s $Serial install -r $testApk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Unable to install instrumentation APK on $Serial" }
    $instrumentationOutput = (& $adb -s $Serial shell am instrument -w "$DebugPackage.test/androidx.test.runner.AndroidJUnitRunner" 2>&1) -join "`n"
    $instrumentationOutput | Out-Host
    if ($LASTEXITCODE -ne 0 -or $instrumentationOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Instrumentation tests failed on $Serial"
    }
}

function Invoke-Doctor {
    Write-Step "Host isolation"
    Write-Host "Tools root: $ToolsRoot"
    Write-Host "Global JAVA_HOME: $env:JAVA_HOME (not modified by this script)"
    Write-Host "Global ANDROID_HOME: $env:ANDROID_HOME (not modified by this script)"
    Write-Step "Docker"
    try { Assert-Docker; Write-Host "Docker Desktop: ready" -ForegroundColor Green } catch { Write-Warning $_ }
    Write-Step "Android tools"
    $javaHome = Get-JavaHome
    Write-Host "Portable JDK: $(if ($javaHome) { $javaHome } else { 'missing' })"
    $emulator = Join-Path $SdkRoot "emulator\emulator.exe"
    if (Test-Path -LiteralPath $emulator) {
        Set-AndroidProcessEnvironment
        & $emulator -accel-check | Out-Host
    } else { Write-Host "Android Emulator: missing" }
    Write-Step "Remote staging"
    try {
        $dns = Resolve-DnsName ([Uri]$StagingServerUrl).Host -Type A -ErrorAction Stop | Where-Object IPAddress
        Write-Host "DNS: $($dns.IPAddress -join ', ')"
    } catch { Write-Host "DNS: not configured yet (expected until you add it in Cloudflare)" }
}

switch ($Action) {
    "doctor" { Invoke-Doctor }
    "setup" {
        New-Item -ItemType Directory -Path $ToolsRoot, $SdkRoot, $AvdRoot, $AndroidUserRoot, $DownloadRoot -Force | Out-Null
        Install-PortableJdk
        Install-CommandTools
        Install-SdkPackages -ApiLevel 36
        Ensure-Avd -Name $MainAvd -ApiLevel 36
        Invoke-Doctor
    }
    "run" {
        Set-AndroidProcessEnvironment
        Ensure-Avd -Name $MainAvd -ApiLevel 36
        if ($Backend -eq "local") { Start-LocalBackend; $serverUrl = $LocalServerUrl } else { $serverUrl = $StagingServerUrl }
        Invoke-AndroidBuild @(':app:assembleDebug')
        Start-Avd -Name $MainAvd -Serial $MainSerial -Port 5554
        Install-And-Launch -ServerUrl $serverUrl
    }
    "test" {
        Set-AndroidProcessEnvironment
        Ensure-Avd -Name $MainAvd -ApiLevel 36
        Invoke-AndroidBuild @(':shared:test', ':server:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
        Invoke-DeviceTests -AvdName $MainAvd -Serial $MainSerial -Port 5554
        if ($FullMatrix) {
            Install-SdkPackages -ApiLevel 26
            Ensure-Avd -Name $MinAvd -ApiLevel 26
            Invoke-DeviceTests -AvdName $MinAvd -Serial $MinSerial -Port 5556
            $adb = Get-SdkTool "platform-tools\adb.exe"
            & $adb -s $MinSerial emu kill 2>$null
        }
    }
    "build-staging" {
        Set-AndroidProcessEnvironment
        Invoke-AndroidBuild @(':app:assembleStaging')
        Export-StagingApk
    }
    "logs" {
        Set-AndroidProcessEnvironment
        $adb = Get-SdkTool "platform-tools\adb.exe"
        $appPid = (& $adb -s $MainSerial shell pidof $DebugPackage).Trim()
        if ([string]::IsNullOrWhiteSpace($appPid)) { throw "$DebugPackage is not running on $MainSerial" }
        & $adb -s $MainSerial logcat --pid $appPid
    }
    "stop" {
        if (Test-Path -LiteralPath (Join-Path $SdkRoot "platform-tools\adb.exe")) {
            Set-AndroidProcessEnvironment
            $adb = Get-SdkTool "platform-tools\adb.exe"
            & $adb -s $MainSerial emu kill 2>$null
            & $adb -s $MinSerial emu kill 2>$null
        }
        try { Assert-Docker; if (Test-Path -LiteralPath $LocalEnvFile) { Invoke-Compose @("stop") } } catch { Write-Warning $_ }
    }
}
