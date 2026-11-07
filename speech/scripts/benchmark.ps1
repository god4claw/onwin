#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Wav,

    [ValidateSet("batch", "streaming", "both", "vad")]
    [string]$Mode = "batch",

    [string]$Speakers = "auto",

    [ValidateRange(1, 16)]
    [int]$Threads = 4,

    [ValidateRange(1, 20)]
    [int]$Runs = 3,

    [ValidateRange(10, 1000)]
    [int]$ChunkMs = 100,

    [ValidateRange(0.0, 1.0)]
    [double]$AutoThreshold = 0.80,

    [ValidateRange(0.0, 1.0)]
    [double]$OnlineThreshold = 0.45,

    [ValidateRange(0, 60000)]
    [int]$PruneMinMs = 3000,

    [ValidateRange(0.0, 1.0)]
    [double]$PruneMinFraction = 0.05,

    [ValidateRange(0.0, 1.0)]
    [double]$SpeakerChangeThreshold = 0.65,

    # Скорость подачи в streaming: 0 — без пауз, 1 — реальное время (задержки).
    [ValidateRange(0.0, 100.0)]
    [double]$Pace = 0.0,

    # VAD: -1 — не передавать, тест возьмёт VadConfig по умолчанию.
    [ValidateRange(-1.0, 1.0)]
    [double]$VadThreshold = -1.0,

    [ValidateRange(-1, 600000)]
    [int]$VadMinSilenceMs = -1,

    [ValidateRange(-1, 600000)]
    [int]$VadMinSpeechMs = -1,

    [ValidateRange(-1, 600000)]
    [int]$VadMaxSpeechMs = -1,

    # АРУ перед VAD в streaming: пусто — не передавать (AgcConfig по умолчанию).
    [ValidateSet("", "on", "off")]
    [string]$Agc = "",

    # NaN — не передавать.
    [double]$AgcTargetDb = [double]::NaN,

    [double]$AgcMaxGainDb = [double]::NaN,

    # Писать embedding каждой streaming-реплики в JSON (подбор кластеризации на хосте).
    [switch]$DumpEmbeddings,

    # Порог финальной перекластеризации streaming; NaN — не передавать (SpeechConfig).
    [double]$ReclusterThreshold = [double]::NaN,

    # Модель speaker embedding: пусто — CAM++ (SpeechConfig по умолчанию).
    [ValidateSet("", "camplus", "eres2net")]
    [string]$EmbeddingModel = "",

    # Модель ASR: пусто — как в SpeechConfig (e2e_rnnt).
    [ValidateSet("", "e2e_ctc", "e2e_rnnt")]
    [string]$AsrModel = "",

    # Поиск смены говорящего внутри VAD-чанка, мс; -1 — не передавать (SpeechConfig).
    [int]$SpeakerChangeWindowMs = -1,
    [int]$SpeakerChangeHopMs = -1,
    [int]$SpeakerChangeMinMs = -1,

    [string]$Serial = "",

    [string]$OutDir = "bench-results",

    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$wavPath = [System.IO.Path]::GetFullPath($Wav)
if (-not (Test-Path $wavPath -PathType Leaf)) {
    throw "WAV not found: $wavPath"
}
if ((Get-Item $wavPath).Length -le 44) {
    throw "WAV is too short: $wavPath"
}

$adbCandidates = @()
if ($env:ANDROID_HOME) {
    $adbCandidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
}
if ($env:ANDROID_SDK_ROOT) {
    $adbCandidates += Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
}
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) {
    $adbCandidates += $adbCommand.Source
}

$localProperties = Join-Path $repoRoot "local.properties"
if (Test-Path $localProperties -PathType Leaf) {
    $sdkLine = Get-Content $localProperties |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if ($sdkLine) {
        $sdkDir = ($sdkLine -split '=', 2)[1].Trim()
        $sdkDir = $sdkDir.Replace('\:', ':').Replace('\\', '\')
        $adbCandidates += Join-Path $sdkDir "platform-tools\adb.exe"
    }
}
if ($env:LOCALAPPDATA) {
    $adbCandidates += Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}

$adb = $adbCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adb) {
    throw "adb not found. Set ANDROID_HOME or add platform-tools to PATH."
}

