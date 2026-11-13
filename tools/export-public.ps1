<#
.SYNOPSIS
  Обновляет публичный экспорт проекта (по умолчанию ..\mylar-cam-public) из текущего коммита.

.DESCRIPTION
  Берёт только отслеживаемые git файлы текущего HEAD (git archive), поэтому reference/, output/, APK, ключи,
  local.properties, build/ и .gradle/ в экспорт не попадают (они в .gitignore). Папка .git экспорта не трогается:
  обновления идут обычными коммитами. После копирования скрипт проверяет, что запрещённых путей и личных данных нет,
  и индексирует изменения (git add -A). С -Message — сразу делает коммит.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\export-public.ps1
  powershell -ExecutionPolicy Bypass -File tools\export-public.ps1 -Message "Mylar Cam 0.8.11"
#>
param(
    [string]$Target = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) "..\mylar-cam-public"),
    [string]$Message = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$Target = [System.IO.Path]::GetFullPath($Target)
if ($Target -eq [System.IO.Path]::GetFullPath($repo)) { throw "Экспорт не может быть самим проектом" }

# 1. Снимок отслеживаемых файлов HEAD
$zip = Join-Path ([System.IO.Path]::GetTempPath()) ("mylar-export-" + [guid]::NewGuid() + ".zip")
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("mylar-export-" + [guid]::NewGuid())
& git -C $repo archive --format=zip -o $zip HEAD
if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
Expand-Archive -Path $zip -DestinationPath $tmp
Remove-Item $zip -Force

# 2. Запрещённые пути: если что-то из этого попало в git, экспорт не делается
$forbidden = '(^|/)(reference|output|build|\.gradle|\.idea)/|\.apk$|\.aab$|\.jks$|\.keystore$|(^|/)local\.properties$|(^|/)keystore\.properties$|assets/textures/'
$files = Get-ChildItem -Path $tmp -Recurse -File | ForEach-Object { $_.FullName.Substring($tmp.Length + 1).Replace('\', '/') }
$bad = $files | Where-Object { $_ -match $forbidden }
if ($bad) { Remove-Item $tmp -Recurse -Force; throw ("В git есть запрещённые для экспорта файлы:`n" + ($bad -join "`n")) }

# 3. Личные данные в текстах (почта, абсолютные пути пользователя)
$personal = '@gmail\.com|example\.invalid|[A-Za-z]:\\Users\\|/Users/[A-Za-z]'
$hits = Get-ChildItem -Path $tmp -Recurse -File -Include *.kt, *.kts, *.md, *.xml, *.properties, *.toml, *.frag, *.vert, *.glsl, *.ps1, *.gitignore, LICENSE |
    Select-String -Pattern $personal | Where-Object { $_.Path -notlike "*export-public.ps1" }
if ($hits) { Remove-Item $tmp -Recurse -Force; throw ("Похоже на личные данные:`n" + ($hits | ForEach-Object { "$($_.Path):$($_.LineNumber)" } | Out-String)) }

# 4. Замена содержимого экспорта (кроме .git) снимком
if (-not (Test-Path $Target)) {
    New-Item -ItemType Directory -Path $Target | Out-Null
}
Get-ChildItem -Path $Target -Force | Where-Object { $_.Name -ne ".git" } | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $tmp "*") -Destination $Target -Recurse -Force
Remove-Item $tmp -Recurse -Force

# 5. Индексация (и коммит, если задано сообщение)
if (-not (Test-Path (Join-Path $Target ".git"))) { & git -C $Target init -q -b main }
& git -C $Target add -A
if ($Message) {
    & git -C $Target commit -q -m $Message
    if ($LASTEXITCODE -ne 0) { Write-Host "Нечего коммитить" }
}
& git -C $Target status --short | Select-Object -First 40
$count = (& git -C $Target ls-files).Count
Write-Host "Экспорт: $Target, файлов в индексе: $count"
