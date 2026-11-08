#Requires -Version 5.1
<#
.SYNOPSIS
    Скачивает LLM-модель (Qwen2.5-0.5B-Instruct, ONNX GenAI, int4) в llm/models.

.DESCRIPTION
    Модель весит ~0,9 ГБ и в git не входит. Скрипт качает её с Hugging Face,
    сверяет SHA-256 каждого файла с llm/models.sha256 и раскладывает по
    каталогу, из которого её потом забирает приложение
    (llm/scripts/push-model.ps1).

    Повторный запуск докачивает только отсутствующие или битые файлы.

.EXAMPLE
    pwsh -File llm/scripts/fetch-model.ps1
#>
param(
    [string]$Destination = (Join-Path $PSScriptRoot "..\models\qwen2.5-0.5b-instruct-int4"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$BaseUrl = "https://huggingface.co/xiaoyao9184/Qwen2.5-0.5B-Instruct-onnx-genai/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4"

# Тот же список, что в llm/models.sha256; хеши — SHA-256 содержимого.
$Files = @(
    @{ Name = "genai_config.json"; Hash = "91451609de365f1b383aae19c6bd935e7b0778c610c83e9bfadcd647f3c3e573" },
    @{ Name = "model.onnx"; Hash = "ce313fa11d3d5b12a752f27531d745514fab253f5ff8690889717d73d9599382" },
    @{ Name = "model.onnx.data"; Hash = "19a2860d7b120cf2d5c5d91c516ecbbbb9d2f7ce2ddbee75bf4c4c6d1ad26e02" },
    @{ Name = "tokenizer.json"; Hash = "9c5ae00e602b8860cbd784ba82a8aa14e8feecec692e7076590d014d7b7fdafa" },
    @{ Name = "tokenizer_config.json"; Hash = "0a04a9d7d4a62b28482bdfe726c122756de85714fb64166ace92ae75b8f57614" },
    @{ Name = "added_tokens.json"; Hash = "58b54bbe36fc752f79a24a271ef66a0a0830054b4dfad94bde757d851968060b" },
    @{ Name = "special_tokens_map.json"; Hash = "76862e765266b85aa9459767e33cbaf13970f327a0e88d1c65846c2ddd3a1ecd" },
    @{ Name = "chat_template.jinja"; Hash = "cd8e9439f0570856fd70470bf8889ebd8b5d1107207f67a5efb46e342330527f" },
    @{ Name = "merges.txt"; Hash = "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5" },
    @{ Name = "vocab.json"; Hash = "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910" }
)

function Get-Sha256([string]$Path) {
    (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Save-RemoteFile([string]$Url, [string]$Path) {
    $part = "$Path.part"
    Remove-Item $part -ErrorAction SilentlyContinue
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -L --fail --retry 3 --retry-delay 2 -o $part $Url
        if ($LASTEXITCODE -ne 0) { throw "curl завершился с кодом $LASTEXITCODE: $Url" }
    } else {
        Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing
    }
    Move-Item -Force $part $Path
}

New-Item -ItemType Directory -Force -Path $Destination | Out-Null

$downloaded = 0
$skipped = 0
foreach ($file in $Files) {
    $target = Join-Path $Destination $file.Name
    if (-not $Force -and (Test-Path $target) -and (Get-Sha256 $target) -eq $file.Hash) {
        $skipped++
        continue
    }

    Write-Host "Скачиваю $($file.Name)..."
    Save-RemoteFile "$BaseUrl/$($file.Name)" $target

    $actual = Get-Sha256 $target
    if ($actual -ne $file.Hash) {
        Remove-Item $target -ErrorAction SilentlyContinue
        throw "SHA-256 не совпал для $($file.Name): ожидался $($file.Hash), получен $actual"
    }
    $downloaded++
}

$total = (Get-ChildItem $Destination -File | Measure-Object Length -Sum).Sum
Write-Host ("Готово: скачано {0}, уже было {1}, всего {2:N0} МБ." -f $downloaded, $skipped, ($total / 1MB))
Write-Host "Дальше: llm/scripts/push-model.ps1 (залить модель на устройство)."