$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $adb @adbArgs @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($code -ne 0) {
        throw "adb $($Arguments -join ' ') failed with exit code $code`n$($output -join "`n")"
    }
    return $output
}

if (-not $NoBuild) {
    Write-Host "Building :speech androidTest APK..."
    Push-Location $repoRoot
    try {
        & .\gradlew.bat --no-daemon :speech:verifyBundledModels :speech:installDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed."
        }
    } finally {
        Pop-Location
    }
}

$package = "com.example.speech.test"

# Файлы кладём в internal storage приложения, а не в /sdcard/Android/data/<pkg>.
# На Android 11+ последний путь — FUSE-представление scoped storage: adb push создаёт
# там файл от shell, и приложение его через File() не видит ("WAV не найден", хотя
# файл физически на месте). В internal storage файл копируется через run-as, то есть
# сразу с uid приложения.
$remoteStaging = "/data/local/tmp/bench-input.wav"
$remoteDir = "/data/user/0/$package/files/bench"
$remoteResults = "$remoteDir/results"
$remoteWav = "$remoteDir/input.wav"
$remoteJson = "$remoteResults/result.json"
$remoteTxt = "$remoteResults/result.txt"

Write-Host "Preparing device directory..."
Invoke-Adb @("shell", "run-as", $package, "mkdir", "-p", $remoteDir) | Out-Null
Invoke-Adb @("shell", "run-as", $package, "mkdir", "-p", $remoteResults) | Out-Null
# Иначе при редком сбое instrumentation можно вытащить result.json предыдущего прогона.
Invoke-Adb @("shell", "run-as", $package, "rm", "-f", $remoteJson) | Out-Null
Invoke-Adb @("shell", "run-as", $package, "rm", "-f", $remoteTxt) | Out-Null

Write-Host "Pushing $wavPath ..."
Invoke-Adb @("push", $wavPath, $remoteStaging) | ForEach-Object { Write-Host $_ }

# run-as требует debuggable-приложение — androidTest-APK таким и ставится.
try {
    Invoke-Adb @("shell", "run-as", $package, "cp", $remoteStaging, $remoteWav) | Out-Null
} catch {
    throw (
        "Не удалось скопировать WAV в internal storage через run-as.`n" +
        "run-as работает только для debuggable-сборок: переустановите APK без -NoBuild " +
        "или разрешите adb root на устройстве.`n$($_.Exception.Message)"
    )
}
Invoke-Adb @("shell", "rm", "-f", $remoteStaging) | Out-Null

$label = [System.IO.Path]::GetFileNameWithoutExtension($wavPath)

# adb/instrumentation не любит локаль с запятой вместо точки в дробных числах.
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$autoThresholdText = $AutoThreshold.ToString($invariant)
$onlineThresholdText = $OnlineThreshold.ToString($invariant)
$pruneFractionText = $PruneMinFraction.ToString($invariant)
$speakerChangeText = $SpeakerChangeThreshold.ToString($invariant)
$paceText = $Pace.ToString($invariant)

$optionalArgs = @()
if ($VadThreshold -ge 0) { $optionalArgs += @("-e", "vad_threshold", $VadThreshold.ToString($invariant)) }
if ($VadMinSilenceMs -ge 0) { $optionalArgs += @("-e", "vad_min_silence_ms", "$VadMinSilenceMs") }
if ($VadMinSpeechMs -ge 0) { $optionalArgs += @("-e", "vad_min_speech_ms", "$VadMinSpeechMs") }
if ($VadMaxSpeechMs -ge 0) { $optionalArgs += @("-e", "vad_max_speech_ms", "$VadMaxSpeechMs") }
if ($Agc) { $optionalArgs += @("-e", "agc", $Agc) }
if (-not [double]::IsNaN($AgcTargetDb)) { $optionalArgs += @("-e", "agc_target_db", $AgcTargetDb.ToString($invariant)) }
if (-not [double]::IsNaN($AgcMaxGainDb)) { $optionalArgs += @("-e", "agc_max_gain_db", $AgcMaxGainDb.ToString($invariant)) }
if ($DumpEmbeddings) { $optionalArgs += @("-e", "dump_embeddings", "on") }
if ($EmbeddingModel) { $optionalArgs += @("-e", "embedding_model", $EmbeddingModel) }
if ($AsrModel) { $optionalArgs += @("-e", "asr_model", $AsrModel) }
if ($SpeakerChangeWindowMs -gt 0) { $optionalArgs += @("-e", "speaker_change_window_ms", "$SpeakerChangeWindowMs") }
if ($SpeakerChangeHopMs -gt 0) { $optionalArgs += @("-e", "speaker_change_hop_ms", "$SpeakerChangeHopMs") }
if ($SpeakerChangeMinMs -ge 0) { $optionalArgs += @("-e", "speaker_change_min_ms", "$SpeakerChangeMinMs") }
if (-not [double]::IsNaN($ReclusterThreshold)) {
    if ($ReclusterThreshold -lt 0 -or $ReclusterThreshold -gt 1) { throw "ReclusterThreshold must be in 0..1" }
    $optionalArgs += @("-e", "recluster_threshold", $ReclusterThreshold.ToString($invariant))
}

