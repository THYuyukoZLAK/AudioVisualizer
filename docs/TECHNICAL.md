# 技术说明

面向想改动或移植本项目的开发者。**使用、构建与安装请见 [README](../README.md)。**

---

## 1. 元数据与音频从哪来

这是整个项目里最需要解释清楚的一块。

### 1.1 音频：MediaProjection + AudioPlaybackCapture

Android 10 起 `AudioPlaybackCapture` 允许抓取**指定应用**的播放音频。
本项目申请 `MediaProjection` 授权，但**不录屏**，只用它换一个
`AudioPlaybackCaptureConfiguration`，把目标应用（默认网易云）的播放流抓成 PCM，
再算 RMS 与 FFT。

代价是每次启动需要用户点一次系统的「允许录制或投射」对话框。

### 1.2 元数据：网易云的车机版 MediaBrowser 服务

网易云对外声明了一个标准的 `MediaBrowserService`：

```
com.netease.cloudmusic/.module.ucar.UCarService
```

`ucar` = 车机版。走这条通道的好处是**零依赖、零私有 API**：
只用 framework 里的 `android.media.browse.MediaBrowser`，连 `androidx.media` 都不需要。

- 播放列表：`MediaController.getQueue()`（API 30+）
- 播放状态：`PlaybackState`，可用的 action 实测为 `0x336`（播放 / 暂停 / 上下一首 / 快进）
- 封面与歌词：`MediaMetadata` 的 extras

⚠️ **两个实测踩过的坑**：

1. 网易云推来的 metadata 是**残缺包** —— 同一首歌不同时刻带不同的键，
   `ALBUM_ART` 时有时无。直接读会让背景闪成纯黑、歌词被清空。所以需要一层
   `MetadataCarryOver`（`playback/MediaSessionSnapshot.kt`），
   把同一首歌缺的字段用上一次的值补齐（用「专辑 | 标题 | 歌手」判定同曲，
   因为 `mediaId` 本身也会缺）。
2. 两条链路给的 `mediaId` **格式不一样**：通知里是 `411500348`，
   MediaBrowser 里是 `playlist_id_4866956204_3340114786`。
   所以模型里另存了一个净化过的 `songId`（`model/NowPlaying.kt`），歌词接口只认它。

⚠️ 另外：`MediaBrowser.connect()` 走的是 `bindService(BIND_AUTO_CREATE)`，
而 `UCarService` 在网易云的主进程里 —— 直接连会**把网易云进程拉起来**。
所以连接采用「引用计数 + 宽限期」策略（`REASON_UI` / `REASON_CAPTURE`，8 秒 teardown 宽限），
让用户自己关掉界面时不会连带重启音乐 App。

### 1.3 回退通道

`NotificationListenerService` 作为可选回退，默认**关闭**
（设置 → 接口 → 元数据来源）。老版本网易云可能没开放 `UCarService`，
这时才需要它，代价是要给通知使用权，且拿不到播放列表。

### 1.4 循环模式：做不到

网易云没有通过任何标准接口暴露循环模式：framework 侧的 `REPEAT_MODE_*` /
`MediaController.getRepeatMode()` 在 API 36 上不存在；它私有的
`ucar.media.metadata.PLAY_MODE` 不是标量、`getDescription().extras` 返回 `null`。
所以界面上**没有**循环模式按钮。

### 1.5 歌词

LRC 解析见 `lyrics/LrcParser.kt`（支持 `[mm:ss.xx]` 与翻译行），
抓取见 `lyrics/LyricsRepository.kt`，只依赖 `HttpURLConnection` + `org.json`，无第三方库。

网易云车机协议里其实自带 `ucar.media.metadata.LYRICS_WHOLE` / `LYRICS_LINE`
（理论上可以免掉网络请求），但它不是标量，需要走 `getDescription().extras` 再试 —— **尚未实现**。

---

## 2. 目录结构

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

### 2.1 几个设计取舍

- **频谱纵轴是线性幅度，不是 dB**。实现复刻自 mfosu（见许可证一节），
  它的高度映射是 `高度px = 幅度 × HeightMultiplier`。所以标尺画的是
  **相对满度百分比**（25/50/75/100%），标 dB 会让人误以为是对数轴。
  电平表相反 —— 它本来就在 dB 域，所以标真 dB（-48/-36/-24/-12）。
- **设置页是浮层，不是导航目的地**。否则调滑块时背后什么都没有，
  「实时看见效果」无从谈起。
- **拖动滑块时整页透明，靠一个独立的悬浮 HUD 显示当前值**。
  不能用「把其余卡片变淡、被按住的保持不透明」—— `alpha` 沿父链相乘，
  父节点变淡之后子节点无法单独亮回来。

---

## 3. 许可证为什么必须打进 APK

本应用自身代码是 MIT；频谱分析与背景粒子移植/改写自 LLin（同为 MIT）；
打包的字体 JetBrains Mono 是 SIL OFL 1.1。

**MIT 与 OFL 都要求「版权声明与许可声明随软件的所有副本一起提供」——
APK 本身就是一份副本。** 只把许可证放在源码仓库里是不够的，
必须让拿到 APK 的人也能读到全文。

做法：`tools/make-licenses.ps1` 把 `licenses/` 下的原文与各段声明拼成
`app/src/main/res/raw/third_party_licenses.txt`（约 20 KB），
在「设置 → 关于 → 查看许可证全文」里展开显示。

改动 `licenses/` 里任何文件后都要重跑该脚本，并确认 APK 内确实包含
（`aapt2 dump resources` 查看 `raw/third_party_licenses`）。

逐文件说明「参考了 LLin 的哪些实现」见 [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)。

---

## 4. 维护备忘

**改包名** —— 四处要同步改：

1. `app/build.gradle.kts` 的 `namespace` 与 `applicationId`
2. `app/src/main/java/<包名路径>/` 目录结构（每级对应包名一段）
3. 所有 `.kt` 顶部的 `package` 声明与互相之间的 `import`
4. `tools/install-app.ps1` 里的 `$appId`

（`AndroidManifest.xml` 里的 `.MainActivity` 是相对写法，不用改。）

**改显示名** —— `app/src/main/res/values/strings.xml` 的 `app_name`。

**改版本号** —— `app/build.gradle.kts` 的 `versionCode` / `versionName`。
发布 APK 时两者都要改（`versionCode` 必须递增，否则无法覆盖安装）。

**关于 `gradle.properties`** —— 里面**故意**没有本机 JDK 路径。
工具链路径属本机配置，放在用户级 `%USERPROFILE%\.gradle\gradle.properties`：

```properties
org.gradle.java.installations.paths=C\:\\Users\\<你>\\.jdks\\temurin-17\\current,C\:\\...\\temurin-21\\current
```

不写这一行也不会构建失败 —— `settings.gradle.kts` 启用了 foojay 解析器，
它只是会改为**联网自动下载** JDK。

**行尾** —— `.gitattributes` 已统一：源码与文档 `eol=lf`，
`.bat` / `.cmd` / `.ps1` 是 `eol=crlf`。
`gradlew` 必须保持 LF，否则在 Linux/macOS 上 `chmod +x` 后会报
`bad interpreter: No such file or directory`。

---

## 5. 开发笔记

本机环境适配脚本在 `tools/`（含 JDK / Gradle 镜像源处理），
