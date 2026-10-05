package top.aerohaku.androidapp.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * 频谱柱的「时间整形」：**瞬时上升 + 峰值锁存 → 线性幅度衰减 → 就地平滑 → 乘显示增益**。
 *
 * 这一层与「数据从哪来」完全无关，所以 PCM-FFT（`SpectrumAnalyzer`）和
 * Visualizer（`SystemMixCapture`）两条链路共用它 ——
 * 这样「鼓点弹下去」的手感在两种模式下是一致的，而不是各写一份、各调一遍。
 *
 * 算法逐句对应 mfosu 的 `MusicVisualizerDrawable.ApplyData` / `UpdateData` / `MathExtensions.Smooth`，
 * 细节见 [SpectrumAnalyzer] 的类注释。
 *
 * ⚠️ 两条链路的**幅度标度不同**（前者满量程正弦在某 bin 读出 1.0，后者是 8-bit），
 * 所以 [displayGain] 必须分开传：它不是「效果强度」，而是标度换算系数。
 */
class SpectrumShaper(
  barCount: Int = SpectrumAnalyzer.DEFAULT_BAR_COUNT,
  decayMs: Float = SpectrumAnalyzer.DEFAULT_DECAY_MS,
  displayGain: Float = SpectrumAnalyzer.DEFAULT_DISPLAY_GAIN,
  smoothness: Int = SpectrumAnalyzer.DEFAULT_SMOOTHNESS,
) {

  private val bars = barCount.coerceIn(SpectrumAnalyzer.MIN_BAR_COUNT, SpectrumAnalyzer.MAX_BAR_COUNT)
  private val decay = decayMs.coerceIn(SpectrumAnalyzer.MIN_DECAY_MS, SpectrumAnalyzer.MAX_DECAY_MS)
  private val gain = displayGain.coerceIn(SpectrumAnalyzer.MIN_DISPLAY_GAIN, SpectrumAnalyzer.MAX_DISPLAY_GAIN)
  private val smooth = smoothness.coerceIn(0, SpectrumAnalyzer.MAX_SMOOTHNESS)

  /** 每根柱当前的幅度 */
  private var current = FloatArray(bars)

  /** 每根柱本轮衰减的起点（峰值） */
  private var peak = FloatArray(bars)

  /** 平滑后的幅度，**原地返回**作为结果 */
  private var smoothed = FloatArray(bars)

  /** 柱数，供调用方确认长度 */
  val barCount: Int get() = bars

  fun reset() {
    current.fill(0f)
    peak.fill(0f)
    smoothed.fill(0f)
  }

  /**
   * 推进一帧。
   *
   * @param binMagnitudes 线性频率轴的幅度，长度必须 ≥ [SpectrumAnalyzer.USED_BIN_COUNT]。
   *   两条链路都保证这一点：PCM 链路传 257 长度的 FFT 幅度，Visualizer 链路先把
   *   8-bit 数据重采样到 160 格。
   * @param dtMs 距上一帧的真实毫秒数。**必须是真实时间**，
   *   因为「从峰值跌到 0 用 decayMs」这个手感依赖它
   * @return 柱高数组，0..1，**复用同一个数组**，不要持有引用
   */
  fun update(binMagnitudes: FloatArray, dtMs: Float): FloatArray {
    // 1) 瞬时上升 + 记住峰值（mfosu ApplyData）
    for (bar in 0 until bars) {
      val value = binMagnitudes[SpectrumAnalyzer.binIndexForBar(bar, bars)]
      if (value > current[bar]) {
        current[bar] = value
        peak[bar] = value
      }
    }

    // 2) 从峰值线性衰减：跌到 0 恰好用 decay 毫秒（mfosu UpdateData）
    val fallPerMs = 1f / decay
    for (bar in 0 until bars) {
      current[bar] = max(0f, current[bar] - peak[bar] * fallPerMs * dtMs)
      smoothed[bar] = current[bar]
    }

    // 3) 沿柱方向的就地箱式平滑（mfosu PostUpdate → MathExtensions.Smooth）
    smoothInPlace(smoothed, smooth)

    // 4) 折成柱高
    for (bar in 0 until bars) {
      smoothed[bar] = (smoothed[bar] * gain).coerceIn(0f, 1f)
    }
    return smoothed
  }

  /**
   * 就地箱式平滑 —— 与 mfosu 的 `MathExtensions.Smooth` 逐句对应。
   *
   * ⚠️ 因为是在原数组上边算边写，`j < i` 读到的已经是**平滑后**的值，
   * 所以 `severity = 1` 实际等价于一阶 IIR `out[i] = (out[i-1] + in[i]) / 2`，
   * 而不是普通的 3 点平均。这个「右偏 + 变柔」正是 mfosu 观感的一部分，照抄。
   */
  private fun smoothInPlace(data: FloatArray, severity: Int) {
    val n = data.size
    val s = min(severity, n / 2)
    if (s <= 0) return
    for (i in 0 until n) {
      val start = max(i - s, 0)
      val end = min(i + s, n)
      var sum = 0f
      for (j in start until end) sum += data[j]
      data[i] = sum / (end - start)
    }
  }
}
