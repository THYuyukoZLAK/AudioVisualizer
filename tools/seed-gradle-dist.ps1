<#
.SYNOPSIS
    从国内镜像预置 Gradle 发行包到 wrapper 缓存。

.DESCRIPTION
    为什么需要它：本机访问 services.gradle.org 会「连上然后卡在半途」
    （实测卡在 ~50 MB 不动，和 GitHub Releases 一个症状），导致 gradlew 永远
    下不完 128 MB 的发行包。腾讯云镜像有逐位相同的副本，速度约 5 MB/s。

    本脚本从 gradle-wrapper.properties 里读出发行版号和官方 distributionSha256Sum，
    下载后用该官方哈希校验，通过才放进 wrapper 缓存目录 —— 所以镜像的可信度
    与官方源等价。

.EXAMPLE
    .\tools\seed-gradle-dist.ps1
#>
$ErrorActionPreference = "Stop"

$propsPath = Join-Path (Split-Path $PSScriptRoot -Parent) "gradle\wrapper\gradle-wrapper.properties"
$props = Get-Content $propsPath -Raw

# distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
$mUrl = [regex]::Match($props, 'distributionUrl=.*?gradle-([0-9][^-]*)-bin\.zip')
if (-not $mUrl.Success) { throw "无法从 gradle-wrapper.properties 解析出版本号" }
$ver = $mUrl.Groups[1].Value

# distributionSha256Sum=<64 hex>
$mSha = [regex]::Match($props, 'distributionSha256Sum=([0-9a-fA-F]{64})')
if (-not $mSha.Success) { throw "gradle-wrapper.properties 里没有 distributionSha256Sum，拒绝用镜像（无法校验）" }
$want = $mSha.Groups[1].Value.ToLower()

$file = "gradle-$ver-bin.zip"
Write-Host ("Gradle {0}  期望 SHA256 {1}" -f $ver, $want) -ForegroundColor Cyan

$dists = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$ver-bin"
$dir = $null
if (Test-Path $dists) { $dir = (Get-ChildItem $dists -Directory | Select-Object -First 1).FullName }
if (-not $dir) {
    # wrapper 用 distributionUrl 的 MD5 命名子目录；没有就建一个占位目录
    $dir = Join-Path $dists "9agqghryom9wkf8r80qlhnts3"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
Write-Host ("缓存目录 " + $dir)

$dl = Join-Path (Split-Path $PSScriptRoot -Parent) "build\gradle-dist"
New-Item -ItemType Directory -Force -Path $dl | Out-Null
$src = Join-Path $dl $file

if ((Test-Path $src) -and ((Get-FileHash $src -Algorithm SHA256).Hash.ToLower() -eq $want)) {
    Write-Host "已下载且校验通过，跳过下载"
} else {
    Write-Host "从腾讯云镜像下载 ..." -ForegroundColor Cyan
    & curl.exe -L -sS --fail --connect-timeout 15 --speed-limit 65536 --speed-time 25 -o $src `
        "https://mirrors.cloud.tencent.com/gradle/$file"
    if ($LASTEXITCODE -ne 0) { throw "镜像下载失败（exit $LASTEXITCODE）" }
    Write-Host ("  完成 {0:N1} MB" -f ((Get-Item $src).Length / 1MB))
}

$got = (Get-FileHash $src -Algorithm SHA256).Hash.ToLower()
if ($got -ne $want) { throw ("SHA256 不匹配，拒绝写入缓存。期望 $want，实际 $got") }
Write-Host "SHA256 校验通过" -ForegroundColor Green

$target = Join-Path $dir $file
foreach ($junk in @("$target.part", "$target.lck", "$target.ok")) {
    if (Test-Path $junk) { Remove-Item $junk -Force -ErrorAction SilentlyContinue }
}
Copy-Item $src $target -Force
Write-Host ("已写入 " + $target) -ForegroundColor Green
Write-Host ""
Write-Host "现在可以跑 .\gradlew.bat --version 验证。" -ForegroundColor Cyan
