package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 顶部进度条：横跨整个屏幕，约 1~2mm 厚。
 *
 * 1mm ≈ **6.3dp**（`1mm = 160/25.4 dp`，与屏幕密度无关），
 * 所以版式稿里的「1~2mm」对应 **6.3~12.6dp**，默认取 9dp ≈ 1.4mm。
 *
 * 刻意只收原始数值（不订阅数据流）—— 这样它是纯展示组件，
 * 不会把 `ui.components` 反向依赖到 `ui.visualizer` 上。
 */
@Composable
fun ProgressBarView(
  positionMs: Long,
  durationMs: Long,
  modifier: Modifier = Modifier,
) {
  val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

  Canvas(modifier.fillMaxSize()) {
    // 轨道：半透明白。
    // 进度条是全屏宽度的刻度，必须能在**任意亮度**的封面上读出播放位置，
    // 所以这里保留轨道 —— 它是功能性元素，不是装饰背景。
    drawRect(color = VizStyle.Fill.copy(alpha = 0.32f))
    if (fraction > 0f) {
      drawRect(
        color = VizStyle.Fill,
        topLeft = Offset.Zero,
        size = Size(size.width * fraction, size.height),
      )
    }
  }
}
