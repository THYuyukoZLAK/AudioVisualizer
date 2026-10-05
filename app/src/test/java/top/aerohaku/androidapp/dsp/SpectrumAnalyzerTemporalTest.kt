package top.aerohaku.androidapp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * mfosu 模型频谱的**时间响应**测试。
 *
 * 核心性质来自 `MusicVisualizerDrawable`：
 * - `ApplyData`：瞬时上升（新值更大就顶上去，并记住峰值）
 * - `UpdateData`：`current -= peak / decayMs × dt` —— **线性幅度衰减**，
 *   也就是「从峰值跌到 0 恰好用 decayMs」，而不是 dB 域的固定 dB/秒。
 *
 * 用 bin 整数倍的纯音驱动，保证能量精确落在一根柱上，测量不受泄漏干扰。
 */
class SpectrumAnalyzerTemporalTest {

  private companion object {
    const val SAMPLE_RATE = 44_100
    const val CHUNK_MS = 20
    const val CHUNK_FRAMES = SAMPLE_RATE * CHUNK_MS / 1000

    /** 选 bin 整数倍：矩形窗下能量才精确落在这个 bin */
    const val TONE_BIN = 12
    const val TONE_HZ = TONE_BIN * SAMPLE_RATE / SpectrumAnalyzer.FFT_SIZE.toDouble()

    /** 纯音幅度；displayGain = 1 时它就是一比一的柱高 */
    const val TONE_AMPLITUDE = 0.5

    /** bin 12 落在哪根柱上（柱→bin 是整除截断） */
    val TONE_BAR = (0 until SpectrumAnalyzer.DEFAULT_BAR_COUNT)
      .first { SpectrumAnalyzer.binIndexForBar(it, SpectrumAnalyzer.DEFAULT_BAR_COUNT) == TONE_BIN }
  }

  private fun toneChunk(offsetFrames: Int, amplitude: Double): ShortArray {
    val out = ShortArray(CHUNK_FRAMES * 2)
    for (i in 0 until CHUNK_FRAMES) {
      val n = i + offsetFrames
      val value = (sin(2 * PI * TONE_HZ * n / SAMPLE_RATE) * amplitude * 32767).toInt().toShort()
      out[i * 2] = value
      out[i * 2 + 1] = value
    }
    return out
  }

  private val silence = ShortArray(CHUNK_FRAMES * 2)

  /** 采集 (相对时间 ms, 柱高) 序列 */
  private fun collect(
    analyzer: SpectrumAnalyzer,
    bar: Int,
    startMs: Long,
    blocks: Int,
    chunkAt: (block: Int) -> ShortArray,
  ): Pair<Long, List<Pair<Long, Float>>> {
    val out = ArrayList<Pair<Long, Float>>()
    var now = startMs
    var offset = 0
    repeat(blocks) { block ->
      val chunk = chunkAt(block)
      offset += CHUNK_FRAMES
      now += CHUNK_MS
      analyzer.process(chunk, chunk.size, now)?.let { out += now to it.spectrum[bar] }
    }
    return now to out
  }

  @Test
  fun `衰减应是线性幅度且从峰值跌到 0 用时 decayMs`() {
    val decayMs = 200f
    val analyzer = SpectrumAnalyzer(
      sampleRate = SAMPLE_RATE,
      channelCount = 2,
      decayMs = decayMs,
      displayGain = 1f,
      smoothness = 0,
    )

    // 先放音，让柱子升到峰值
    var offset = 0
    val (_, toneFrames) = collect(analyzer, TONE_BAR, 0L, blocks = 30) { _ ->
      toneChunk(offset, TONE_AMPLITUDE).also { offset += CHUNK_FRAMES }
    }
    val peak = toneFrames.last().second
    assertTrue("应升到峰值，实际 $peak", peak > 0.3f)

    // 停音：继续喂静音并推进时间
    val stopAt = toneFrames.last().first
    val (_, decayFrames) = collect(analyzer, TONE_BAR, stopAt, blocks = 20) { silence }

    // 采样间隔由「采集块长度 CHUNK_MS」与「分析器产出节流」共同决定，
    // **从时间戳量出来**：这里曾经把 40ms 写死，后来把产出速率从 25fps 提到 60fps 就挂了。
    val intervalMs = (decayFrames[1].first - decayFrames[0].first).toFloat()
    assertTrue("采样间隔应为正，实际 $intervalMs ms", intervalMs > 0f)

    // 1) 线性幅度衰减 → 相邻采样点的差值恒定（末尾会留一个 ~1e-8 的浮点残差，滤掉）
    val values = decayFrames.map { it.second }
    val deltas = values.zipWithNext { a, b -> a - b }.filter { it > 1e-3f }
    assertTrue("静音后应至少有 3 个非零采样点，实际 $values", deltas.size >= 3)
    assertTrue(
      "衰减应是线性幅度（相邻差值恒定），实际差值 $deltas",
      deltas.max() - deltas.min() < deltas.max() * 0.05f,
    )

    // 2) 每个采样间隔的衰减量应等于 peak / decayMs × 间隔
    val expectedDelta = TONE_AMPLITUDE.toFloat() / decayMs * intervalMs
    assertEquals(
      "衰减速率应为 peak/decayMs",
      expectedDelta, deltas.first(), expectedDelta * 0.05f,
    )
  }

