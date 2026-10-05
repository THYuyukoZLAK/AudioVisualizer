# AudioVisualizer

把 Android 设备上正在播放的音乐，实时画成**频谱 / 波形 / 电平**，并把专辑封面、歌词、
播放控制一起摆在同一个界面里。为**横屏车载 / 平板**场景设计。

界面沿用 SCEX 生态那套「深蓝 + 青色」仪表风格：无圆角、等宽字体、克制的发光。

> 音源目前面向 **网易云音乐** 做适配，但取音频走的是系统级的
> 「按应用抓取播放音频」通道，元数据走标准 `MediaBrowser`，
> 换别的播放器原则上只需要改一个包名常量。

---

## 功能

| 模块 | 说明 |
|---|---|
| **频谱** | 32 段对数分频；FFT 1024 点 + Hann 窗，指数平滑 + 峰值保持；可叠加 25% / 50% / 75% / 100% 标尺 |
| **波形** | 实时时域波形 |
| **电平表** | dBFS 柱状表，带 -48 / -36 / -24 / -12 dB 刻度 |
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

单元测试（纯 JVM，不需要设备）：

```bash
./gradlew :app:testDebugUnitTest
```

### 签名

release 需要仓库根目录的 `keystore.properties`（**不入库**）：

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件不存在时，release 产物为未签名，其余流程照常。

## 元数据与音频从哪来

这是整个项目里最需要解释清楚的一块。

### 音频：MediaProjection + AudioPlaybackCapture

Android 10 起 `AudioPlaybackCapture` 允许抓取**指定应用**的播放音频。
本项目申请 `MediaProjection` 授权，但**不录屏**，只用它换一个
`AudioPlaybackCaptureConfiguration`，把目标应用（默认网易云）的播放流抓成 PCM，
再算 RMS 与 FFT。

代价是每次启动需要用户点一次系统的「允许录制或投射」对话框。

### 元数据：网易云的车机版 MediaBrowser 服务

网易云对外声明了一个标准的 `MediaBrowserService`：

```
com.netease.cloudmusic/.module.ucar.UCarService
```

`ucar` = 车机版。走这条通道的好处是**零依赖、零私有 API**：
只用 framework 里的 `android.media.browse.MediaBrowser`，连 `androidx.media` 都不需要。

- 播放列表：`MediaController.getQueue()`（API 30+）
- 播放状态：`PlaybackState`，可用的 action 实测为 `0x336`（播放 / 暂停 / 上下一首 / 快进）
- 封面与歌词：`MediaMetadata` 的 extras

⚠️ **两个实测踩过的坑**（详见 `agents_mem.md` 坑十八、十九）：

1. 网易云推来的 metadata 是**残缺包** —— 同一首歌不同时刻带不同的键，
   `ALBUM_ART` 时有时无。直接读会让背景闪成纯黑、歌词被清空。所以需要一层
   `MetadataCarryOver`，把同一首歌缺的字段用上一次的值补齐。
2. 两条链路给的 `mediaId` **格式不一样**：通知里是 `411500348`，
   MediaBrowser 里是 `playlist_id_4866956204_3340114786`。
   所以模型里另存了一个净化过的 `songId`，歌词接口只认它。

### 回退通道

`NotificationListenerService` 作为可选回退，默认**关闭**
（设置 → 接口 → 元数据来源）。老版本网易云可能没开放 `UCarService`，
这时才需要它，代价是要给通知使用权，且拿不到播放列表。

### 循环模式：做不到

网易云没有通过任何标准接口暴露循环模式：framework 侧的 `REPEAT_MODE_*` /
`MediaController.getRepeatMode()` 在 API 36 上不存在；它私有的
`ucar.media.metadata.PLAY_MODE` 不是标量、`getDescription().extras` 返回 `null`。
所以界面上**没有**循环模式按钮。

## 目录结构

```
app/src/main/java/top/aerohaku/androidapp/
├── capture/    音频捕获引擎与前台服务（MediaProjection / AudioPlaybackCapture）
├── dsp/        FFT、频谱整形、音频帧定义
├── playback/   元数据来源（MediaBrowser / 通知）+ 播放控制 + 状态仲裁
├── lyrics/     LRC 解析与歌词抓取
├── display/    屏幕常亮、刷新率、帧统计
├── model/      数据模型（NowPlaying 等）
├── permission/ 权限申请封装
├── theme/      配色、通用控件（卡片 / 按钮 / 滑块）、拖动状态
└── ui/
    ├── visualizer/  主屏与 ViewModel
    ├── components/  频谱 / 波形 / 电平 / 歌词 / 封面 / 播放条 / 粒子
    ├── layout/      版式预设、锚点定位、缩放
    ├── settings/    设置页（分页：版式 / 外观 / 播放 / 接口 / 调试 / 关于）
    └── particles/   背景粒子场
```

## 许可证

本应用自身代码以 **MIT License** 发布，Copyright (c) 2026 THYuyukoZLAK。

**频谱分析**（`dsp/SpectrumAnalyzer.kt`）与**背景粒子**（`ui/particles/ParticleField.kt`）
两处实现移植 / 改写自 **LLin —— osu! 插件 IGPlayer，即「mfosu」**
（[MATRIX-feather/LLin](https://github.com/MATRIX-feather/LLin)，MIT，
Copyright (c) 2025 MATRIX-feather）。两者同为 MIT，无冲突；
已完整保留其版权声明与许可声明，**未使用该项目的任何美术资源、贴图或二进制文件**。

界面所用的 **JetBrains Mono** 字体为 SIL Open Font License 1.1
（Copyright 2020 The JetBrains Mono Project Authors），打包的是未修改的副本。

其余依赖（AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines）为 Apache License 2.0。

许可证全文**随 APK 内置**（设置 → 关于 → 查看许可证全文，由
`tools/make-licenses.ps1` 拼进 `res/raw/third_party_licenses.txt`）。
之所以必须打包进去：MIT 与 OFL 都要求「版权声明与许可声明随软件的所有副本一起提供」，
**APK 本身就是一份副本**，只放在源码仓库里是不够的。

## 维护备忘

**改包名** —— 四处要同步改：

1. `app/build.gradle.kts` 的 `namespace` 与 `applicationId`
2. `app/src/main/java/<包名路径>/` 目录结构（每级对应包名一段）
3. 所有 `.kt` 顶部的 `package` 声明与互相之间的 `import`
4. `tools/install-app.ps1` 里的 `$appId`

（`AndroidManifest.xml` 里的 `.MainActivity` 是相对写法，不用改。）

**改显示名** —— `app/src/main/res/values/strings.xml` 的 `app_name`。

**改版本号** —— `app/build.gradle.kts` 的 `versionCode` / `versionName`。

**关于 `gradle.properties`** —— 里面**故意**没有本机 JDK 路径。
工具链路径属本机配置，放在用户级 `%USERPROFILE%\.gradle\gradle.properties`；
新机器上照该文件里的注释加一行即可。

## 开发笔记

踩过的坑、设备特性、调试手法（`adb` / `logcat` / `uiautomator` 的用法）记在仓库外的
`agents_mem.md`，**未纳入版本库** —— 里面含本机绝对路径与测试设备序列号。
