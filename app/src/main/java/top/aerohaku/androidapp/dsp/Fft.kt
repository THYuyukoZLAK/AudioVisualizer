package top.aerohaku.androidapp.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 迭代式 radix-2 Cooley-Tukey FFT（自己实现，不引库）。
 *
 * 纯 JVM 逻辑，可直接写单元测试 —— 见 `FftTest`。
 * 表在构造时预计算，`transform` 全部原地操作、零分配，可以放心按 50fps 调用。
 */
class Fft(val size: Int) {

  init {
    require(size >= 2 && (size and (size - 1)) == 0) { "size 必须是 2 的幂，当前 = $size" }
  }

  private val levels = Integer.numberOfTrailingZeros(size)
  private val cosTable = FloatArray(size / 2) { cos(2.0 * Math.PI * it / size).toFloat() }
  private val sinTable = FloatArray(size / 2) { sin(2.0 * Math.PI * it / size).toFloat() }
  private val reverseTable = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

  /**
   * 原地复数 FFT。`re` / `im` 长度都必须等于 [size]。
   * 调用前把虚部清零即为实数输入。
   */
  fun transform(re: FloatArray, im: FloatArray) {
    require(re.size == size && im.size == size) { "数组长度必须等于 $size" }

    // 1) 位反转置换
    for (i in 0 until size) {
      val j = reverseTable[i]
      if (j > i) {
        var t = re[i]; re[i] = re[j]; re[j] = t
        t = im[i]; im[i] = im[j]; im[j] = t
      }
    }

    // 2) 蝶形运算
    var span = 2
    while (span <= size) {
      val half = span / 2
      val tableStep = size / span
      var blockStart = 0
      while (blockStart < size) {
        var j = blockStart
        var k = 0
        while (j < blockStart + half) {
          val l = j + half
          val tre = re[l] * cosTable[k] + im[l] * sinTable[k]
          val tim = -re[l] * sinTable[k] + im[l] * cosTable[k]
          re[l] = re[j] - tre
          im[l] = im[j] - tim
          re[j] += tre
          im[j] += tim
          j++
          k += tableStep
        }
        blockStart += span
      }
      if (span == size) break
      span *= 2
    }
  }

  /**
   * 幅度谱，写入 `out` 的前 [binCount] 个元素。
   *
   * 归一化：实数正弦波幅度 A 对应 bin 幅度 `A * size / 2`，
   * 因此乘 `2 / size` 后满量程正弦的谱值约为 1。
   * 例外：直流（bin 0）与 Nyquist（bin size/2）不会分摊到负频率，系数应取 `1 / size`。
   */
  fun magnitudes(re: FloatArray, im: FloatArray, out: FloatArray) {
    val bins = binCount
    val sinusoidScale = 2f / size
    val singleSidedScale = 1f / size
    for (i in 0 until bins) {
      val r = re[i]
      val m = im[i]
      val scale = if (i == 0 || i == size / 2) singleSidedScale else sinusoidScale
      out[i] = sqrt(r * r + m * m) * scale
    }
  }

  /** 有效 bin 数（含直流），即 size/2 + 1 */
  val binCount: Int get() = size / 2 + 1
}
