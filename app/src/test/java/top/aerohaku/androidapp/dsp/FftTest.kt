package top.aerohaku.androidapp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT 与频谱分析器的单元测试。
 * 两者都是纯 JVM 逻辑（时间由外部传入），所以可以直接跑。
 */
class FftTest {

  private fun binOf(freq: Double, sampleRate: Int, size: Int) = freq * size / sampleRate

  @Test
  fun `直流信号能量全部落在 0 号 bin`() {
    val size = 64
    val fft = Fft(size)
    val re = FloatArray(size) { 1f }
    val im = FloatArray(size)
    val mags = FloatArray(fft.binCount)

    fft.transform(re, im)
    fft.magnitudes(re, im, mags)

    // 全 1 输入 → 0 号 bin 归一化后应为 1，其余接近 0
    assertEquals(1f, mags[0], 1e-3f)
    for (i in 1 until fft.binCount) {
      assertTrue("bin $i 应为 0，实际 ${mags[i]}", mags[i] < 1e-3f)
    }
  }

  @Test
  fun `满量程正弦的谱峰归一化后约为 1`() {
    val size = 1024
    val fft = Fft(size)
    val re = FloatArray(size) { sin(2 * PI * 100 * it / size).toFloat() }
    val im = FloatArray(size)
    val mags = FloatArray(fft.binCount)

    fft.transform(re, im)
    fft.magnitudes(re, im, mags)

    val peak = mags.max()
    assertEquals(1f, peak, 0.02f)
  }

  @Test
  fun `1kHz 正弦落在正确的 bin 上`() {
    val sampleRate = 44_100
    val size = 1024
    val freq = 1_000.0
    val fft = Fft(size)
    val re = FloatArray(size) { sin(2 * PI * freq * it / sampleRate).toFloat() }
    val im = FloatArray(size)
    val mags = FloatArray(fft.binCount)

    fft.transform(re, im)
    fft.magnitudes(re, im, mags)

    val expectedBin = Math.round(binOf(freq, sampleRate, size)).toInt()
    val actualBin = mags.indices.maxByOrNull { mags[it] }!!
    assertEquals(expectedBin, actualBin)
  }

  @Test
  fun `两个正弦应出现两个谱峰`() {
    val sampleRate = 44_100
    val size = 1024
    // 刻意选**整数 bin 对齐**的频率：矩形窗下非整数 bin 会有扇贝损失（最多 -3.9dB），
    // 会让幅度断言变得不确定性；bin 对齐时能量精确落在该 bin，其余整数 bin 为 0。
    val binLow = 12
    val binHigh = 93
    val freqLow = binLow * sampleRate.toDouble() / size
    val freqHigh = binHigh * sampleRate.toDouble() / size

    val fft = Fft(size)
    val re = FloatArray(size) {
      (sin(2 * PI * freqLow * it / sampleRate) + sin(2 * PI * freqHigh * it / sampleRate)).toFloat() * 0.5f
    }
    val im = FloatArray(size)
    val mags = FloatArray(fft.binCount)

    fft.transform(re, im)
    fft.magnitudes(re, im, mags)

    assertTrue("bin $binLow 幅度应约 0.5，实际 ${mags[binLow]}", mags[binLow] > 0.45f)
    assertTrue("bin $binHigh 幅度应约 0.5，实际 ${mags[binHigh]}", mags[binHigh] > 0.45f)
    // 中间频段应该是安静的
    assertTrue("中间频段应为 0，实际 ${mags[(binLow + binHigh) / 2]}", mags[(binLow + binHigh) / 2] < 0.01f)
    // 峰值确实位于这两个 bin
    val loudest = mags.indices.maxByOrNull { mags[it] }!!
    assertTrue(loudest == binLow || loudest == binHigh)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `非 2 的幂长度应报错`() {
    Fft(1000)
  }

  // ------------------------------------------------------------------ 分析器

  private fun stereoChunk(
    frames: Int,
    sampleRate: Int,
    leftAmplitude: Double,
    leftFreq: Double,
    rightAmplitude: Double,
    rightFreq: Double,
    offsetFrames: Int,
  ): ShortArray {
    val out = ShortArray(frames * 2)
    for (i in 0 until frames) {
      val n = i + offsetFrames
      out[i * 2] = (sin(2 * PI * leftFreq * n / sampleRate) * leftAmplitude * 32767).toInt().toShort()
      out[i * 2 + 1] = (sin(2 * PI * rightFreq * n / sampleRate) * rightAmplitude * 32767).toInt().toShort()
    }
    return out
  }

  @Test
  fun `左声道响右声道静音时左右电平应明显不同`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50 // 20ms

    var frame: AudioFrame? = null
    var offset = 0
    repeat(40) { index ->
      val chunk = stereoChunk(chunkFrames, sampleRate, 0.9, 440.0, 0.0, 440.0, offset)
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val result = requireNotNull(frame) { "应该产出过帧" }
    assertTrue("应识别为立体声", result.stereo)
    assertTrue("左声道 RMS 应接近 -1dB，实际 ${result.rmsDbL}", result.rmsDbL > -6f)
    assertTrue("右声道应为静音，实际 ${result.rmsDbR}", result.rmsDbR <= Levels.MIN_DB + 0.01f)
  }

  @Test
  fun `单声道输入时 stereo 标记为 false 且左右电平相同`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 1)
    val chunkFrames = sampleRate / 50

