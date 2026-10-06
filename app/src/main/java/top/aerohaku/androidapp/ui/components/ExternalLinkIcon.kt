package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 「会在应用外部打开」的小图标：一个缺了右上角的方框 + 一支射出去的箭头。
 *
 * 为什么要手画：`Icons.Outlined.OpenInNew` 在 `material-icons-extended` 里（十几 MB 的依赖），
 * 而 `material-icons-core` 只有实心一套。手画还能和 [GearIcon] 保持同一套风格 ——
 * 只用描边、不做圆角、线宽相对尺寸恒定。
 *
 * 它只用在「GitHub 仓库」这类**跳出去**的按钮前面；按钮文字本身不必再写「（外部链接）」。
 */
@Composable
fun ExternalLinkIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
  Canvas(modifier) {
    drawExternalLink(color)
  }
}

/** 线宽相对图标边长 */
private const val STROKE_RATIO = 0.11f

private fun DrawScope.drawExternalLink(color: Color) {
  val side = size.minDimension
  if (side <= 0f) return

  val strokeWidth = side * STROKE_RATIO
  val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
  // 形状按 0..1 的归一化坐标设计，再乘边长 —— 换尺寸时比例不变
  fun at(fraction: Float) = fraction * side

  // 容器：一个缺了右上角的方框（缺口正好让箭头「出去」）
  val container = Path().apply {
    moveTo(at(0.58f), at(0.16f))
    lineTo(at(0.16f), at(0.16f))
    lineTo(at(0.16f), at(0.84f))
    lineTo(at(0.84f), at(0.84f))
    lineTo(at(0.84f), at(0.42f))
  }
  drawPath(container, color, style = stroke)

  // 箭头：从框内斜着指向右上角外面
  drawLine(
    color = color,
    start = Offset(at(0.42f), at(0.58f)),
    end = Offset(at(0.82f), at(0.18f)),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  // 箭头尖：两条短线折成「⌐」
  val head = Path().apply {
    moveTo(at(0.54f), at(0.16f))
    lineTo(at(0.84f), at(0.16f))
    lineTo(at(0.84f), at(0.46f))
  }
  drawPath(head, color, style = stroke)
}
