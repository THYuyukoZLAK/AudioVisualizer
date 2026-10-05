<#
.SYNOPSIS
    真机（USB 调试）操作：列出 / 信息 / 截图 / 日志 / 卸载。

.EXAMPLE
    .\tools\device.ps1 list
    .\tools\device.ps1 info
    .\tools\device.ps1 screenshot
    .\tools\device.ps1 logcat

.NOTES
    装 APK 用 .\tools\install-app.ps1（它会先构建）。

    真机首次连接：手机开「开发者选项 → USB 调试」，插线后手机会弹
    「允许 USB 调试吗？」——**必须点允许**（可勾「一律允许」）。
    没点之前 `adb devices` 会显示 `unauthorized`，这是正常的中间状态，
    不是驱动或线的问题。点了之后变成 `device` 才能操作。

    本机实测设备：联想 TB321FU（Android 15 / API 35 / arm64-v8a / 平板）。
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("list", "info", "screenshot", "logcat", "uninstall", "restart-adb", "wake")]
    [string]$Action = "list",

    # screenshot 的输出路径；留空则写到 build\screenshots\<时间戳>.png
    [string]$Out,

    [int]$Lines = 200
)

. "$PSScriptRoot\gradle-env.ps1"

$root = Split-Path $PSScriptRoot -Parent
$appId = "top.aerohaku.androidapp"

function Show-State {
    Write-Host "== adb devices -l ==" -ForegroundColor Cyan
    & adb devices -l
    $state = (& adb devices) -join "`n"
    if ($state -match "unauthorized") {
        Write-Host ""
        Write-Host "  设备处于 unauthorized：请解锁手机，点掉「允许 USB 调试吗？」弹窗。" -ForegroundColor Yellow
        Write-Host "  若弹窗一直不出现，试 .\tools\device.ps1 restart-adb 重新触发。" -ForegroundColor Yellow
    }
}

switch ($Action) {

    "list" { Show-State }

    "info" {
        Show-State
        Write-Host ""
        Write-Host "== 设备属性 ==" -ForegroundColor Cyan
        foreach ($p in @(
            "ro.product.brand", "ro.product.model", "ro.product.device",
            "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.version.security_patch", "ro.product.cpu.abilist",
            "ro.build.characteristics"
        )) {
            $v = ((& adb shell getprop $p) -join "").Trim()
            Write-Host ("  {0,-34} {1}" -f $p, $v)
        }
        Write-Host ""
        Write-Host "== 屏幕 / 密度 ==" -ForegroundColor Cyan
        & adb shell wm size
        & adb shell wm density
        Write-Host ""
        Write-Host "== 本 app 是否已安装 ==" -ForegroundColor Cyan
        $pkgs = & adb shell pm list packages $appId
        if ($pkgs) { Write-Host "  已安装" } else { Write-Host "  未安装" }
    }

    "restart-adb" {
        Write-Host "== 重启 adb 服务（会重新触发手机授权弹窗）==" -ForegroundColor Cyan
        & adb kill-server
        Start-Sleep -Seconds 2
        & adb start-server
        Start-Sleep -Seconds 3
        Show-State
    }

    "wake" {
        Write-Host "== 唤醒屏幕 ==" -ForegroundColor Cyan
        & adb shell input keyevent KEYCODE_WAKEUP
        & adb shell wm dismiss-keyguard
    }

    "screenshot" {
        # 不要用 `adb exec-out screencap -p > file`：PowerShell 的 > 会给二进制做
        # 编码转换，PNG 直接坏掉。用 shell 生成到设备再 pull。
        $remote = "/sdcard/_adb_screen.png"
        if (-not $Out) {
            $dir = Join-Path $root "build\screenshots"
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            $Out = Join-Path $dir ((Get-Date -Format "yyyyMMdd-HHmmss") + ".png")
        }
        & adb shell screencap -p $remote
        & adb pull $remote $Out
        & adb shell rm $remote
        if (Test-Path $Out) {
            Write-Host ("  已保存 " + $Out) -ForegroundColor Green
        } else {
            Write-Host "  截图失败（设备是否已授权？）" -ForegroundColor Red
        }
    }

    "logcat" {
        Write-Host ("== logcat（只看本 app 与崩溃，最近 {0} 行）==" -f $Lines) -ForegroundColor Cyan
        & adb logcat -d -t $Lines |
            Select-String -Pattern "$appId|AndroidRuntime|FATAL EXCEPTION|E ActivityManager" |
            ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
        Write-Host "  （无输出 = 没有相关日志/崩溃）"
    }

    "uninstall" {
        Write-Host ("== 卸载 " + $appId + " ==") -ForegroundColor Cyan
        & adb uninstall $appId
    }
}