    var frame: AudioFrame? = null
    var offset = 0
    repeat(40) { index ->
      val chunk = ShortArray(chunkFrames) { i ->
        (sin(2 * PI * 440.0 * (i + offset) / sampleRate) * 0.5 * 32767).toInt().toShort()
      }
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val result = requireNotNull(frame)
    assertEquals(false, result.stereo)
    assertEquals(result.rmsDbL, result.rmsDbR, 0.001f)
  }

  @Test
  fun `1kHz 正弦应落在 1kHz 所在的柱上`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50

    var frame: AudioFrame? = null
    var offset = 0
    repeat(40) { index ->
      val chunk = stereoChunk(chunkFrames, sampleRate, 0.9, 1_000.0, 0.9, 1_000.0, offset)
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val spectrum = requireNotNull(frame).spectrum
    assertEquals(SpectrumAnalyzer.DEFAULT_BAR_COUNT, spectrum.size)
    val loudestBar = spectrum.indices.maxByOrNull { spectrum[it] }!!
    // 不硬编码柱号：用 DSP 同一套公式反推，这样改柱数时测试不会碎。
    // 允许 ±2：1kHz 不是 bin 整数倍，矩形窗下会有扇贝损失把峰值带偏一格。
    val expectedBar = (0 until spectrum.size)
      .minByOrNull { kotlin.math.abs(analyzer.barFrequency(it) - 1_000f) }!!
    assertTrue(
      "1kHz 应落在柱 $expectedBar 附近，实际 $loudestBar",
      kotlin.math.abs(loudestBar - expectedBar) <= 2,
    )
  }

  @Test
  fun `波形长度正确且幅度合理`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50

    var frame: AudioFrame? = null
    var offset = 0
    repeat(40) { index ->
      val chunk = stereoChunk(chunkFrames, sampleRate, 0.8, 440.0, 0.8, 440.0, offset)
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val waveform = requireNotNull(frame).waveform
    assertEquals(SpectrumAnalyzer.WAVEFORM_POINTS, waveform.size)
    val rms = sqrt(waveform.sumOf { it.toDouble() * it } / waveform.size)
    assertTrue("波形 RMS 应在合理范围，实际 $rms", rms in 0.3..0.9)
  }

  @Test
  fun `静音时所有柱为 0`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50
    val silence = ShortArray(chunkFrames * 2)

    var frame: AudioFrame? = null
    repeat(40) { index ->
      analyzer.process(silence, silence.size, index * 20L)?.let { frame = it }
    }

