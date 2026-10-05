<#
.SYNOPSIS
    AVD（Android 模拟器）管理：列出 / 创建 / 启动 / 停止 / 删除。

.EXAMPLE
    .\tools\avd.ps1 list
    .\tools\avd.ps1 create                 # 建一个 Pixel 风格的 AVD
    .\tools\avd.ps1 start                  # 启动
    .\tools\avd.ps1 stop

.NOTES
    ⚠️ 本机目前**跑不了模拟器**：需要启用 Windows 功能
       「Windows Hypervisor Platform」（当前为 Disabled），要管理员权限 + 重启。
       命令见 README「启用模拟器」一节。
       系统镜像已下好：system-images/android-37.0/google_apis/x86_64

    ⚠️ 包 ID 的**分隔符要看工具**：
       - `avdmanager`（本脚本用的）+ `sdkmanager`：老工具，用 **`;`**
       - `android sdk` / `android emulator`（新 CLI）：用 **`/`**
       传错会报 “Package path is not valid”（而它会把正确写法回显给你）。

    ⚠️ 新 CLI 的 `android emulator create` 只接一个 <profile> 位置参数
       （phone / tablet / desktop），**不能指定 AVD 名字**，所以本脚本还是走 avdmanager。
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("list", "create", "start", "stop", "delete", "devices")]
    [string]$Action = "list",

    [string]$Name = "Pixel_API_37",
    # 老工具 avdmanager 要 ";" 分隔（不是新 CLI 的 "/"）
    [string]$Image = "system-images;android-37.0;google_apis;x86_64",
    [string]$Device = "pixel_7"
)

. "$PSScriptRoot\gradle-env.ps1"

switch ($Action) {

    "list" {
        Write-Host "== 已创建的 AVD ==" -ForegroundColor Cyan
        & emulator -list-avds
        Write-Host ""
        Write-Host "== 可用设备型号（前 20）==" -ForegroundColor Cyan
        & avdmanager list device 2>&1 | Select-String -Pattern '^\s*id:|^\s*Name:' | Select-Object -First 40
    }

    "devices" {
        Write-Host "== adb 可见的设备 ==" -ForegroundColor Cyan
        & adb devices -l
    }

    "create" {
        Write-Host ("== 创建 AVD: {0}  镜像: {1}  型号: {2} ==" -f $Name, $Image, $Device) -ForegroundColor Cyan
        # --force 让重复执行幂等；-d 指定设备型号
        "no" | & avdmanager create avd --name $Name --package $Image --device $Device --force
        Write-Host ""
        & emulator -list-avds
    }

    "start" {
        Write-Host ("== 启动 {0} ==" -f $Name) -ForegroundColor Cyan
        Write-Host "  模拟器窗口会独立弹出；本命令会一直占用当前终端。" -ForegroundColor Yellow
        & emulator -avd $Name
    }

    "stop" {
        Write-Host "== adb emu kill ==" -ForegroundColor Cyan
        & adb emu kill
        Write-Host "== adb devices ==" -ForegroundColor Cyan
        & adb devices
    }

    "delete" {
        Write-Host ("== 删除 AVD: {0} ==" -f $Name) -ForegroundColor Cyan
        & avdmanager delete avd --name $Name
    }
}
