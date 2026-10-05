<#
.SYNOPSIS
    把 debug APK 装到已连接的设备/模拟器上（真机 USB 调试用这个）。

.EXAMPLE
    .\tools\install-app.ps1              # 构建 + 安装
    .\tools\install-app.ps1 -NoBuild     # 只安装已有的 APK
    .\tools\install-app.ps1 -Launch      # 安装后直接启动

.NOTES
    真机首次连接需要在手机上开「开发者选项 → USB 调试」，并同意授权弹窗。
    用 .\tools\device.ps1 查看设备是否被识别。
#>
param(
    [switch]$NoBuild,
    [switch]$Launch
)

. "$PSScriptRoot\gradle-env.ps1"

$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (-not $NoBuild) {
        & (Join-Path $PSScriptRoot "build.ps1")
        if ($LASTEXITCODE -ne 0) { Write-Host "构建失败，已中止。" -ForegroundColor Red; exit 1 }
    }

    $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk") -Recurse -Filter "*-debug.apk" -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Write-Host "找不到 debug APK，先跑一次 .\tools\build.ps1" -ForegroundColor Red; exit 1 }

    Write-Host ""
    Write-Host "== adb devices ==" -ForegroundColor Cyan
    & adb devices -l

    Write-Host ""
    Write-Host ("== adb install -r " + $apk.Name + " ==") -ForegroundColor Cyan
    & adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if ($Launch) {
        # 从 AndroidManifest 里的 applicationId 起主 Activity
        $appId = "top.aerohaku.androidapp"
        Write-Host ""
        Write-Host ("== am start " + $appId) -ForegroundColor Cyan
        & adb shell am start -n "$appId/.MainActivity"
    }
} finally {
    Pop-Location
}
