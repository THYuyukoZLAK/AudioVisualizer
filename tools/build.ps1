<#
.SYNOPSIS
    构建 APK。

.EXAMPLE
    .\tools\build.ps1                 # debug APK（默认）
    .\tools\build.ps1 -Release        # release APK（存在 keystore.properties 时自动签名）
    .\tools\build.ps1 -Clean          # 先 clean
    .\tools\build.ps1 -Task test      # 跑任意 Gradle 任务

.NOTES
    产物在 app\build\outputs\apk\<flavor>\<buildType>\ 下。
    首次构建会下载 AGP / Kotlin / AndroidX 依赖（dl.google.com + Maven Central，
    本机直连即可，不需要代理）。
#>
param(
    [switch]$Release,
    [switch]$Clean,
    [string[]]$Task
)

. "$PSScriptRoot\gradle-env.ps1"

$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $tasks = @()
    if ($Clean) { $tasks += "clean" }
    if ($Task -and $Task.Count -gt 0) {
        $tasks += $Task
    } else {
        $tasks += if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
    }

    Write-Host ("== gradlew " + ($tasks -join " ") + " ==") -ForegroundColor Cyan
    & .\gradlew.bat @tasks --console=plain
    $code = $LASTEXITCODE

    if ($code -eq 0 -and -not ($tasks -contains "clean")) {
        $apkDir = Join-Path $root "app\build\outputs\apk"
        if (Test-Path $apkDir) {
            Write-Host ""
            Write-Host "== APK 产物 ==" -ForegroundColor Cyan
            Get-ChildItem $apkDir -Recurse -Filter *.apk |
                ForEach-Object { Write-Host ("  {0,9:N2} MB  {1}" -f ($_.Length / 1MB), $_.FullName) }
        }
    }
    exit $code
} finally {
    Pop-Location
}
