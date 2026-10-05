<#
.SYNOPSIS
    本机 Android 开发环境的统一入口（被其它脚本 dot-source）。

.DESCRIPTION
    为什么需要这个文件：本机「Oracle Java 8」的两个目录挂在 **系统** PATH 上，
    而系统 PATH 排在用户 PATH 之前，所以任何 shell 里裸 `java` 都还是 1.8。
    Gradle / AGP / SDK 工具只认 JAVA_HOME，所以这里把它显式设成 JDK 21，
    并把 Android 的工具目录插到 PATH 最前面，避免依赖 PATH 的解析顺序。

    用法： . .\tools\gradle-env.ps1      （注意开头的点和空格 = dot-source）
#>

$ErrorActionPreference = "Continue"

# JDK：优先沿用已持久化的用户级 JAVA_HOME，失效则回退到已知路径
$jdk21 = $env:JAVA_HOME
if (-not $jdk21 -or -not (Test-Path (Join-Path $jdk21 "bin\java.exe"))) {
    $jdk21 = "C:\Users\xiazi\.jdks\temurin-21\current"
}
$env:JAVA_HOME = $jdk21

$sdk = "C:\Users\xiazi\AppData\Local\Android\Sdk"
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

# SDK 工具目录插到 PATH 最前面
$env:Path = @(
    (Join-Path $jdk21 "bin"),
    (Join-Path $sdk "platform-tools"),
    (Join-Path $sdk "cmdline-tools\latest\bin"),
    (Join-Path $sdk "emulator"),
    $env:Path
) -join ";"

# 注意：JDK 17（供 kotlin { jvmToolchain(17) } 使用）不在这里设置。
# 它是通过项目根目录 gradle.properties 里的
# org.gradle.java.installations.paths 告诉 Gradle 的 —— 那才是正确机制。
