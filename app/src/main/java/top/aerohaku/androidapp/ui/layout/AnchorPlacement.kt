package top.aerohaku.androidapp.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 屏幕边框上的九个定位点。
 *
 * 设计意图：部件位置**只与屏幕边框有关**，与具体分辨率/横竖屏无关 ——
 * 这样同一套配置在任何设备上都能保持「左上角的东西还在左上角」。
 */
enum class AnchorPoint(val label: String) {
  TOP_LEFT("左上"),
  TOP_CENTER("上中"),
  TOP_RIGHT("右上"),
  CENTER_LEFT("左中"),
  CENTER("正中"),
  CENTER_RIGHT("右中"),
  BOTTOM_LEFT("左下"),
  BOTTOM_CENTER("下中"),
  BOTTOM_RIGHT("右下"),
  ;

  /** 0 = 屏幕左/上边缘，0.5 = 屏幕中线，1 = 屏幕右/下边缘 */
  val screenX: Float
    get() = when (this) {
      TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> 0f
      TOP_CENTER, CENTER, BOTTOM_CENTER -> 0.5f
      TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> 1f
    }

  val screenY: Float
    get() = when (this) {
      TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 0f
      CENTER_LEFT, CENTER, CENTER_RIGHT -> 0.5f
      BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> 1f
    }

  /** 部件自身用哪个位置去对齐定位点：0 = 左/上边，0.5 = 中心，1 = 右/下边 */
  val alignX: Float get() = screenX

  val alignY: Float get() = screenY
}

/**
 * 一个部件的位置 = **定位点 + 相对该点的偏移**。
 *
 * 语义：把部件的 [AnchorPoint.alignX]/[alignY] 那一侧，放到「屏幕定位点 + 偏移」处。
 * 例：`BOTTOM_LEFT` + `(64dp, -48dp)` → 部件左边缘距屏幕左边 64dp、下边缘距屏幕底边 48dp。
 */
data class AnchorPlacement(
  val anchor: AnchorPoint = AnchorPoint.TOP_LEFT,
  val offsetX: Dp = 0.dp,
  val offsetY: Dp = 0.dp,
)

/**
 * 把每个子部件按各自的 [AnchorPlacement] 摆进容器。
 *
 * ⚠️ `placements` 的顺序必须与 `content` 里子部件的顺序**严格一致**。
 * 子部件自己通过 `Modifier.size(...)` / `fillMaxWidth()` 决定尺寸，本布局只负责摆位置。
 */
@Composable
fun AnchoredLayout(
  placements: List<AnchorPlacement>,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Layout(content, modifier) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight

    // ⚠️ 关键：必须把 min 设为 0。
    // 外层是 `Modifier.fillMaxSize()` 时传进来的约束是**固定**的（min == max），
    // 直接透传会让每个子部件都被强制撑满整个容器 ——
    // `Modifier.width(120.dp)` 会被 clamp 成容器宽度，专辑封面就会盖住全屏。
    val childConstraints = Constraints(
      minWidth = 0,
      minHeight = 0,
      maxWidth = width,
      maxHeight = height,
    )
    val placeables = measurables.map { it.measure(childConstraints) }

    layout(width, height) {
      placeables.forEachIndexed { index, placeable ->
        val placement = placements.getOrElse(index) { AnchorPlacement() }
        val dx = placement.offsetX.roundToPx()
        val dy = placement.offsetY.roundToPx()

        val x = width * placement.anchor.screenX + dx - placeable.width * placement.anchor.alignX
        val y = height * placement.anchor.screenY + dy - placeable.height * placement.anchor.alignY

        placeable.place(x.roundToInt(), y.roundToInt())
      }
    }
  }
}

/** 供设置页把「定位点 + 偏移」显示成一句人话 */
fun AnchorPlacement.describe(): String = buildString {
  append(anchor.label)
  if (offsetX.value != 0f || offsetY.value != 0f) {
    append(" (")
    append(if (offsetX.value >= 0) "+" else "")
    append(offsetX.value.toInt())
    append(", ")
    append(if (offsetY.value >= 0) "+" else "")
    append(offsetY.value.toInt())
    append(")")
  }
}
