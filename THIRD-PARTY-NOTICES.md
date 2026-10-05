# 第三方许可证与致谢

本应用（AudioVisualizer，包名 `top.aerohaku.androidapp`）自身代码以 **MIT License** 发布，
全文见仓库根目录的 [`LICENSE`](LICENSE)。

**许可证全文不写在本文档里**，而是集中放在 [`licenses/`](licenses/) 目录，
由 `tools/make-licenses.ps1` 拼接成 App 内的资源
`app/src/main/res/raw/third_party_licenses.txt`，在「设置 → 关于 → 展开许可证全文」处可读。

> 改完 `licenses/` 下任何文件，都要重跑一次 `tools/make-licenses.ps1`，
> 否则装进 APK 的还是旧文本。

## 一、本项目自身代码

MIT License — Copyright (c) 2026 THYuyukoZLAK

## 二、LLin（osu! 插件 IGPlayer，即「mfosu」）

- 项目：<https://github.com/MATRIX-feather/LLin>
- 许可证：**MIT License**
- Copyright (c) 2025 MATRIX-feather

### 参考/移植了哪些内容

本应用的**频谱分析**与**背景粒子**两处实现，是阅读该项目源码后移植改写的。
原项目是 C# + osu.Framework，本项目是 Kotlin + Jetpack Compose —— **不是逐行翻译**，
但算法链路、参数含义与默认值都取自这些文件：

| LLin 源文件 | 本项目的对应实现 | 移植了什么 |
|---|---|---|
| `.../Configuration/SandboxRulesetConfigManager.cs` | `dsp/SpectrumAnalyzer.kt`、`ui/particles/ParticleField.kt` | 柱数 / 衰减 / 平滑度 / 粒子数 / 速度 / 方向等默认值与取值范围 |
| `.../Visualizers/MusicVisualizerDrawable.cs` | `dsp/SpectrumAnalyzer.kt` | 柱 → bin 的整除截断映射、瞬时上升 + 峰值锁存、从峰值线性幅度衰减、就地箱式平滑 |
| `.../Components/ParticlesDrawable.cs` | `ui/particles/ParticleField.kt` | 伪 3D 视差的深度模型、尺寸/透明度随深度映射、出界重投、各方向的位移公式 |
| `.../Components/Particles.cs` | `ui/components/ParticleFieldView.kt` | 粒子层的装配方式 |
| `.../MusicHelpers/MusicAmplitudesProvider.cs` | `dsp/SpectrumAnalyzer.kt` | 每帧取一次频谱幅度 |
| `.../MusicHelpers/MusicIntensityController.cs` | `dsp/AudioFrame.kt` 的 `energy` | **Σ幅度 = 音频强度** |
| `.../MusicHelpers/CurrentRateContainer.cs` | `ui/particles/ParticleField.kt` | 把音频强度绑定成**时间流速**（「随音频运动」的真正来源） |
| `.../MusicHelpers/RateAdjustableContainer.cs` | 同上 | 被音频强度缩放的自有模拟时钟 |

**未使用**该项目的任何美术资源、字体、贴图、二进制文件或 BASS 相关代码。

### 冲突处理

LLin 是 MIT，本项目也是 MIT —— **无冲突**，只需履行 MIT 的署名义务：
保留其版权声明与许可声明。这一点已通过在内置许可证全文的第一节之后
完整收录 LLin 的 MIT 原文来满足。

## 三、JetBrains Mono 字体

- 项目：<https://github.com/JetBrains/JetBrainsMono>
- 许可证：**SIL Open Font License 1.1**
- Copyright 2020 The JetBrains Mono Project Authors

打包的是两个**未修改**的 TTF（`app/src/main/res/font/jetbrains_mono_{regular,bold}.ttf`），
未使用保留字体名，也未改名后再分发。

## 四、AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines

- 许可证：**Apache License 2.0**
- Copyright 2019-2026 The Android Open Source Project
- Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors

以 Maven 依赖形式引入，未修改其源码。

---

## 声明

**此软件为本人自用与小范围分发，不公开发行。**
