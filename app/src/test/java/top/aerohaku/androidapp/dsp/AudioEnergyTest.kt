package top.aerohaku.androidapp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * `AudioFrame.energy` 的测试。
 *
 * 这个值不是「好看的附加信息」：它是粒子场**时间流速**的唯一输入
 * （对应 mfosu 里绑到 `Clock.Rate` 上的 `ΣFrequencyAmplitudes`），
 * 所以它的量纲、线性度、以及「瞬时 vs 衰减」的取舍都要盯住。
 */
class AudioEnergyTest {

  private companion object {
    const val SAMPLE_RATE = 44_100
    const val CHUNK_MS = 20
    const val CHUNK_FRAMES = SAMPLE_RATE * CHUNK_MS / 1000

    /** 取 bin 整数倍，矩形窗下能量精确落在这个 bin */
    const val TONE_BIN = 12
    const val TONE_HZ = TONE_BIN * SAMPLE_RATE / SpectrumAnalyzer.FFT_SIZE.toDouble()
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

  /** 喂 blocks 块音（或静音），返回最后一帧的能量 */
  private fun energyOf(amplitude: Double, blocks: Int, silentTail: Int = 0): Float {
    val analyzer = SpectrumAnalyzer(sampleRate = SAMPLE_RATE, channelCount = 2)
    var now = 0L
    var offset = 0
    var last = 0f
    repeat(blocks) {
      val chunk = toneChunk(offset, amplitude)
      offset += CHUNK_FRAMES
      now += CHUNK_MS
      analyzer.process(chunk, chunk.size, now)?.let { last = it.energy }
    }
    repeat(silentTail) {
      now += CHUNK_MS
      analyzer.process(silence, silence.size, now)?.let { last = it.energy }
    }
    return last
  }

  @Test
  fun `静音时能量恒为 0`() {
    val energy = energyOf(amplitude = 0.0, blocks = 40)
    assertEquals(0f, energy, 1e-9f)
  }

  @Test
  fun `能量应正比于音频幅度`() {
    val quarter = energyOf(amplitude = 0.25, blocks = 40)
    val half = energyOf(amplitude = 0.5, blocks = 40)

    assertTrue("半幅应有能量，实际 $half", half > 0.1f)
    assertTrue("幅度越大能量越大（$quarter → $half）", half > quarter)
    assertEquals("能量应与幅度成正比", 2f, half / quarter, 0.05f)
  }

  /**
   * 这条是它与 `spectrum` 的关键区别：
   * `spectrum` 带峰值保持，停音后要 decayMs 才回落到 0；
   * 而粒子要的是「现在有多响」，所以 energy 必须**立刻**归零。
   */
  @Test
  fun `停音后能量立即归零而不像频谱那样衰减`() {
    val justBeforeStop = energyOf(amplitude = 0.5, blocks = 30)
    val afterStop = energyOf(amplitude = 0.5, blocks = 30, silentTail = 4)

    assertTrue("停音前应有能量，实际 $justBeforeStop", justBeforeStop > 0.1f)
    assertEquals("停音后能量应立刻归零", 0f, afterStop, 1e-6f)
  }
}