    val result = requireNotNull(frame)
    assertTrue("静音时不应有柱亮起", result.spectrum.all { it == 0f })
  }

  @Test
  fun `低频相邻柱不应全部相同（插值修复平顶）`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50

    // 固定种子的伪随机噪声，保证可重复；用噪声是因为它天然在低频也有连续能量
    val random = java.util.Random(42L)
    var frame: AudioFrame? = null
    repeat(40) { index ->
      val chunk = ShortArray(chunkFrames * 2) { (random.nextInt(65536) - 32768).toShort() }
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val spectrum = requireNotNull(frame).spectrum
    // 修复前：FFT 1024 时 40~100Hz 全在 bin 1~2，前 8 根柱取值完全相同 → 平顶
    val distinct = spectrum.take(8).map { (it * 1000).toInt() }.distinct().size
    assertTrue("前 8 根低频柱应至少出现 5 种不同高度，实际 $distinct 种：${spectrum.take(8).toList()}", distinct >= 5)
  }

  @Test
  fun `线性频率轴下单音只点亮少数几根柱`() {
    val sampleRate = 44_100
    // smoothness = 0：不然沿柱方向的平滑会把峰往右摊开，测不出这个性质
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2, smoothness = 0)
    val chunkFrames = sampleRate / 50

    // mfosu 是「一根柱 = 一个 FFT bin 的原始幅度」，不做跨 bin 聚合，
    // 所以单音只会在对应位置立起一根柱，其余接近 0（而旧的对数频带实现里
    // 同一音量的单音不管落在哪个频带都应该读出接近的电平）。
    val bin = 12
    val tone = bin * sampleRate.toDouble() / SpectrumAnalyzer.FFT_SIZE

    var frame: AudioFrame? = null
    var offset = 0
    repeat(50) { index ->
      val chunk = stereoChunk(chunkFrames, sampleRate, 0.8, tone, 0.8, tone, offset)
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val spectrum = requireNotNull(frame).spectrum
    val peakIndex = spectrum.indices.maxByOrNull { spectrum[it] }!!
    assertTrue("单音应确实点亮了柱子，实际 ${spectrum[peakIndex]}", spectrum[peakIndex] > 0.5f)
    val neighbourMax = listOf(peakIndex - 1, peakIndex + 1)
      .filter { it in spectrum.indices }
      .maxOf { spectrum[it] }
    assertTrue(
      "bin $bin 的单音应只点亮柱 $peakIndex，实际邻柱最大 $neighbourMax",
      neighbourMax < 0.1f,
    )
  }

  @Test
  fun `默认参数应与 mfosu 一致`() {
    // 依据 reference/sourcecode 的 SandboxRulesetConfigManager.InitialiseDefaults：
    //   DecayB = 200 / MultiplierB = 400（画布 200px → 增益 2.0）/ SmoothnessB = 1 / BarCountB = 120
    // 以及 osu.Framework 的 BassAmplitudeProcessor（DataFlags.FFT512）与
    // MusicVisualizerDrawable.used_amplitude_count = 160。
    assertEquals(512, SpectrumAnalyzer.FFT_SIZE)
    assertEquals(160, SpectrumAnalyzer.USED_BIN_COUNT)
    assertEquals(120, SpectrumAnalyzer.DEFAULT_BAR_COUNT)
    assertEquals(200f, SpectrumAnalyzer.DEFAULT_DECAY_MS, 0f)
    // mfosu 是 400/200 = 2.0，但真机对比后确认 6.4 才与 mfosu 观感一致（BASS FFT 标度差）
    assertEquals(6.4f, SpectrumAnalyzer.DEFAULT_DISPLAY_GAIN, 0f)
    assertEquals(1, SpectrumAnalyzer.DEFAULT_SMOOTHNESS)

    val analyzer = SpectrumAnalyzer(44_100, channelCount = 2)
    // 柱 9 → bin 12 → 12×44100/512 ≈ 1034Hz
    assertEquals(1_033.6f, analyzer.barFrequency(9), 2f)
  }

  @Test
  fun `频率轴应是线性的`() {
    val sampleRate = 44_100
    val barCount = SpectrumAnalyzer.DEFAULT_BAR_COUNT
    val freq = SpectrumAnalyzer.barFrequencies(sampleRate, barCount)

    assertEquals(barCount, freq.size)
    assertEquals(0f, freq[0], 0.01f)

    // 线性轴：频率正比于柱号。因为柱→bin 是整除截断（每 3 根跳一格），
    // 单根会有最多一格的台阶，所以容差取「一格 + 一点点」。
    val binHz = sampleRate.toFloat() / SpectrumAnalyzer.FFT_SIZE
    val step = SpectrumAnalyzer.USED_BIN_COUNT.toFloat() / barCount * binHz
    for (bar in 0 until barCount) {
      assertEquals("柱 $bar 的频率应为 ${bar * step}Hz", bar * step, freq[bar], step + 0.01f)
    }

    // 末端大约到 13.6kHz —— 而不是对数轴那样铺到 16kHz 以上
    assertTrue("末端频率应在 13~14kHz，实际 ${freq.last()}", freq.last() in 13_000f..14_000f)
    // 单调递增（允许相等：柱数超过 160 时会有多根柱共用同一个 bin）
    for (bar in 1 until barCount) {
      assertTrue(
        "频率应递增：柱 ${bar - 1}=${freq[bar - 1]} vs 柱 $bar=${freq[bar]}",
        freq[bar] >= freq[bar - 1],
      )
    }
  }

  @Test
  fun `8kHz 的窄峰应立起来而不是糊成一片`() {
    val sampleRate = 44_100
    // smoothness = 0：不然平滑会把峰右摊，削弱对比
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2, smoothness = 0)
    val chunkFrames = sampleRate / 50
    // 选 bin 整数倍的频率，矩形窗下能量才精确落在该 bin
    val bin = 93
    val toneFrequency = bin * sampleRate.toDouble() / SpectrumAnalyzer.FFT_SIZE
    val random = java.util.Random(7L)

    var frame: AudioFrame? = null
    var offset = 0
    repeat(50) { index ->
      val chunk = ShortArray(chunkFrames * 2)
      for (i in 0 until chunkFrames) {
        val n = i + offset
        val tone = sin(2 * PI * toneFrequency * n / sampleRate) * 0.5
        val noise = (random.nextDouble() - 0.5) * 0.02
        val sample = ((tone + noise) * 32767).toInt().toShort()
        chunk[i * 2] = sample
        chunk[i * 2 + 1] = sample
      }
      offset += chunkFrames
      analyzer.process(chunk, chunk.size, index * 20L)?.let { frame = it }
    }

    val spectrum = requireNotNull(frame).spectrum
    val toneBar = spectrum.indices
      .minByOrNull { kotlin.math.abs(analyzer.barFrequency(it) - toneFrequency.toFloat()) }!!
    val neighbourMax = listOf(toneBar - 2, toneBar - 1, toneBar + 1, toneBar + 2)
      .filter { it in spectrum.indices }
      .maxOf { spectrum[it] }

    // 线性频率轴的窄峰天生就是尖锐的（一柱一个 bin，不做跨 bin 聚合）；
    // 这条断言用来盯住「不要退化成把相邻柱抹平的平滑包络」。
    assertTrue(
      "8kHz 窄峰应远高于相邻柱，实际 峰=${spectrum[toneBar]} 邻柱最大=$neighbourMax",
      spectrum[toneBar] - neighbourMax > 0.2f,
    )
  }

  @Test
  fun `24ms 内的重复调用不会重复产出帧`() {
    val sampleRate = 44_100
    val analyzer = SpectrumAnalyzer(sampleRate, channelCount = 2)
    val chunkFrames = sampleRate / 50
    val chunk = ShortArray(chunkFrames * 2)

    val first = analyzer.process(chunk, chunk.size, 0L)
    val second = analyzer.process(chunk, chunk.size, 10L)
    val third = analyzer.process(chunk, chunk.size, 30L)

    assertTrue("首帧应立即产出", first != null)
    assertEquals("10ms 后不应再产出", null, second)
    assertTrue("30ms 后应产出", third != null)
  }
}
