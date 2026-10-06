package top.aerohaku.androidapp.ui.components

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 背景虚化用到的像素算法。
 *
 * ## 为什么全都在**缩略图**上做
 *
 * 模糊是**与分辨率无关**的：先缩到短边一两百像素，模糊完再放大回全屏，
 * 观感一样，成本却只跟缩略图尺寸有关。反过来若在全屏尺寸（1600×2560 = 410 万像素）
 * 上做卷积，光一遍横向扫描就是几十毫秒，只能上 GPU。
 *
 * ## 为什么不用 `Modifier.blur`
 *
 * Compose 的 `Modifier.blur` 走 `RenderEffect`，是**全屏图层**上的一次额外渲染通道，
 * 而且是 API 31+ 才真正生效。对「一张静态缩略图铺满屏」这种需求来说，
 * 它贵得多、还不可控。这里改成在缩略图上做 CPU 卷积，`SpectrumAnalyzer` 那套
 * 一行没动，而且结果是一张普通位图 —— 绘制路径上零额外开销。
 *
 * ## 三个算法都在 `IntArray` 上就地/半就地工作
 *
 * 不碰任何 Android 类型，所以**可以在本地 JVM 单测里验证**（见 `BackgroundBlurTest`）。
 */

/**
 * 盒式（均值）模糊，[iterations] 遍。
 *
 * 单遍盒式的频响是 sinc，有旁瓣，观感上会留下一点方向性的方形结构；
 * **连做三遍**之后按中心极限定理已经非常接近高斯 —— 这就是图像处理里
 * 「三遍盒子 ≈ 高斯」的由来，而它仍然是最快的（滑动窗口，复杂度与半径无关）。
 *
 * 边界按**边缘延展**处理（不是补零）：补零会让画面四周发暗，那是最常见的糊弄法。
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, iterations: Int = 1) {
  if (radius <= 0 || width <= 0 || height <= 0 || iterations <= 0) return
  if (pixels.size < width * height) return

  val scratch = IntArray(pixels.size)
  repeat(iterations) {
    boxRow(pixels, scratch, width, height, radius)
    boxColumn(scratch, pixels, width, height, radius)
  }
}

/**
 * 可分离高斯模糊：核长 `2×radius+1`，取 `σ = radius/3`。
 *
 * 「可分离」= 先横着一遍、再竖着一遍，两遍一维卷积就等价于一遍二维高斯
 * （因为高斯核本身可分离），复杂度从 `O(n·r²)` 降到 `O(n·r)`。
 */
internal fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
  if (radius <= 0 || width <= 0 || height <= 0) return
  if (pixels.size < width * height) return

  val sigma = radius / 3f
  val kernel = FloatArray(radius * 2 + 1)
  var total = 0f
  for (i in -radius..radius) {
    val v = exp(-(i * i) / (2f * sigma * sigma))
    kernel[i + radius] = v
    total += v
  }
  // 归一化：不归一化的话整体会变亮（或变暗）
  for (i in kernel.indices) kernel[i] /= total

  val scratch = IntArray(pixels.size)
  gaussianRow(pixels, scratch, width, height, kernel, radius)
  gaussianColumn(scratch, pixels, width, height, kernel, radius)
}

// ------------------------------------------------------------------ 一维卷积

/** 横向盒式，结果写入 [dst]。窗口长度为 `2×radius+1`，越界处按边缘像素延展 */
private fun boxRow(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
  val window = 2 * radius + 1
  for (y in 0 until height) {
    val row = y * width
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val px = src[row + i.coerceIn(0, width - 1)]
      a += (px ushr 24) and 0xFF
      r += (px ushr 16) and 0xFF
      g += (px ushr 8) and 0xFF
      b += px and 0xFF
    }
    for (x in 0 until width) {
      dst[row + x] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
      // 滑动：右边进一个、左边出一个 —— 所以每像素只做一次加法，与半径无关
      val leaving = src[row + (x - radius).coerceIn(0, width - 1)]
      val entering = src[row + (x + radius + 1).coerceIn(0, width - 1)]
      a += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
      r += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
      g += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
      b += (entering and 0xFF) - (leaving and 0xFF)
    }
  }
}

