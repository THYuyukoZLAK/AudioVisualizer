package top.aerohaku.androidapp.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Visualizer` 给的 8 位数据 → `SpectrumAnalyzer` 认的 PCM16。
 *
 * 这步转换看着一眼就对，但**中位偏置**很容易写错：AOSP 往捕获缓冲里写的时候做了
 * `buf[captIdx] = smp ^ 0x80`，所以 8 位数据里的 **128 才是零点**，不是 0。
 * 搞错的话整条波形会上移 128 个量化级 —— 表现为「频谱糊成一团、
 * 电平表永远贴满格」，但代码里怎么看都没毛病。
 */
class SystemMixCaptureTest {

  private fun convert(vararg bytes: Int): ShortArray {
    val src = ByteArray(bytes.size) { bytes[it].toByte() }
    val dst = ShortArray(bytes.size)
    u8ToS16(src, dst)
    return dst
  }

  @Test
  fun `中位 0x80 映射到 0`() {
    assertEquals(0, convert(0x80)[0].toInt())
  }

  @Test
  fun `两端分别映射到 int16 的两端`() {
    assertEquals(-32768, convert(0x00)[0].toInt())
    assertEquals(32512, convert(0xFF)[0].toInt())
  }

  @Test
  fun `恒定中位就是静音`() {
    convert(0x80, 0x80, 0x80, 0x80).forEach { assertEquals(0, it.toInt()) }
  }

  @Test
  fun `取值随无符号字节严格递增`() {
    var previous = Int.MIN_VALUE
    for (b in 0..255) {
      val value = convert(b)[0].toInt()
      assertTrue("第 $b 个不该比前一个小", value > previous)
      previous = value
    }
  }

  @Test
  fun `只转换前 count 个，其余保持原样`() {
    val src = byteArrayOf(0, 0, 0)
    val dst = ShortArray(3) { 7 }

    u8ToS16(src, dst, count = 1)

    assertEquals(-32768, dst[0].toInt())
    assertEquals(7, dst[1].toInt())
    assertEquals(7, dst[2].toInt())
  }
}