  @Test
  fun `衰减时间加倍则回落耗时也加倍`() {
    /** @return 归零耗时(ms) 与 采样间隔(ms) */
    fun fallTime(decayMs: Float): Pair<Long, Long> {
      val analyzer = SpectrumAnalyzer(
        sampleRate = SAMPLE_RATE,
        channelCount = 2,
        decayMs = decayMs,
        displayGain = 1f,
        smoothness = 0,
      )
      var offset = 0
      val (_, toneFrames) = collect(analyzer, TONE_BAR, 0L, blocks = 30) { _ ->
        toneChunk(offset, TONE_AMPLITUDE).also { offset += CHUNK_FRAMES }
      }
      val stopAt = toneFrames.last().first
      val (_, decayFrames) = collect(analyzer, TONE_BAR, stopAt, blocks = 120) { silence }
      val interval = decayFrames[1].first - decayFrames[0].first
      val zeroAt = decayFrames.first { it.second == 0f }.first
      return (zeroAt - stopAt) to interval
    }

    // 从最后一个有声帧算起，理论耗时 ≈ decayMs；采样点是 interval 间隔，
    // 所以归零点最多偏离一个间隔，容差取两个间隔。
    fun assertFallTime(decayMs: Float) {
      val (actual, interval) = fallTime(decayMs)
      val expected = decayMs
      assertTrue(
        "decay=${decayMs}ms 时应在 ${expected}ms 附近归零，" +
          "实际 ${actual}ms（采样间隔 ${interval}ms）",
        kotlin.math.abs(actual - expected) <= interval * 2f,
      )
    }

    assertFallTime(100f)
    assertFallTime(400f)
  }

  @Test
  fun `柱高应与幅度成正比`() {
    fun barHeight(amplitude: Double): Float {
      val analyzer = SpectrumAnalyzer(
        sampleRate = SAMPLE_RATE,
        channelCount = 2,
        displayGain = 1f,
        smoothness = 0,
      )
      var offset = 0
      val (_, frames) = collect(analyzer, TONE_BAR, 0L, blocks = 30) { _ ->
        toneChunk(offset, amplitude).also { offset += CHUNK_FRAMES }
      }
      return frames.last().second
    }

    val low = barHeight(0.1)
    val high = barHeight(0.25)
    assertTrue("两个幅度都应点亮柱子，实际 $low / $high", low > 0.01f && high > 0.01f)
    // 线性幅度轴：0.25 / 0.1 = 2.5
    assertEquals("柱高比例应等于幅度比例", 2.5f, high / low, 0.15f)
  }

  @Test
  fun `静音时所有柱为 0`() {
    val analyzer = SpectrumAnalyzer(SAMPLE_RATE, channelCount = 2)
    val (_, frames) = collect(analyzer, 0, 0L, blocks = 30) { silence }
    val last = frames.last().second
    assertEquals(0f, last, 0f)

    val allZero = frames.all { it.second == 0f }
    assertTrue("静音时不该有任何柱亮起", allZero)
  }

  @Test
  fun `柱数应决定 spectrum 长度`() {
    fun barCountOf(barCount: Int): Int {
      val analyzer = SpectrumAnalyzer(SAMPLE_RATE, channelCount = 2, barCount = barCount)
      var frame: AudioFrame? = null
      var now = 0L
      repeat(10) {
        now += CHUNK_MS
        analyzer.process(silence, silence.size, now)?.let { frame = it }
      }
      return requireNotNull(frame).spectrum.size
    }

    // mfosu 的 BarCountB 默认 120，可调范围很大
    assertEquals(SpectrumAnalyzer.DEFAULT_BAR_COUNT, barCountOf(120))
    assertEquals(64, barCountOf(64))
    // 超过 160 时多根柱共用同一个 bin，也应正常工作
    assertEquals(240, barCountOf(240))
  }
}
