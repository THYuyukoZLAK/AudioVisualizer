package top.aerohaku.androidapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 背景虚化的像素算法。
 *
 * 这些函数**不碰任何 Android 类型**（就地操作 `IntArray`），所以能在本地 JVM 单测里跑 ——
 * 卷积这种东西靠肉眼在真机上看是没用的，得把性质钉住：
 *
 * 1. **边界按边缘延展**（不是补零）：纯色图模糊完必须**一模一样**。补零会让四周发暗，
 *    而那恰恰是「看起来像蒙了一层灰」的常见来源。
 * 2. **核非负且有界** ⇒ 不可能过冲/振铃：阶跃边缘模糊后仍单调。
 * 3. 程度 → σ → 半径的换算在三个算法之间**方差等价**，切换算法时观感不会跳。
 */
class BackgroundBlurTest {

  private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
  private fun red(px: Int) = (px ushr 16) and 0xFF
  private fun green(px: Int) = (px ushr 8) and 0xFF
  private fun blue(px: Int) = px and 0xFF

  @Test
  fun `盒式：纯色图完全不变 —— 也就验证了边界是按边缘延展的`() {
    val pixels = IntArray(8 * 8) { argb(255, 40, 80, 120) }

    boxBlur(pixels, 8, 8, radius = 3, iterations = 3)

    pixels.forEach {
      assertEquals(40, red(it))
      assertEquals(80, green(it))
      assertEquals(120, blue(it))
      assertEquals(255, (it ushr 24) and 0xFF)
    }
  }

  @Test
  fun `高斯：纯色图完全不变 —— 核归一化 + 边界延展`() {
    val pixels = IntArray(8 * 8) { argb(255, 40, 80, 120) }

    gaussianBlur(pixels, 8, 8, radius = 4)

    pixels.forEach {
      assertEquals(40, red(it))
      assertEquals(80, green(it))
      assertEquals(120, blue(it))
    }
  }

  @Test
  fun `半径 0 或非法尺寸时是恒等`() {
    val src = intArrayOf(
      argb(255, 10, 20, 30), argb(255, 200, 100, 50),
      argb(255, 7, 7, 7), argb(255, 250, 250, 250),
    )
    val before = src.copyOf()

    boxBlur(src, 2, 2, radius = 0, iterations = 3)
    gaussianBlur(src, 2, 2, radius = 0)
    boxBlur(src, 0, 0, radius = 3)
    boxBlur(src, 2, 2, radius = 3, iterations = 0)

    assertTrue(src.contentEquals(before))
  }

  @Test
  fun `把阶跃边缘抹平，且不会过冲`() {
    val n = 16
    val pixels = IntArray(n * n) { i -> argb(255, if (i % n < n / 2) 0 else 255, 0, 0) }
    val jumpBefore = red(pixels[n / 2]) - red(pixels[n / 2 - 1])

    boxBlur(pixels, n, n, radius = 3, iterations = 3)

    val jumpAfter = red(pixels[n / 2]) - red(pixels[n / 2 - 1])
    assertTrue("边缘没被抹平：$jumpBefore → $jumpAfter", jumpAfter < jumpBefore)

    // 阶跃被抹成了斜坡：原本非黑即白，现在中间那几个像素落在两者之间
    val ramp = (0 until n).count { val v = red(pixels[it]); v in 1..254 }
    assertTrue("没有出现过渡带", ramp >= 4)

    // ⚠️ 别去断言「最左/最右像素完全不变」—— 窗口宽 2r+1，最左像素的窗口仍覆盖到 x=r，
    // 它会沾到半径内的邻居（实测 0 → 3）。「边界不被拉暗」这件事由纯色图那条测试负责。

    // 单调不减 —— 核非负的必然结果，也是「没有振铃/过冲」的判据
    // （不能拿打包后的整数去 min/max：alpha=255 会把符号位带成负数）
    for (y in 0 until n) {
      for (x in 1 until n) {
        val prev = red(pixels[y * n + x - 1])
        val cur = red(pixels[y * n + x])
        assertTrue("第 $y 行 x=$x 处不再单调：$prev → $cur", cur >= prev)
      }
    }
  }

  @Test
  fun `对称的输入模糊后仍然对称`() {
    val n = 17
    val center = n / 2
    val pixels = IntArray(n * n) { i ->
      val distance = abs(i % n - center)
      argb(255, distance * 15, distance * 15, 0)
    }

    gaussianBlur(pixels, n, n, radius = 3)

    for (y in 0 until n) {
      for (x in 0..center) {
        val left = red(pixels[y * n + x])
        val right = red(pixels[y * n + (n - 1 - x)])
        // 浮点求和顺序不同，末位可能差 1
        assertTrue("第 $y 行 x=$x 不对称：$left vs $right", abs(left - right) <= 1)
      }
    }
  }

  @Test
  fun `程度 → σ：0 是 0，100 是短边的 6_25%，且单调`() {
    assertEquals(0f, sigmaForAmount(0, 128), 1e-6f)
    assertEquals(0f, sigmaForAmount(-5, 128), 1e-6f)
    assertEquals(8f, sigmaForAmount(100, 128), 1e-4f)

    var last = -1f
    for (amount in 0..100) {
      val sigma = sigmaForAmount(amount, 128)
      assertTrue("程度 $amount 处不再单调", sigma >= last)
      last = sigma
    }
  }

  @Test
  fun `σ → 半径：高斯取 3σ，盒式按方差等价`() {
    assertEquals(0, gaussianRadius(0f))
    assertEquals(30, gaussianRadius(10f))

    // 同样的模糊量，单遍盒式需要比三遍更大的半径
    assertTrue(boxRadius(4f, 1) > boxRadius(4f, 3))

    // 换算出来的半径，其实际 σ 应当接近目标 σ ——
    // 这就是「换算法时观感不会跳」的依据
    for (sigma in listOf(2f, 4f, 6f, 8f)) {
      for (iterations in listOf(1, 3)) {
        val r = boxRadius(sigma, iterations)
        // 半径 r 的盒式方差是 (r² + r) / 3，k 遍相加
        val actual = sqrt(iterations * (r * r + r) / 3f)
        val error = abs(actual - sigma) / sigma
        assertTrue(
          "σ=$sigma 迭代 $iterations 遍 → 半径 $r，实际 σ=$actual（误差 ${(error * 100).toInt()}%）",
          error < 0.25f,
        )
      }
    }
  }
}