/** 纵向盒式。与 [boxRow] 同构，只是步长换成 width */
private fun boxColumn(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
  val window = 2 * radius + 1
  for (x in 0 until width) {
    var a = 0
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val px = src[i.coerceIn(0, height - 1) * width + x]
      a += (px ushr 24) and 0xFF
      r += (px ushr 16) and 0xFF
      g += (px ushr 8) and 0xFF
      b += px and 0xFF
    }
    for (y in 0 until height) {
      dst[y * width + x] =
        (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
      val leaving = src[(y - radius).coerceIn(0, height - 1) * width + x]
      val entering = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
      a += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
      r += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
      g += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
      b += (entering and 0xFF) - (leaving and 0xFF)
    }
  }
}

private fun gaussianRow(
  src: IntArray,
  dst: IntArray,
  width: Int,
  height: Int,
  kernel: FloatArray,
  radius: Int,
) {
  for (y in 0 until height) {
    val row = y * width
    for (x in 0 until width) {
      var a = 0f
      var r = 0f
      var g = 0f
      var b = 0f
      for (k in -radius..radius) {
        val px = src[row + (x + k).coerceIn(0, width - 1)]
        val w = kernel[k + radius]
        a += ((px ushr 24) and 0xFF) * w
        r += ((px ushr 16) and 0xFF) * w
        g += ((px ushr 8) and 0xFF) * w
        b += (px and 0xFF) * w
      }
      dst[row + x] = pack(a, r, g, b)
    }
  }
}

private fun gaussianColumn(
  src: IntArray,
  dst: IntArray,
  width: Int,
  height: Int,
  kernel: FloatArray,
  radius: Int,
) {
  for (x in 0 until width) {
    for (y in 0 until height) {
      var a = 0f
      var r = 0f
      var g = 0f
      var b = 0f
      for (k in -radius..radius) {
        val px = src[(y + k).coerceIn(0, height - 1) * width + x]
        val w = kernel[k + radius]
        a += ((px ushr 24) and 0xFF) * w
        r += ((px ushr 16) and 0xFF) * w
        g += ((px ushr 8) and 0xFF) * w
        b += (px and 0xFF) * w
      }
      dst[y * width + x] = pack(a, r, g, b)
    }
  }
}

/** 四个通道各自取整并夹到 0..255。卷积不会过冲，夹取只是防御浮点误差 */
private fun pack(a: Float, r: Float, g: Float, b: Float): Int =
  (clampByte(a) shl 24) or (clampByte(r) shl 16) or (clampByte(g) shl 8) or clampByte(b)

private fun clampByte(v: Float): Int = v.roundToInt().coerceIn(0, 255)

// ------------------------------------------------------------------ 「程度」→ 半径

/**
 * 「模糊程度」0..100 → 目标 σ（单位：**缩略图像素**）。
 *
 * 用 σ 而不是半径当作中间量，是因为三个算法达到同样观感所需的半径差很多
 * （盒式要更大的半径才能顶得上高斯）。换算到同一个 σ 之后，切换算法时观感是连续的。
 */
internal fun sigmaForAmount(amount: Int, shortSide: Int): Float {
  if (amount <= 0 || shortSide <= 0) return 0f
  val ratio = amount.coerceIn(0, 100) / 100f
  return ratio * shortSide * MAX_SIGMA_RATIO
}

/**
 * 等价于目标 σ 的高斯半径（`3σ` 截断，此时核外的权重 < 0.3%）。
 */
internal fun gaussianRadius(sigma: Float): Int =
  if (sigma <= 0f) 0 else ceil(3f * sigma).toInt()

/**
 * 等价于目标 σ 的**盒式**半径。
 *
 * 半径 r 的盒式方差是 `(r² + r) / 3`，连做 [iterations] 遍方差相加，
 * 令它等于 σ² 解一元二次方程即得。这样 `迭代次数 × 半径` 不同的两个算法
 * 在同一个「程度」下会给出同样的模糊量。
 */
internal fun boxRadius(sigma: Float, iterations: Int): Int {
  if (sigma <= 0f || iterations <= 0) return 0
  val target = 3f * sigma * sigma / iterations
  return ((-1f + sqrt(1f + 4f * target)) / 2f).roundToInt().coerceAtLeast(0)
}

/** σ 的上限：占缩略图短边的比例。0.0625 在 128px 缩略图上约等于 σ=8，已经非常糊了 */
internal const val MAX_SIGMA_RATIO = 0.0625f
