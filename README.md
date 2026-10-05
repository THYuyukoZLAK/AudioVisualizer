# AudioVisualizer

**AIGC 代码注意**

把 Android 设备上正在播放的音乐，实时画成**频谱 / 波形 / 电平**，并把专辑封面、歌词、
播放控制一起摆在同一个界面里。为**横屏车载 / 平板**场景设计。

> 音源目前面向 **网易云音乐** 做适配，但取音频走的是系统级的「按应用抓取播放音频」通道，
> 元数据走标准 `MediaBrowser`，换别的播放器原则上只需要改一个包名常量。
> 原理与踩过的坑见 [docs/TECHNICAL.md](docs/TECHNICAL.md)。

---

## 功能

| 模块 | 说明 |
|---|---|
| **频谱** | 32 段对数分频；FFT 1024 点 + Hann 窗，指数平滑 + 峰值保持；可叠加 25% / 50% / 75% / 100% 标尺 |
| **波形** | 实时时域波形 |
| **电平表** | 左右声道 dBFS 柱状表 + 准峰值线；顶到满量程时在 0 dB 处亮红线 |
| **专辑封面** | 整屏虚化封面作背景，前景为封面卡片 |
| **歌词** | LRC 解析，支持翻译行，按播放进度逐行高亮滚动 |
| **播放控制** | 播放列表 / 上一首 / 播放·暂停 / 下一首；列表点选切歌；误操作 10 秒（可配 0–60s）后自动淡出 |
| **背景粒子** | 随音频能量起伏的粒子场，参数可调 |
| **版式** | 三种预设 + 每个部件的锚点 / 偏移 / 缩放单独可调；支持整体等比缩放 |
| **拖动实时预览** | 调任意滑块时整页透明，只留一个悬浮 HUD 显示当前值与实时效果 |

## 环境要求

| 项 | 值 |
|---|---|
| 最低系统 | Android 10（`minSdk 29`） |
| 目标系统 | Android 16（`targetSdk 36`） |
| 屏幕 | **横屏**使用，竖屏未适配 |
| ABI | 纯 framework / Kotlin，无 native 代码，不分 ABI |
| 构建 | JDK 17+、Android SDK Platform 36、Gradle 9.1（wrapper 自带） |

## 构建

```bash
# Linux / macOS
./gradlew :app:assembleRelease

# Windows
gradlew.bat :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。
单元测试是纯 JVM 的，不需要设备或模拟器：

```bash
./gradlew :app:testDebugUnitTest
```

### 签名

release 签名读取仓库根目录的 `keystore.properties`（**不在版本库里**）：

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件不存在时，release 产物为**未签名**，其余流程照常 —— 只是想自己构建来用的话，
改用 `:app:assembleDebug` 或 `:app:installDebug` 更省事。

## 安装

### 用现成的 APK

到 [Releases](https://github.com/THYuyukoZLAK/AudioVisualizer/releases) 下载
`AudioVisualizer-<版本>.apk` 直接安装（首次需要在系统里允许安装未知来源应用）。

已经连了 USB 调试的话，也可以：

```bash
adb install -r AudioVisualizer-1.2.apk
```

### 从源码装到设备

```bash
./gradlew :app:installRelease      # 或 :app:installDebug
```

### 首次运行

需要授予两项权限：

- **「允许录制或投射」**（MediaProjection）—— 用来抓取播放音频，**不会录屏**
- 可选：**通知使用权** —— 仅在老版本网易云上作元数据回退，默认关闭

### 注意

release 与 debug 的签名不同，**两者不能互相覆盖安装**。换签名版本前要先卸载，
而卸载会清掉全部设置（版式预设、外观配置等）。

## 许可证

本应用自身代码以 **MIT License** 发布，Copyright (c) 2026 THYuyukoZLAK。

- **频谱分析**与**背景粒子**两处实现移植 / 改写自 LLin —— osu! 插件 IGPlayer，即「mfosu」
  （[MATRIX-feather/LLin](https://github.com/MATRIX-feather/LLin)，MIT，
  Copyright (c) 2025 MATRIX-feather）。同为 MIT，无冲突；
  未使用该项目的任何美术资源、贴图或二进制文件。
- 界面字体 **JetBrains Mono** 为 SIL Open Font License 1.1。
- 其余依赖（AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines）为 Apache License 2.0。

许可证原文见 [`licenses/`](licenses/)，逐文件说明见
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)；
全文也随 APK 内置（设置 → 关于 → 查看许可证全文）。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/TECHNICAL.md](docs/TECHNICAL.md) | 元数据 / 音频链路原理、目录结构、设计取舍、维护备忘 |
| [docs/PLAN-audio-visualizer.md](docs/PLAN-audio-visualizer.md) | 立项时的可行性验证与计划 |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | 逐文件说明参考了 LLin 的哪些实现 |
