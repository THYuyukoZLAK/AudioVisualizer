# 真机性能采样：把 tools/perf.sh 推到设备上跑一次。
#
# 用法：
#   . .\tools\gradle-env.ps1      # 先拿到 adb
#   .\tools\perf.ps1                         # 默认采目标应用 10 秒
#   .\tools\perf.ps1 -Seconds 20
#   .\tools\perf.ps1 -Package com.netease.cloudmusic
#
# 为什么要经这一个包装：设备端 sh 不认 CRLF，而本仓库的文件行尾是 CRLF
# （.gitattributes 只管住 git 里的，管不住工作区）。所以推之前先统一成 LF、
# 并且不带 BOM —— 否则 `sh /data/local/tmp/perf.sh` 会报语法错误。
param(
  [string]$Package = 'top.aerohaku.androidapp',
  [int]$Seconds = 10
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$shPath = Join-Path $PSScriptRoot 'perf.sh'

if (-not (Test-Path $shPath)) { throw "找不到 $shPath" }

# LF + 无 BOM
$text = ([IO.File]::ReadAllText($shPath)) -replace "`r`n", "`n"
$staged = Join-Path $env:TEMP 'av-perf.sh'
[IO.File]::WriteAllText($staged, $text, (New-Object System.Text.UTF8Encoding($false)))

adb push $staged /data/local/tmp/perf.sh | Out-Null
adb shell "sh /data/local/tmp/perf.sh '$Package' $Seconds"
