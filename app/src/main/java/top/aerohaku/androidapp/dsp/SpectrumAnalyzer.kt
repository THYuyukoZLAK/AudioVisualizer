package top.aerohaku.androidapp.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 把 PCM 变成可视化帧：频谱柱 + 波形 + 左右声道电平（dB）。
 *
 * ## 频谱部分：复刻 osu! 播放器插件 mfosu 的做法
 *
 * 参考 `AndroidApp/reference/sourcecode/.../Screens/Visualizer/`，链路是：
 *
 * ```
 * BASS ChannelGetData(FFT512)          → 256 个线性频率格（0 .. Nyquist）
 * osu.Framework ChannelAmplitudes      → 只保留前 160 格（≈0 .. 13.8kHz @44.1k）
 * MusicVisualizerDrawable.getConverted → 120 根柱，柱 i 直接取 bin floor(i×160/120)
 * ApplyData                            → 瞬时上升：新值更大就顶上去，并记住峰值
 * UpdateData                           → 从峰值线性衰减，跌到 0 恰好用 decayMs
 * Smooth(smoothness)                   → 就地箱式平滑（沿柱子方向）
 * 绘制                                 → 高度px = 幅度 × HeightMultiplier
 * ```
 *
 * ### 和「传统频谱仪」的几个关键差别（都是刻意的）
 * - **线性频率轴**，不是对数轴。低频只占左侧极窄几条，中高频铺满 —— BASS FFT512 点
 *   在 44.1kHz 下每格 86.1Hz，160 格才铺到 13.8kHz。
 * - **线性幅度**，不是 dB。安静的高频就是几乎为 0，而不是「-60dB 还能占 20% 高度」。
 * - **不做跨 bin 聚合**：一根柱就是一个 bin 的原始值，所以谐波是尖锐的孤立峰，
 *   不会像频带取峰值那样糊成一条平滑包络。
 * - **衰减与幅度成正比**（`current -= peak/decayMs × dt`），也就是**从峰值到 0 固定耗时**，
 *   而不是 dB 域的固定 dB/秒。这样高柱掉得快、矮柱掉得慢，鼓点看起来是「弹」下去的。
 *
 * ### 参数（与 mfosu 设置面板一一对应）
 * | 本类 | mfosu | 默认 |
 * |---|---|---|
 * | [barCount] | `BarCountB` | 120 |
 * | [decayMs] | `DecayB` | 200 |
 * | [displayGain] | `MultiplierB` / 画布高度 200 | 2.0（≈ mfosu 的 400） |
 * | [smoothness] | `SmoothnessB` | 1 |
 *
 * ⚠️ [displayGain] 是唯一无法从源码确定的量：它把 BASS FFT 的绝对标度换算成屏幕高度。
 * 我们自己 [Fft.magnitudes] 的约定是「满量程正弦在其所在 bin 读出 1.0」，
 * 若与 BASS 有常数倍差异，只需要调 [displayGain] 即可对齐观感。
 *
 * ### 与 osu! 不同的地方（有意保留）
 * - 用**环形缓冲 + 每 ~24ms 出一帧**，osu! 是每渲染帧取一次：观感一致，但不会把 UI 刷爆
 * - 衰减被钳制在 0（osu! 会掉成负数，只是被后续数据盖住，画出来没有区别）
 */
