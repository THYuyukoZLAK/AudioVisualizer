<#
.SYNOPSIS
    安装一个 Temurin JDK 到用户目录（免管理员）。

.EXAMPLE
    .\tools\fetch-jdk.ps1 -Major 21
    .\tools\fetch-jdk.ps1 -Major 17

.DESCRIPTION
    本机从 GitHub Releases 下东西是拉不动的：直连会挂死不动，走代理会在
    20~25 MB 处 `curl: (56) schannel: server closed abruptly`。
    而 Temurin JDK 恰好托管在 GitHub Releases。

    所以这里走**清华 TUNA 的 Adoptium 镜像**——它是同一份产物的完整镜像，
    逐位相同、SHA256 一致（哈希取自 Adoptium 官方 API，下载后强制校验），
    且本机直连速度快（实测约 4.4 MB/s），不花代理流量。

    脚本会先删掉旧的 `~\.jdks\temurin-<Major>` 再解压，所以重复执行是幂等的。
#>
param(
    [Parameter(Mandatory = $true)][string]$Major
)

$ErrorActionPreference = "Stop"

$proxy = "http://127.0.0.1:7897"
$dest = Join-Path $env:USERPROFILE ".jdks"
$dl = Join-Path (Split-Path $PSScriptRoot -Parent) "build\jdk-dist"

New-Item -ItemType Directory -Force -Path $dest, $dl | Out-Null

# 1) 问官方 API 要「当前最新的该大版本」的文件名与哈希
$api = "https://api.adoptium.net/v3/assets/latest/$Major/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
Write-Host ("== 查询 Adoptium API（JDK " + $Major + "）==") -ForegroundColor Cyan

$json = $null
foreach ($mode in @("direct", "proxy")) {
    try {
        if ($mode -eq "direct") { $r = Invoke-WebRequest -Uri $api -UseBasicParsing -TimeoutSec 20 }
        else { $r = Invoke-WebRequest -Uri $api -UseBasicParsing -Proxy $proxy -TimeoutSec 30 }
        $json = $r.Content | ConvertFrom-Json
        Write-Host ("  OK (" + $mode + ")")
        break
    } catch {
        Write-Host ("  fail (" + $mode + "): " + $_.Exception.Message)
    }
}
if (-not $json) { throw "无法访问 Adoptium API" }

$rel = $json[0]
$pkg = $rel.binary.package
$name = $pkg.name
$sha = $pkg.checksum

# 2) 镜像优先，GitHub 只作最后手段
$sources = @(
    @{ Name = "tuna";         Url = ("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/$Major/jdk/x64/windows/" + $name); Proxy = $false },
    @{ Name = "github+proxy"; Url = $pkg.link;                                                                          Proxy = $true  }
)

Write-Host ("  版本   " + $rel.version.semver)
Write-Host ("  文件   " + $name)
Write-Host ("  SHA256 " + $sha)
foreach ($s in $sources) { Write-Host ("  源[" + $s.Name + "] " + $s.Url) }

$zip = Join-Path $dl $name
$cached = $false
if (Test-Path $zip) {
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -eq $sha.ToUpper()) {
        Write-Host ("  已有完整缓存 {0:N1} MB" -f ((Get-Item $zip).Length / 1MB))
        $cached = $true
    } else {
        Write-Host "  缓存校验不过（可能是上次中断的残留），重新下载"
        Remove-Item $zip -Force
    }
}

if (-not $cached) {
    Write-Host "== 下载 ==" -ForegroundColor Cyan
    $ok = $false
    # 多源 + 多轮，每轮 -C - 续传，每轮结束用 SHA256 判定是否完工
    for ($pass = 1; $pass -le 3 -and -not $ok; $pass++) {
        foreach ($s in $sources) {
            if (Test-Path $zip) {
                if ((Get-FileHash $zip -Algorithm SHA256).Hash -eq $sha.ToUpper()) { $ok = $true; break }
            }
            if ($s.Proxy) { $cx = @("-x", $proxy) } else { $cx = @() }
            Write-Host ("  第{0}轮 源[{1}] 续传中 ..." -f $pass, $s.Name)
            & curl.exe -L -sS --fail --connect-timeout 15 --speed-limit 4096 --speed-time 25 -C - -o $zip @cx $s.Url
            $now = if (Test-Path $zip) { (Get-Item $zip).Length / 1MB } else { 0 }
            Write-Host ("    exit={0}  {1:N1} MB" -f $LASTEXITCODE, $now)
            if (Test-Path $zip) {
                if ((Get-FileHash $zip -Algorithm SHA256).Hash -eq $sha.ToUpper()) { $ok = $true; break }
            }
        }
    }
    if (-not $ok) {
        Write-Host ""
        Write-Host "自动下载失败。请手动下载下面任一链接，保存到：" -ForegroundColor Red
        Write-Host ("  " + $zip)
        foreach ($s in $sources) { Write-Host ("  " + $s.Url) }
        throw "下载失败"
    }
}

Write-Host "== 校验并解压 ==" -ForegroundColor Cyan
$actual = (Get-FileHash $zip -Algorithm SHA256).Hash
if ($actual -ne $sha.ToUpper()) { throw ("SHA256 不匹配。期望 $sha，实际 $actual") }
Write-Host "  SHA256 通过" -ForegroundColor Green

$out = Join-Path $dest "temurin-$Major"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
Expand-Archive -Path $zip -DestinationPath $out -Force

# zip 内是单层目录 jdk-<ver>，改名为 current 让路径稳定
$inner = Get-ChildItem $out -Directory | Select-Object -First 1
if (-not $inner) { throw "解压后没有找到 JDK 目录" }
$flat = Join-Path $out "current"
if (Test-Path $flat) { Remove-Item $flat -Recurse -Force }
Move-Item $inner.FullName $flat

$javac = Join-Path $flat "bin\javac.exe"
if (-not (Test-Path $javac)) { throw ("找不到 javac.exe：" + $javac) }

Write-Host ""
& (Join-Path $flat "bin\java.exe") -version 2>&1 | ForEach-Object { Write-Host ("  " + $_) }
& $javac -version 2>&1 | ForEach-Object { Write-Host ("  " + $_) }
Write-Host ("  路径 " + $flat) -ForegroundColor Green
