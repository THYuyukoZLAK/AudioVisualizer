package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空心齿轮图标。
 *
 * 为什么手画而不是用现成图标：
 * - `Icons.Outlined.Settings` 在 `material-icons-extended` 里，那是十几 MB 的依赖；
 *   `material-icons-core` 只有 `Filled` 一套，画出来是实心齿轮，不是**空心**的。
 * - 手画还能和本 app 的既有风格对齐（纯白、只用描边、不做圆角）。
 *
 * 形状是一个「平顶 + 平底 + 斜齿侧」的齿轮轮廓（描边）加一个中心圆（描边），
 * 所以整体是空心的、线宽统一。
 *
 * @param color 描边颜色，默认纯白
 * @param teeth 齿数
 */
@Composable
fun GearIcon(
  modifier: Modifier = Modifier,
  color: Color = Color.White,
  teeth: Int = DEFAULT_TEETH,
) {
  Canvas(modifier) {
    drawGear(color = color, teeth = teeth)
  }
}

private const val DEFAULT_TEETH = 8

/** 描边宽度相对图标半径的比例 */
private const val STROKE_RATIO = 0.11f

/** 齿根半径 / 齿顶半径 */
private const val VALLEY_RATIO = 0.78f

/** 中心孔半径 / 齿顶半径 */
private const val HUB_RATIO = 0.40f

/**
 * 每个齿在角度上占 `[0.10, 0.90]`：前 30% 是齿顶，后 30% 是齿根，中间两段是斜齿侧。
 * 齿顶与齿根都取**两个点**，这样它们是平的而不是尖的 —— 尖的就成星星了。
 */
private const val TOOTH_TOP_START = 0.10f
private const val TOOTH_TOP_END = 0.40f
private const val VALLEY_START = 0.60f
private const val VALLEY_END = 0.90f

private fun DrawScope.drawGear(color: Color, teeth: Int) {
  if (teeth < 3 || size.minDimension <= 0f) return

  val center = Offset(size.width / 2f, size.height / 2f)
  val strokeWidth = size.minDimension / 2f * STROKE_RATIO
  // 描边是以路径为中心向两侧各画一半，所以半径先扣掉半个线宽，免得齿顶被裁掉
  val outer = size.minDimension / 2f - strokeWidth / 2f
  val valley = outer * VALLEY_RATIO
  val hub = outer * HUB_RATIO

  val step = (2.0 * PI / teeth).toFloat()
  val path = Path()

  for (i in 0 until teeth) {
    // -PI/2 让第一个齿朝正上方，齿轮看起来是「正」的
    val base = i * step - (PI / 2).toFloat()
    if (i == 0) {
      path.polarLine(center, base + step * TOOTH_TOP_START, outer, move = true)
    } else {
      path.polarLine(center, base + step * TOOTH_TOP_START, outer)
    }
    path.polarLine(center, base + step * TOOTH_TOP_END, outer)
    path.polarLine(center, base + step * VALLEY_START, valley)
    path.polarLine(center, base + step * VALLEY_END, valley)
  }
  path.close()

  drawPath(path, color, style = Stroke(width = strokeWidth, join = StrokeJoin.Miter))
  drawCircle(color, radius = hub, center = center, style = Stroke(width = strokeWidth))
}

private fun Path.polarLine(center: Offset, angle: Float, radius: Float, move: Boolean = false) {
  val x = center.x + cos(angle) * radius
  val y = center.y + sin(angle) * radius
  if (move) moveTo(x, y) else lineTo(x, y)
}