class SpectrumAnalyzer(
  private val sampleRate: Int,
  private val channelCount: Int,
  barCount: Int = DEFAULT_BAR_COUNT,
  decayMs: Float = DEFAULT_DECAY_MS,
  displayGain: Float = DEFAULT_DISPLAY_GAIN,
  smoothness: Int = DEFAULT_SMOOTHNESS,
) {

  private val bars = barCount.coerceIn(MIN_BAR_COUNT, MAX_BAR_COUNT)
  private val decay = decayMs.coerceIn(MIN_DECAY_MS, MAX_DECAY_MS)
  private val gain = displayGain.coerceIn(MIN_DISPLAY_GAIN, MAX_DISPLAY_GAIN)
  private val smooth = smoothness.coerceIn(0, MAX_SMOOTHNESS)

  companion object {
    /** 与 BASS `DataFlags.FFT512` 一致：512 点 FFT */
    const val FFT_SIZE = 512

    /** bASS FFT512 返回的格数（bin 0..255，不含 Nyquist） */
    const val BIN_COUNT = 256

    /**
     * `osu.Framework.Audio.Track.ChannelAmplitudes` 只用到前 160 格。
     * 源码注释原话：「256 length array of bins ... at every ~78Hz step (0Hz - 20,000Hz)」，
     * 实际取用范围见 `MusicVisualizerDrawable.used_amplitude_count = 160`。
     */
    const val USED_BIN_COUNT = 160

    const val DEFAULT_BAR_COUNT = 120
    const val MIN_BAR_COUNT = 8
    const val MAX_BAR_COUNT = 300

    /** 从峰值衰减到 0 所需时间（ms）。mfosu `DecayB` 默认 200 */
    const val DEFAULT_DECAY_MS = 200f
    const val MIN_DECAY_MS = 40f
    const val MAX_DECAY_MS = 800f

    /**
     * 显示增益：`柱高 = 幅度 × displayGain`（1.0 = 满屏）。
     *
     * mfosu 是 `高度px = 幅度 × MultiplierB`，画布高 200px、MultiplierB 默认 400，
     * 即 400/200 = 2.0。但**直接照抄 2.0 画出来柱子明显偏矮**，真机逐帧实测：
     * 该歌曲下 120 根柱的全局 p50 = 1%、p99 = 24%、**max 仅 49%**，一根都没撞顶。
     *
     * 改成 **6.4** 后观感与 mfosu 对齐（用户真机对比确认 6~6.8 可用）。
     * 这个 3 倍的差异最可能来自 BASS 与本地 [Fft.magnitudes] 的绝对标度差
     * （BASS 的 FFT 归一化系数）；无法从源码确认，所以全部折进这个常量。
     * 以后若换采集源导致整体偏高/偏矮，优先调它（对应 mfosu 的 `MultiplierB`）。
     */
    const val DEFAULT_DISPLAY_GAIN = 6.4f
    const val MIN_DISPLAY_GAIN = 0.25f
    const val MAX_DISPLAY_GAIN = 12f

    /** 沿柱子方向做就地箱式平滑的半径。mfosu `SmoothnessB` 默认 1 */
    const val DEFAULT_SMOOTHNESS = 1
    const val MAX_SMOOTHNESS = 20

    /** 波形显示点个数 */
    const val WAVEFORM_POINTS = 256

    /** 环形缓冲容量（同时要 ≥ [FFT_SIZE] 与 [WAVEFORM_WINDOW]，取 2 的幂方便取模） */
    private const val RING_SIZE = 1024

    /** 波形只取最近这么多个样本（约 23ms）—— 太长就看不出波形形状了 */
    private const val WAVEFORM_WINDOW = 1024

    /** 电平表：RMS 下落速度 */
    private const val RMS_FALL_DB_PER_SEC = 26f

    /** 电平表：峰值保持时长与随后下落速度 */
    private const val PEAK_HOLD_MS = 1_200L
    private const val PEAK_FALL_DB_PER_SEC = 30f

    /**
     * 准峰值的子窗长度。
     *
     * ‼ 峰值不能用「整块的最大绝对值」：16.7ms 块的最大值在音乐上几乎必然贴着满量程
     * （数字母带峰值本来就压到 -1..0 dBFS），于是 `peakHold` 的「保持」分支被无限刷新，
     * 回落分支永远执行不到 —— 峰值线会在整首歌里一动不动地钉在 0 dB。
     *
     * 改用子窗 RMS 的最大值（即电平表里的「准峰值」）：3ms 短到能跟上鼓点、
     * 又长到不会被单个采样毛刺带跑，读数会真的随音乐起伏。
     */
    private const val QUASI_PEAK_WINDOW_MS = 3f

    /**
     * 产出帧的最低间隔。
     *
     * 目标是 60fps（16.7ms），但采集块到达会有抖动，所以卡在 12ms：
     * 既不会把 16.7ms 的那一块误杀成 30fps，又能拦住 `AudioRecord.read` 的碎片化短读，
     * 避免每几毫秒就白算一次 FFT（FFT 窗本身就长达 512 样本 ≈ 11.6ms，比这更密也没信息量）。
     */
    private const val MIN_FRAME_INTERVAL_MS = 12L

    /**
     * 柱 → bin 的下标映射，与 mfosu `(int)MathExtensions.Map(i, 0, BarCount, 0, 160)` 等价：
     * 整除截断，所以每 3 根柱会跳过一个 bin。
     */
    fun binIndexForBar(bar: Int, barCount: Int): Int = (bar * USED_BIN_COUNT) / barCount

    /**
     * 每根柱对应的频率（Hz），供 UI 画横向刻度。
     * 因为频率轴是**线性**的，柱子天然等宽分布，刻度直接按比例落位即可。
     */
    fun barFrequencies(sampleRate: Int, barCount: Int): FloatArray {
      val n = barCount.coerceIn(MIN_BAR_COUNT, MAX_BAR_COUNT)
      return FloatArray(n) { binIndexForBar(it, n) * sampleRate.toFloat() / FFT_SIZE }
    }
  }

  /**
   * 与 BASS 一致：**不加窗**（矩形窗）。
   * 加 Hann 会把相邻 bin 抹平、削掉峰值，画出来就没有 mfosu 那种尖锐感了。
   */
  private val fft = Fft(FFT_SIZE)

  private val re = FloatArray(FFT_SIZE)
  private val im = FloatArray(FFT_SIZE)
  private val mags = FloatArray(fft.binCount)

  /** 单声道混合样本的环形缓冲（最旧样本位于 [ringWrite]） */
  private val monoRing = FloatArray(RING_SIZE)
  private var ringWrite = 0
  private var filled = 0

  /** 每根柱当前的幅度（线性，0..1） */
  private var current = FloatArray(bars)

  /** 每根柱本轮衰减的起点（峰值） */
  private var peak = FloatArray(bars)

  /** 平滑后的幅度，供绘制 */
  private var smoothed = FloatArray(bars)

  /** 0=rmsL 1=rmsR 2=peakL 3=peakR */
  private val meterDb = FloatArray(4) { Levels.MIN_DB }
  private val peakHoldUntilMs = LongArray(2)

  private var lastLevelMs = -1L
  private var lastFrameMs = -1L

  /** 供 UI / 测试查询：某根柱对应的频率（Hz） */
  fun barFrequency(bar: Int): Float = binIndexForBar(bar, bars) * sampleRate.toFloat() / FFT_SIZE

  fun reset() {
    monoRing.fill(0f)
    current.fill(0f)
    peak.fill(0f)
    smoothed.fill(0f)
    meterDb.fill(Levels.MIN_DB)
    peakHoldUntilMs.fill(0L)
    ringWrite = 0
    filled = 0
    lastLevelMs = -1L
    lastFrameMs = -1L
  }

  /**
   * 消费一块**交错** PCM。
   * 返回 null 表示这一块还没到产出间隔（电平仍在内部继续更新）。
   */
  fun process(samples: ShortArray, count: Int, nowMs: Long): AudioFrame? {
    val frames = count / channelCount
    if (frames <= 0) return null

    val levelDtMs = elapsedSinceLastLevel(nowMs)
    updateChannelLevels(samples, frames, nowMs, levelDtMs)
    pushToRing(samples, frames)

    val dtMs = if (lastFrameMs < 0L) 20f else (nowMs - lastFrameMs).coerceIn(1L, 250L).toFloat()
    val due = lastFrameMs < 0L || nowMs - lastFrameMs >= MIN_FRAME_INTERVAL_MS
    if (!due) return null

    lastFrameMs = nowMs
    updateSpectrum(dtMs)
    return buildFrame()
  }

  // ------------------------------------------------------------------ 电平

  private fun elapsedSinceLastLevel(nowMs: Long): Float {
    val dt = if (lastLevelMs < 0L) 20f else (nowMs - lastLevelMs).coerceIn(1L, 250L).toFloat()
    lastLevelMs = nowMs
    return dt
  }

  private fun updateChannelLevels(samples: ShortArray, frames: Int, nowMs: Long, dtMs: Float) {
    var sumL = 0.0
    var sumR = 0.0

    // 准峰值：把整块切成固定长度的子窗，取各子窗 RMS 的最大值（见 QUASI_PEAK_WINDOW_MS）
    val subWindow = (sampleRate * QUASI_PEAK_WINDOW_MS / 1000f).toInt().coerceAtLeast(1)
    var subSumL = 0.0
    var subSumR = 0.0
    var subCount = 0
    var peakL = 0f
    var peakR = 0f

    var i = 0
    while (i < frames) {
      val left = samples[i * channelCount].toInt()
      val right = if (channelCount > 1) samples[i * channelCount + 1].toInt() else left
      val sqL = left.toDouble() * left
      val sqR = right.toDouble() * right
      sumL += sqL
      sumR += sqR
      subSumL += sqL
      subSumR += sqR
      subCount++
      if (subCount == subWindow) {
        peakL = max(peakL, rmsAmplitude(subSumL, subCount))
        peakR = max(peakR, rmsAmplitude(subSumR, subCount))
        subSumL = 0.0
        subSumR = 0.0
        subCount = 0
      }
      i++
    }
    // 不足一个子窗的尾段也要比一比，否则块尾的峰值会被丢掉
    if (subCount > 0) {
      peakL = max(peakL, rmsAmplitude(subSumL, subCount))
      peakR = max(peakR, rmsAmplitude(subSumR, subCount))
    }

    val rmsL = Levels.linearToDb(rmsAmplitude(sumL, frames) / 32768f)
    val rmsR = Levels.linearToDb(rmsAmplitude(sumR, frames) / 32768f)

    meterDb[0] = attackRelease(meterDb[0], rmsL, RMS_FALL_DB_PER_SEC, dtMs)
    meterDb[1] = attackRelease(meterDb[1], rmsR, RMS_FALL_DB_PER_SEC, dtMs)

    val peakLdb = Levels.linearToDb(peakL / 32768f)
    val peakRdb = Levels.linearToDb(peakR / 32768f)
    meterDb[2] = peakHold(slot = 2, holdIndex = 0, target = peakLdb, nowMs = nowMs, dtMs = dtMs)
    meterDb[3] = peakHold(slot = 3, holdIndex = 1, target = peakRdb, nowMs = nowMs, dtMs = dtMs)
  }

  /** 平方和 + 样本数 → RMS 幅度（与采样同量纲，0..32768） */
  private fun rmsAmplitude(sumSquares: Double, count: Int): Float =
    sqrt(sumSquares / count).toFloat()

  /** 瞬时上升、缓慢下落 —— 电平表的标准表现 */
  private fun attackRelease(current: Float, target: Float, fallDbPerSec: Float, dtMs: Float): Float =
    if (target >= current) target else max(target, current - fallDbPerSec * dtMs / 1000f)

  private fun peakHold(slot: Int, holdIndex: Int, target: Float, nowMs: Long, dtMs: Float): Float {
    if (target >= meterDb[slot]) {
      meterDb[slot] = target
      peakHoldUntilMs[holdIndex] = nowMs + PEAK_HOLD_MS
      return target
    }
    if (nowMs >= peakHoldUntilMs[holdIndex]) {
      meterDb[slot] = max(target, meterDb[slot] - PEAK_FALL_DB_PER_SEC * dtMs / 1000f)
    }
    return meterDb[slot]
  }

  // ------------------------------------------------------------------ 频谱

  private fun pushToRing(samples: ShortArray, frames: Int) {
    var w = ringWrite
    var i = 0
    while (i < frames) {
      val left = samples[i * channelCount]
      val right = if (channelCount > 1) samples[i * channelCount + 1] else left
      monoRing[w] = (left + right) * 0.5f / 32768f
      w++
      if (w == RING_SIZE) w = 0
      i++
    }
    ringWrite = w
    filled = min(RING_SIZE, filled + frames)
  }

  /** 环形缓冲里「倒数第 n 个」样本：n=0 是最新的一个 */
  private fun recent(n: Int): Float {
    val index = ringWrite - 1 - n
    return monoRing[if (index >= 0) index else index + RING_SIZE]
  }

  private fun updateSpectrum(dtMs: Float) {
    val ready = filled >= FFT_SIZE

    // 不加窗（矩形窗），与 BASS 一致
    for (i in 0 until FFT_SIZE) {
      re[i] = if (ready) recent(FFT_SIZE - 1 - i) else 0f
      im[i] = 0f
    }

    fft.transform(re, im)
    fft.magnitudes(re, im, mags)

    // 1) 瞬时上升 + 记住峰值（mfosu ApplyData）
    for (bar in 0 until bars) {
      val value = mags[binIndexForBar(bar, bars)]
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

  private fun buildFrame(): AudioFrame {
    // ⚠️ 这里**不再**裁剪到 0..1。
    // 以前是 `(smoothed[it] * gain).coerceIn(0f, 1f)`，增益后的过饱和部分全被压成 1.0，
    // 频谱顶就出一条平线 —— 之后无论怎么调高部件，那条平线只会被拉高，
    // 手感像在调「截断高度」而不是缩放。
    // 现在把裁剪交给绘制层（按部件自己的显示增益），
    // 「部件高度」与「显示增益」才是两个独立、看得见效果的参数。
    val spectrum = FloatArray(bars) { smoothed[it] * gain }

    // 瞬时能量：直接把这一帧的 FFT 幅度求和。
    // 这是 mfosu 里 `MusicIntensityController.Intensity = amplitudes.Sum()` 的位置，
    // 那一行经 `CurrentRateContainer` 绑成了粒子时钟的 Rate —— 也就是「随音频运动」的来源。
    // 必须取 mags（瞬时）而不是 smoothed（带峰值保持的衰减值），否则鼓点过后粒子还会继续冲。
    var energy = 0f
    for (i in 0 until USED_BIN_COUNT) energy += mags[i]

    // 波形：取环形缓冲里**最近** WAVEFORM_WINDOW 个单声道样本，
    // 每 stride 个取绝对值最大的一个（保包络）。
    val waveform = FloatArray(WAVEFORM_POINTS)
    val stride = WAVEFORM_WINDOW / WAVEFORM_POINTS
    for (point in 0 until WAVEFORM_POINTS) {
      var value = 0f
      for (k in 0 until stride) {
        val back = WAVEFORM_WINDOW - 1 - (point * stride + k)
        val sample = if (back < filled) recent(back) else 0f
        if (abs(sample) > abs(value)) value = sample
      }
      waveform[point] = value
    }

    return AudioFrame(
      spectrum = spectrum,
      waveform = waveform,
      rmsDbL = meterDb[0],
      rmsDbR = meterDb[1],
      peakDbL = meterDb[2],
      peakDbR = meterDb[3],
      stereo = channelCount > 1,
      sampleRate = sampleRate,
      energy = energy,
    )
  }
}
