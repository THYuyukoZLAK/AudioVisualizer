package top.aerohaku.androidapp.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import top.aerohaku.androidapp.ui.layout.BackgroundBlur
import top.aerohaku.androidapp.ui.layout.BlurAlgorithm
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 背景：专辑封面 → 逐级缩小 → 卷积模糊 → 放大铺满 → 暗化。
 *
 * ## 为什么不是「一步缩小 + `Modifier.blur`」
 *
 * 早先的做法是把封面**一步**缩到 24×24，再用 `Modifier.blur(24.dp)` 补一次高斯。
 * 那是「背景像素感很强、完全不柔和」的根源，而且两个原因都不在尚斯本身：
 *
 * 1. ‼️ **一步缩小是欠采样**。`Bitmap.createScaledBitmap(filter = true)` 用的是双线性，
 *    一次缩 25 倍时每个目标像素只采到 2×2 个源像素（本该平均约 25×25 个）。
 *    于是 24×24 那张图本身就是一片彩色噪块 —— 后面乘多少倍、糊多少层都是把噪块放大。
 * 2. **24 像素对 2560 像素的屏幕是 100 倍以上的放大**。放大倍数越大，
 *    双线性插值的「菱形阶梯」越明显，那就是肉眼看到的方块。
 *
 * 现在改成：**逐级折半**（每步只缩 2 倍，此时双线性恰好等价于盒式平均 ⇒ 干净），
 * 缩到短边 [BASE_SHORT_SIDE] 再在缩略图上做卷积，最后放大。
 *
 * ## 为什么卷积放在缩略图上做
 *
 * 模糊与分辨率无关：先缩小再模糊再放大，观感一样，成本却只跟缩略图尺寸有关。
 * 反过来说，`Modifier.blur` 是在**全屏图层**上加一次渲染通道（还 API 31+ 才生效），
 * 对一张静态背景图来说贵得多、而且不可控。
 *
 * @param darken 暗化强度 0..1
 * @param blur   虚化配置：开关 / 算法 / 程度
 */
@Composable
fun AlbumArtBackground(
  artwork: ImageBitmap?,
  darken: Float,
  blur: BackgroundBlur,
  modifier: Modifier = Modifier,
) {
  Box(modifier) {
    when {
      artwork == null -> Box(Modifier.fillMaxSize().background(Color.Black))

      // 关掉虚化就是原图直出（只叠暗化）。
      // 「关」与「程度调到 0」是两个意思：后者仍然会经过缩略图，只是不卷积
      !blur.enabled -> ArtworkImage(artwork)

      else -> {
        val soft = remember(artwork, blur.algorithm, blur.amount) {
          artwork.softThumbnail(blur.algorithm, blur.amount)
        }
        ArtworkImage(soft)
      }
    }

    // 暗化：保证白色图形在上面始终有足够对比度
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = darken.coerceIn(0f, 1f))))
  }
}

@Composable
private fun ArtworkImage(bitmap: ImageBitmap) {
  Image(
    bitmap = bitmap,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    // 放大的是一张**本来就平滑**的图，双线性够了：
    // 双线性的插值误差与二阶导数成正比，而糊过的图几乎没有二阶导数。
    // 换成双三次只是徒增整屏 quad 的纹理采样次数。
    filterQuality = FilterQuality.Low,
    modifier = Modifier.fillMaxSize(),
  )
}

/** 缩略图短边。128 是「够平滑」与「够便宜」的折中：卷积成本与它的平方成正比 */
private const val BASE_SHORT_SIDE = 128

/** `BOX3` 的迭代次数 */
private const val BOX3_ITERATIONS = 3

/**
 * 把封面压成一张「本身就柔和」的缩略图。**在主线程上同步做**，
 * 代价按 128px 缩略图算：盒式不到 0.1ms、高斯满半径约几毫秒；
 * 而它只在「封面变了」或「用户拖模糊滑杆」时才会重算。
 */
private fun ImageBitmap.softThumbnail(algorithm: BlurAlgorithm, amount: Int): ImageBitmap {
  val source = asAndroidBitmap()
  val thumb = pyramidDownscale(BASE_SHORT_SIDE)

  // 不卷积就直接用缩略图，省掉一次像素搬运
  if (algorithm == BlurAlgorithm.DOWNSCALE || amount <= 0) return thumb.asImageBitmap()

  val width = thumb.width
  val height = thumb.height
  val pixels = IntArray(width * height)
  thumb.getPixels(pixels, 0, width, 0, 0, width, height)
  // 缩略图是我们自己分配的才回收；源图属于播放元数据，别处还在用
  if (thumb !== source) thumb.recycle()

  val sigma = sigmaForAmount(amount, min(width, height))
  when (algorithm) {
    BlurAlgorithm.GAUSSIAN -> gaussianBlur(pixels, width, height, gaussianRadius(sigma))
    BlurAlgorithm.BOX3 ->
      boxBlur(pixels, width, height, boxRadius(sigma, BOX3_ITERATIONS), BOX3_ITERATIONS)
    BlurAlgorithm.BOX1 -> boxBlur(pixels, width, height, boxRadius(sigma, 1), 1)
    BlurAlgorithm.DOWNSCALE -> Unit
  }

  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * 逐级折半把封面缩到短边 [targetShortSide]。
 *
 * ‼️ **不能一步缩到位**：双线性一次缩 25 倍只采 2×2 个源像素，严重欠采样。
 * 每步只缩 2 倍时，一个目标像素**正好**覆盖 2×2 个源像素、权重各 1/4，
 * 双线性于是等价于盒式平均，每一级都是干净的。这正是 mipmap 的生成方式。
 *
 * ⚠️ 中间产物由本函数分配、用完即回收；**源图绝不会被 recycle**。
 */
private fun ImageBitmap.pyramidDownscale(targetShortSide: Int): Bitmap {
  val raw = asAndroidBitmap()
  // 归一化成 ARGB_8888：HARDWARE 位图上 getPixels() 会抛。
  // 跨进程传过来的封面不可能是 HARDWARE，但这里只花一次 128px 的拷贝，
  // 换来「绝不会因为一张图把界面搞崩」。
  val source = if (raw.config == Bitmap.Config.ARGB_8888) {
    raw
  } else {
    runCatching { raw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: raw
  }

  var current = source
  var owned = false
  var shortSide = min(current.width, current.height)
  if (shortSide <= 0) return current

  while (shortSide > targetShortSide * 2) {
    val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
    if (owned) current.recycle()
    current = next
    owned = true
    shortSide = min(current.width, current.height)
  }

  // 收尾一步：此时缩放比在 (0.5, 1] 之间，远小于 2 倍，双线性仍然是干净的
  val scale = targetShortSide.toFloat() / shortSide
  if (scale < 1f) {
    val next = Bitmap.createScaledBitmap(
      current,
      (current.width * scale).roundToInt().coerceAtLeast(1),
      (current.height * scale).roundToInt().coerceAtLeast(1),
      true,
    )
    if (owned) current.recycle()
    current = next
  }
  return current
}
