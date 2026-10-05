package top.aerohaku.androidapp.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 背景：专辑封面铺满 → 高斯模糊 → 30% 暗化。
 *
 * **模糊用两步做，为的是兼容 minSdk 29**：
 * 1. 先把封面降到很短边 [DOWNSCALE_SHORT_SIDE] 像素，再用双线性放大铺满 ——
 *    这一步在所有 API 上都是「免费」的模糊，也是主力
 * 2. 叠加 `Modifier.blur`（API 31+ 才真正生效）补一次真高斯
 *
 * 只做第 2 步的话，在 Android 10/11 上会得到一张**没有任何模糊**的糊脸封面。
 *
 * @param darken 暗化强度 0..1，由 `VisualizerAppearanceStore` 配置
 */
@Composable
fun AlbumArtBackground(
  artwork: ImageBitmap?,
  darken: Float,
  modifier: Modifier = Modifier,
) {
  Box(modifier) {
    if (artwork == null) {
      Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
      val blurred = remember(artwork) { artwork.downscaled(DOWNSCALE_SHORT_SIDE) }
      Image(
        bitmap = blurred,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
        modifier = Modifier.fillMaxSize().blur(BLUR_RADIUS_DP.dp),
      )
    }

    // 暗化：保证白色图形在上面始终有足够对比度
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = darken.coerceIn(0f, 1f))))
  }
}

/** 短边降到这个像素数；再小就会糊成一团色块 */
private const val DOWNSCALE_SHORT_SIDE = 24

/** API 31+ 上再补一次真高斯 */
private const val BLUR_RADIUS_DP = 24

/**
 * 等比缩小 [targetShortSide]，得到一个「本身就是模糊的」小图。
 * ⚠️ 不改动原图（`createScaledBitmap` 会分配新 Bitmap），因为 [this] 可能被别处复用（比如封面小图）。
 */
private fun ImageBitmap.downscaled(targetShortSide: Int): ImageBitmap {
  val source = asAndroidBitmap()
  val shortSide = min(source.width, source.height)
  if (shortSide <= targetShortSide || shortSide <= 0) return this

  val scale = targetShortSide.toFloat() / shortSide
  val width = (source.width * scale).roundToInt().coerceAtLeast(1)
  val height = (source.height * scale).roundToInt().coerceAtLeast(1)
  return Bitmap.createScaledBitmap(source, width, height, true).asImageBitmap()
}
