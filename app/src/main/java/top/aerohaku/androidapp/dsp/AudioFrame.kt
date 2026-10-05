package top.aerohaku.androidapp.dsp

import kotlin.math.log10
import kotlin.math.pow

/** 电平换算的公共约定 */
object Levels {

  /** 显示下限（dBFS）。低于它的都按静音处理 */
  const val MIN_DB = -75f

  /** 满量程 */
  const val MAX_DB = 0f

  private const val FLOOR = 1e-6f

  /**
   * 线性幅度（0..1）→ dBFS。
   *
   * ⚠️ 输入恒 ≤ 1.0（调用方拿的是 `peak / 32768` 这种归一化值），
   * 所以结果**恒 ≤ [MAX_DB]** —— 并不存在「超过 0 dBFS 的读数」。
   *
   * 这里曾经写成 `coerceIn(MIN_DB, MAX_DB + 6f)`，看起来像「允许过载」，
   * 但那个上界**永远达不到**；反倒让显示层以为要留 6dB 的余量
   * （见 `VizStyle.METER_CEIL_DB`），白白占掉 9% 的宽度。
   */
  fun linearToDb(amplitude: Float): Float =
    if (amplitude <= FLOOR) MIN_DB else (20f * log10(amplitude)).coerceIn(MIN_DB, MAX_DB)

  /** dBFS → 0..1（按 dB 线性，即标准的「对数幅度轴」） */
  fun normalize(db: Float): Float = normalize(db, linearMix = 0f)

  /**
   * dBFS → 0..1，并可在「对数（dB）」与「线性幅度」之间连续过渡。
   *
   * - `linearMix = 0`：柱高 ∝ dB —— 即标准的对数幅度轴
   * - `linearMix = 1`：柱高 ∝ 幅度 —— **响度区域被拉开约 3 倍**，
   *   低频跟着鼓点的起伏会明显得多；代价是安静的高频细节被压到看不见
   *
   * 实测：−6→−12dB 在 dB 轴上只给 8% 高度差，在线性幅度轴上给 25%。
   * 「看着带劲」的音乐可视化基本都是后者。
   */
  fun normalize(db: Float, linearMix: Float): Float {
    // 低于显示下限 = 静音。必须严格返回 0：
    // 线性幅度轴在 -75dB 处是 10^(-75/20) ≈ 1.8e-4（不是 0），
    // 不拦的话静音柱会拿到一个很小的非零值，画不出「点阵」基线。
    if (db <= MIN_DB) return 0f

    val dbNormalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    if (linearMix <= 0f) return dbNormalized

    val linearNormalized = 10f.pow(db / 20f).coerceIn(0f, 1f)
    if (linearMix >= 1f) return linearNormalized
    return dbNormalized * (1f - linearMix) + linearNormalized * linearMix
  }

  /** 峰值是否接近削顶（用于亮削顶指示灯） */
  fun isClipping(peakDb: Float): Boolean = peakDb >= -0.5f
}

/**
 * 一帧可视化数据（频谱 + 波形 + 左右声道电平）。
 *
 * 刻意用**普通 class** 而不是 data class：
 * 内部是 `FloatArray`，data class 的 equals/hashCode 对数组做引用比较，语义很有迷惑性。
 * 而 `StateFlow` 靠 equals 去重 —— 用普通 class 的引用相等语义可以保证「每帧都是新值、一定下发」。
 */
class AudioFrame(
  /** 频谱柱高度，0..1，长度为 [SpectrumAnalyzer.BAR_COUNT] */
  val spectrum: FloatArray,
  /** 波形显示点，-1..1，长度为 [SpectrumAnalyzer.WAVEFORM_POINTS] */
  val waveform: FloatArray,
  val rmsDbL: Float,
  val rmsDbR: Float,
  val peakDbL: Float,
  val peakDbR: Float,
  /** 是否真的拿到了立体声；false 表示左右电平必然相同 */
  val stereo: Boolean,
  /** 采集采样率，供 UI 推算频率刻度；0 表示未知 */
  val sampleRate: Int = 0,
  /**
   * 音频能量：**未经衰减/平滑**的频谱幅度直接求和（只统计 [SpectrumAnalyzer.USED_BIN_COUNT] 格）。
   *
   * 对应 mfosu 的 `MusicIntensityController.Intensity`（
   * `amplitudes.Sum()`），那一行被绑定到粒子的时钟速率上。
   * 注意取的是**瞬时**值，不是 [spectrum] 那份带峰值保持的衰减值 ——
   * 粒子需要的是「现在有多响」，不是「刚才峰值撑到多高」。
   */
  val energy: Float = 0f,
) {
  /** 是否已有真实数据（未捕获时是 [EMPTY]，频谱为空） */
  val hasData: Boolean get() = spectrum.isNotEmpty()

  companion object {
    val EMPTY = AudioFrame(
      spectrum = FloatArray(0),
      waveform = FloatArray(0),
      rmsDbL = Levels.MIN_DB,
      rmsDbR = Levels.MIN_DB,
      peakDbL = Levels.MIN_DB,
      peakDbR = Levels.MIN_DB,
      stereo = false,
      sampleRate = 0,
      energy = 0f,
    )
  }
}
