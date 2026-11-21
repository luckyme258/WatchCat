# ci_watch.ps1 —— 盯住最新一次 CI run，跑完打印结论；失败就把该 job 的日志落到 apk\ci-log.txt
# 用法：pwsh tools\ci_watch.ps1            # 盯最新一次
#       pwsh tools\ci_watch.ps1 -RunId 44912790152
param(
    [string]$Repo = 'luckyme258/WatchCat',
    [string]$RunId = '',
    [string]$TokenFile = 'D:\keys\github_token.txt',
    [int]$TimeoutSec = 900
)
$ErrorActionPreference = 'Stop'
$token = (Get-Content $TokenFile -Raw).Trim()
$h = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json' }
$api = "https://api.github.com/repos/$Repo"

if (-not $RunId) {
    $RunId = (Invoke-RestMethod -Uri "$api/actions/runs?per_page=1" -Headers $h).workflow_runs[0].id
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$run = $null
while ((Get-Date) -lt $deadline) {
    $run = Invoke-RestMethod -Uri "$api/actions/runs/$RunId" -Headers $h
    if ($run.status -eq 'completed') { break }
    Start-Sleep -Seconds 15
}

"run=$RunId head=$($run.head_sha.Substring(0,7)) status=$($run.status) conclusion=$($run.conclusion)"
if ($run.status -ne 'completed') { "TIMEOUT：还没跑完，过一会再查"; exit 2 }

$jobs = (Invoke-RestMethod -Uri "$api/actions/runs/$RunId/jobs" -Headers $h).jobs
foreach ($job in $jobs) {
    "JOB: $($job.name) => $($job.conclusion)"
    foreach ($s in $job.steps) {
        if ($s.conclusion -and $s.conclusion -notin @('success', 'skipped')) {
            "   STEP FAIL: $($s.name) [$($s.conclusion)]"
        }
    }
}

$logs = (Invoke-RestMethod -Uri "$api/actions/runs/$RunId/logs" -Headers $h -Method Get)
# 日志接口直接返回纯文本（不是 zip），原样保存便于 grep
$outDir = Join-Path $PSScriptRoot '..\apk'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$logPath = Join-Path $outDir 'ci-log.txt'
Set-Content -LiteralPath $logPath -Value $logs -Encoding UTF8
# 只摘错误行，避免把 300KB 日志糊到界面上
$errs = Select-String -LiteralPath $logPath -Pattern 'e: file|BUILD FAILED|error:|FAILURE' |
    ForEach-Object { $_.Line -replace '^\S+Z\s*', '' } | Select-Object -First 30
if ($errs) { '--- 关键错误 ---'; $errs }
"log=$logPath"
