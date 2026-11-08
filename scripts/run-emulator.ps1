#Requires -Version 5.1
<#
.SYNOPSIS
    Поднимает эмулятор, ставит x86_64-сборку приложения и запускает её.

.DESCRIPTION
    На этой машине QEMU периодически падает под нагрузкой ONNX (access violation),
    поэтому запуск вынесен в один скрипт: эмулятор, установка и старт.

    Скрипт:
    1. поднимает AVD (по умолчанию headless; -Window — с окном, но на этой
       машине окно ломается с UpdateLayeredWindowIndirect);
    2. собирает x86_64 APK с LLM, если подходящего APK ещё нет;
    3. устанавливает APK, если пакет не установлен или APK пересобран;
    4. открывает приложение.

.EXAMPLE
    pwsh -ExecutionPolicy Bypass -File scripts/run-emulator.ps1

.EXAMPLE
    pwsh -ExecutionPolicy Bypass -File scripts/run-emulator.ps1 -Window -Avd Pixel_8_API36

.NOTES
    На этой машине политика выполнения блокирует неподписанные скрипты — отсюда
    -ExecutionPolicy Bypass. Окно эмулятора здесь падает с UpdateLayeredWindowIndirect,
    поэтому по умолчанию headless: смотреть экран через `scrcpy` или Android Studio.
#>
param(
    [string]$Avd = "Pixel_6_API34",
    [switch]$Window,
    [switch]$Rebuild,
    [string]$Package = "io.github.god4claw.onwin.profile"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $root "app\build\outputs\apk\nonMinifiedRelease\app-nonMinifiedRelease.apk"
$apkMetadata = Join-Path (Split-Path $apk -Parent) "output-metadata.json"
$log = Join-Path $root "run-artifacts\emulator.log"

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $path = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $path) { return $path }
    throw "adb не найден: добавьте platform-tools в PATH или задайте ANDROID_HOME."
}

function Resolve-Emulator {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $path = Join-Path $sdk "emulator\emulator.exe"
    if (Test-Path $path) { return $path }
    throw "emulator.exe не найден: задайте ANDROID_HOME."
}

$adb = Resolve-Adb

$devices = (& $adb devices) -split "`n" | Where-Object { $_ -match "device$" }
if (-not $devices) {
    Write-Host "Поднимаю $Avd..."
    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
    $arguments = @("-avd", $Avd, "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect", "-memory", "3072")
    if (-not $Window) { $arguments = @("-no-window") + $arguments }
    Start-Process -FilePath (Resolve-Emulator) -ArgumentList $arguments `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden

    & $adb wait-for-device
    for ($i = 0; $i -lt 120; $i++) {
        $boot = (& $adb shell getprop sys.boot_completed 2>$null) -join ""
        if ($boot -match "1") { break }
        Start-Sleep -Seconds 3
    }
    if ($boot -notmatch "1") { throw "Эмулятор не загрузился за 6 минут, лог: $log" }
    Write-Host "Эмулятор загружен."
}

$canReuseApk = $false
if (-not $Rebuild -and (Test-Path $apk) -and (Test-Path $apkMetadata)) {
    $metadata = Get-Content -LiteralPath $apkMetadata -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ceq $Package) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $apkArchive = [IO.Compression.ZipFile]::OpenRead($apk)
        try {
            $canReuseApk = $null -ne $apkArchive.GetEntry("assets/llm/llm-model-v1.zip")
        } finally {
            $apkArchive.Dispose()
        }
    }
}
$buildApk = -not $canReuseApk
if ($buildApk) {
    Write-Host "Собираю x86_64 APK..."
    & (Join-Path $root "gradlew.bat") :app:assembleNonMinifiedRelease "-PonwinProfileX86=true"
    if ($LASTEXITCODE -ne 0) { throw "Сборка не удалась" }
}

$installed = (& $adb shell pm list packages) -match [regex]::Escape($Package)
if ($buildApk -or -not $installed) {
    Write-Host "Ставлю $Package..."
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Установка не удалась (проверьте место: adb shell df -h /data)" }
}

& $adb shell am start -n "$Package/com.example.onwin.MainActivity" | Out-Null
Write-Host "Готово: $Package запущен на $Avd."
Write-Host "Скриншот: adb exec-out screencap -p > shot.png"
