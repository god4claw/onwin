#Requires -Version 5.1
<#
.SYNOPSIS
    Заливает скачанную LLM-модель на подключённое устройство.

.DESCRIPTION
    Модель не входит в APK: она копируется во внешний каталог приложения
    (/sdcard/Android/data/<package>/files/llm), который приложение читает без
    дополнительных разрешений. Каталог выбирается в OnDeviceCorrector.

    Перед запуском: llm/scripts/fetch-model.ps1.

.EXAMPLE
    pwsh -File llm/scripts/push-model.ps1

.EXAMPLE
    pwsh -File llm/scripts/push-model.ps1 -Serial emulator-5554 -Package com.example.llm
#>
param(
    [string]$Package = "io.github.god4claw.onwin",
    [string]$Source = (Join-Path $PSScriptRoot "..\models\qwen2.5-0.5b-instruct-int4"),
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw "adb не найден: добавьте platform-tools в PATH или задайте ANDROID_HOME."
}

if (-not (Test-Path (Join-Path $Source "genai_config.json"))) {
    throw "Нет модели в $Source. Сначала запустите llm/scripts/fetch-model.ps1."
}

$adb = Resolve-Adb
$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

$destination = "/sdcard/Android/data/$Package/files/llm/"
Write-Host "adb push $Source -> $destination"
& $adb @adbArgs shell mkdir -p "/sdcard/Android/data/$Package/files/llm"
& $adb @adbArgs push "$Source/." $destination
if ($LASTEXITCODE -ne 0) { throw "adb push завершился с кодом $LASTEXITCODE" }

& $adb @adbArgs shell ls -l "/sdcard/Android/data/$Package/files/llm"
Write-Host "Готово. Приложение подхватит модель при следующей обработке (перезапуск процесса не нужен)."
