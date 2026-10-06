package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 顶部进度条：横跨整个屏幕，约 1~2mm 厚。
 *
 * 1mm ≈ **6.3dp**（`1mm = 160/25.4 dp`，与屏幕密度无关），
 * 所以版式稿里的「1~2mm」对应 **6.3~12.6dp**，默认取 9dp ≈ 1.4mm。
 *
 * 刻意只收原始数值（不订阅数据流）—— 这样它是纯展示组件，
 * 不会把 `ui.components` 反向依赖到 `ui.visualizer` 上。
 *
 * 传入 [onSeek] 就变成可拖动/可点击的。⚠️ 位置与时长仍然由外部推 ——
 * 拖动期间只是**本地预览**，真正落定靠 `onSeek`。
 *
 * @param onSeek 松手（或点击）时回调目标位置（ms）。传 null 则完全不可交互 ——
 *   网易云没声明 `SEEK_TO` 时就是这样：能拖却拖不动，比不能拖更让人困惑。
 */
@Composable
fun ProgressBarView(
  positionMs: Long,
  durationMs: Long,
  modifier: Modifier = Modifier,
  color: Color = VizStyle.Fill,
  onSeek: ((Long) -> Unit)? = null,
) {
  val live = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

  // 拖动中的预览位置。非空 = 用户正在拖，此时**忽略**外面推进来的实时进度，
  // 否则手指还按着，条子就被会话推回来的旧位置拽回去了
  var dragFraction by remember { mutableStateOf<Float?>(null) }
  val fraction = dragFraction ?: live

  // 手势闭包会被 pointerInput 长期持有，而 onSeek 每次重组都是新 lambda ——
  // 必须走 rememberUpdatedState，否则松手时调到的可能是旧的那个
  val seekAction by rememberUpdatedState(onSeek)
  val seekable = onSeek != null && durationMs > 0L

  Canvas(
    modifier
      .fillMaxSize()
      .then(
        if (!seekable) {
          Modifier
        } else {
          Modifier
            .pointerInput(durationMs) {
              detectHorizontalDragGestures(
                onDragStart = { offset ->
                  dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                },
                onHorizontalDrag = { change, _ ->
                  dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                },
                onDragEnd = {
                  val target = dragFraction
                  dragFraction = null
                  if (target != null) seekAction?.invoke((target * durationMs).toLong())
                },
                onDragCancel = { dragFraction = null },
              )
            }
            // 点一下也能跳：这条子只有不到 10dp 高，对拇指来说拖动并不总是好操作。
            // 两个 pointerInput 并行生效，拖动会 consume，所以拖完不会又触发一次点击
            .pointerInput(durationMs) {
              detectTapGestures { offset ->
                val target = (offset.x / size.width).coerceIn(0f, 1f)
                seekAction?.invoke((target * durationMs).toLong())
              }
            }
        },
      ),
  ) {
    // 轨道：半透明。
    // 进度条是全屏宽度的刻度，必须能在**任意亮度**的封面上读出播放位置，
    // 所以这里保留轨道 —— 它是功能性元素，不是装饰背景。
    drawRect(color = color.copy(alpha = if (dragFraction != null) 0.45f else 0.32f))
    if (fraction > 0f) {
      drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = Size(size.width * fraction, size.height),
      )
    }
  }
}
