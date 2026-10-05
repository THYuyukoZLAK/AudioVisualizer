package top.aerohaku.androidapp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 电平表「峰值」的行为测试。
 *
 * 峰值原先取**整块的最大绝对值**。那个量在音乐上几乎必然贴着满量程
 * （数字母带峰值本来就压到 -1..0 dBFS），于是 `peakHold` 里「刷新保持期」的分支
 * 被无限命中、回落分支永远执行不到 —— 峰值线钉死在 0 dB 一动不动。
 *
 * 现在改成**准峰值**：把块切成 3ms 子窗，取各子窗 RMS 的最大值。
 */
class SpectrumAnalyzerLevelTest {

  private companion object {
    const val SAMPLE_RATE = 44_100
    const val CHUNK_MS = 20
    const val CHUNK_FRAMES = SAMPLE_RATE * CHUNK_MS / 1000

    /** dBFS 换算 —— 测试里独立算一遍，不复用被测代码 */
    fun db(amplitude: Double): Float = (20.0 * log10(amplitude)).toFloat()

    /** 双声道纯音：前 [loudFrames] 帧有声音，其余静音 */
    fun tone(loudFrames: Int, amplitude: Double): ShortArray {
      val out = ShortArray(CHUNK_FRAMES * 2)
      for (i in 0 until loudFrames) {
        val v = (sin(2 * PI * 1000.0 * i / SAMPLE_RATE) * amplitude * 32767).toInt().toShort()
        out[i * 2] = v
        out[i * 2 + 1] = v
      }
      return out
    }
  }

  @Test
  fun `单个采样点毛刺不应把峰值顶到满量程`() {
    val analyzer = SpectrumAnalyzer(sampleRate = SAMPLE_RATE, channelCount = 2)
    val chunk = ShortArray(CHUNK_FRAMES * 2)
    chunk[200] = Short.MAX_VALUE
    chunk[201] = Short.MAX_VALUE

    val frame = requireNotNull(analyzer.process(chunk, chunk.size, 100L)) { "应产出帧" }

    // 旧实现（整块最大绝对值）在这里会给出 0 dB —— 正是「峰值线钉死」的来源。
    // 准峰值看到的是一个孤立采样点落进 3ms 窗，能量被摊薄到 -21 dB 左右。
    assertTrue(
      "准峰值应远低于满量程，实际 ${frame.peakDbL} dB",
      frame.peakDbL < -15f,
    )
  }

  @Test
  fun `恒定纯音的准峰值应贴近其 RMS`() {
    val analyzer = SpectrumAnalyzer(sampleRate = SAMPLE_RATE, channelCount = 2)
    val amplitude = 0.5
    val chunk = tone(loudFrames = CHUNK_FRAMES, amplitude = amplitude)

    val frame = requireNotNull(analyzer.process(chunk, chunk.size, 100L)) { "应产出帧" }

    // 正弦波的 RMS = 幅度 / √2；各子窗 RMS 相同，所以准峰值也等于它
    val expected = db(amplitude / sqrt(2.0))
    assertEquals("整块 RMS", expected, frame.rmsDbL, 1f)
    assertEquals("准峰值", expected, frame.peakDbL, 1.5f)
  }

  @Test
  fun `半块瞬态时准峰值应明显高于整块 RMS`() {
    val analyzer = SpectrumAnalyzer(sampleRate = SAMPLE_RATE, channelCount = 2)
    val amplitude = 0.8
    val chunk = tone(loudFrames = CHUNK_FRAMES / 2, amplitude = amplitude)

    val frame = requireNotNull(analyzer.process(chunk, chunk.size, 100L)) { "应产出帧" }

    // 有声段 RMS = A/√2；整块 RMS 还要再乘 √0.5（一半时间静音）
    val loudRms = db(amplitude / sqrt(2.0))
    val wholeRms = db(amplitude / sqrt(2.0) * sqrt(0.5))

    assertEquals("整块 RMS 应被静音段拉低", wholeRms, frame.rmsDbL, 1.5f)
    assertEquals("准峰值应贴近有声段的 RMS", loudRms, frame.peakDbL, 1.5f)
    assertTrue(
      "准峰值应比整块 RMS 高出 3dB 量级，实际 ${frame.peakDbL - frame.rmsDbL} dB",
      frame.peakDbL - frame.rmsDbL > 2f,
    )
  }
}