# Путь к результату обязан быть путём на устройстве: хост-путь (C:\...) даёт EROFS.
Write-Host (
    "Running benchmark (mode=$Mode, runs=$Runs, threads=$Threads, " +
    "auto=$autoThresholdText, online=$onlineThresholdText, " +
    "prune=$PruneMinMs/$pruneFractionText, change=$speakerChangeText, pace=$paceText)..."
)
$instrumentArgs = @(
    "shell", "am", "instrument", "-w", "-r",
    "-e", "class", "com.example.speech.bench.SpeechBenchmarkTest",
    "-e", "wav", $remoteWav,
    "-e", "label", $label,
    "-e", "mode", $Mode,
    "-e", "speakers", $Speakers,
    "-e", "threads", "$Threads",
    "-e", "runs", "$Runs",
    "-e", "chunk_ms", "$ChunkMs",
    "-e", "auto_threshold", $autoThresholdText,
    "-e", "online_threshold", $onlineThresholdText,
    "-e", "prune_min_ms", "$PruneMinMs",
    "-e", "prune_min_fraction", $pruneFractionText,
    "-e", "speaker_change_threshold", $speakerChangeText,
    "-e", "pace", $paceText
) + $optionalArgs + @(
    "-e", "out", $remoteJson,
    "$package/androidx.test.runner.AndroidJUnitRunner"
)
$instrumentOutput = Invoke-Adb $instrumentArgs
$instrumentText = $instrumentOutput -join "`n"

$instrumentOutput |
    Where-Object { $_ -match "INSTRUMENTATION_|OK \(|FAILURES|Tests run" } |
    ForEach-Object { Write-Host $_ }

if ($instrumentText -match "FAILURES!!!" -or
    $instrumentText -match "Tests run:.*Failures: [1-9]" -or
    $instrumentText -match "INSTRUMENTATION_STATUS_CODE: -2" -or
    # Нативный SIGSEGV убивает процесс: FAILURES не пишется, а am instrument
    # всё равно завершается с кодом 0.
    $instrumentText -match "INSTRUMENTATION_RESULT: shortMsg=") {
    throw "Instrumentation failed.`n$instrumentText"
}

$outPath = if ([System.IO.Path]::IsPathRooted($OutDir)) {
    [System.IO.Path]::GetFullPath($OutDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $OutDir))
}
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$base = [System.IO.Path]::GetFileNameWithoutExtension($wavPath)
$localJson = Join-Path $outPath "$base-$stamp.json"
$localTxt = Join-Path $outPath "$base-$stamp.txt"

# adb pull не читает internal storage чужого приложения: забираем через run-as + exec-out.
# exec-out отдаёт байты как есть (без CRLF-переводов, в отличие от shell).
Write-Host "Pulling results..."
function Get-DeviceFile {
    param([string]$RemotePath, [string]$LocalPath)

    # Перехватываем сырые байты: конвейер PowerShell иначе перекодирует их в строки
    # в OEM-кодировке консоли и ломает UTF-8 в русском тексте.
    # Script требует -Version 5.1, поэтому здесь ProcessStartInfo + аргументы строкой
    # (ArgumentList появился только в PowerShell 7).
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    # adb exec-out не передаёт код возврата удалённой команды: сначала проверяем,
    # что файл есть, иначе текст «No such file» молча попал бы в локальный .json.
    try {
        Invoke-Adb @("shell", "run-as", $package, "test", "-f", $RemotePath) | Out-Null
    } catch {
        throw "Результат $RemotePath не найден на устройстве: бенчмарк не завершился."
    }
    $arguments = @($adbArgs) + @("exec-out", "run-as", $package, "cat", $RemotePath)
    $psi.Arguments = ($arguments | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join " "

    $proc = [System.Diagnostics.Process]::Start($psi)
    $buffer = New-Object System.IO.MemoryStream
    $proc.StandardOutput.BaseStream.CopyTo($buffer)
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        throw "Не удалось прочитать $RemotePath на устройстве: $stderr"
    }

    [System.IO.File]::WriteAllBytes($LocalPath, $buffer.ToArray())
    Write-Host "pulled $RemotePath -> $LocalPath"
}

Get-DeviceFile -RemotePath $remoteJson -LocalPath $localJson
Get-DeviceFile -RemotePath $remoteTxt -LocalPath $localTxt

Invoke-Adb @("shell", "run-as", $package, "rm", "-f", $remoteWav) | Out-Null

Write-Host ""
Get-Content $localTxt -Encoding UTF8 | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host "JSON: $localJson"
Write-Host "TXT:  $localTxt"
