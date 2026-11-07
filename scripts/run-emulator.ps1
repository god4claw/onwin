#Requires -Version 5.1
<#
.SYNOPSIS
    Поднимает эмулятор, ставит x86_64-сборку приложения и запускает её.

.DESCRIPTION
    На этой машине QEMU периодически падает под нагрузкой ONNX (access violation),
    поэтому запуск вынесен в один скрипт: эмулятор, установка, модель, старт.

    Скрипт:
    1. поднимает AVD (по умолчанию headless; -Window — с окном, но на этой
       машине окно ломается с UpdateLayeredWindowIndirect);
    2. собирает APK, если его нет (x86_64, nonMinifiedRelease);
    3. ставит APK, если пакет не установлен;
    4. заливает LLM-модель, если её нет в каталоге приложения;
    5. открывает приложение.

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
    [string]$Package = "com.example.onwin.profile",
    [string]$Model = "llm\models\qwen2.5-0.5b-instruct-int4"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $root "app\build\outputs\apk\nonMinifiedRelease\app-nonMinifiedRelease.apk"
$modelSource = Join-Path $root $Model
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

if ($Rebuild -or -not (Test-Path $apk)) {
    Write-Host "Собираю x86_64 APK..."
    & (Join-Path $root "gradlew.bat") :app:assembleNonMinifiedRelease "-PonwinProfileX86=true"
    if ($LASTEXITCODE -ne 0) { throw "Сборка не удалась" }
}

$installed = (& $adb shell pm list packages) -match [regex]::Escape($Package)
if (-not $installed) {
    Write-Host "Ставлю $Package..."
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Установка не удалась (проверьте место: adb shell df -h /data)" }
}

$modelDir = "/sdcard/Android/data/$Package/files/llm"
$hasModel = ((& $adb shell ls $modelDir 2>$null) -join " ") -match "genai_config.json"
if (-not $hasModel) {
    if (-not (Test-Path (Join-Path $modelSource "genai_config.json"))) {
        Write-Warning "Модели нет на хосте: выполните llm/scripts/fetch-model.ps1. Приложение запустится без LLM."
    } else {
        Write-Host "Заливаю LLM-модель (~0,9 ГБ)..."
        & $adb shell mkdir -p $modelDir
        & $adb push "$modelSource/." "$modelDir/"
        & $adb shell chmod -R 777 $modelDir
    }
}

& $adb shell am start -n "$Package/com.example.onwin.MainActivity" | Out-Null
Write-Host "Готово: $Package запущен на $Avd."
Write-Host "Скриншот: adb exec-out screencap -p > shot.png"
