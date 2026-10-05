package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.theme.JetBrainsMono
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.SettingsDrag
import java.util.Locale
import kotlin.math.abs

/**
 * 拖动滑杆时浮在顶部的读数 HUD：**全不透明**地显示「正在调哪一项、调到多少」。
 *
 * ## 为什么非要这么绕
 *
 * 拖动时设置页整体变透明，好让用户实时看见背后可视化界面的变化。
 * 但 `alpha` 沿父链**相乘** —— 被拖的那根滑杆没法在整页淡下去的同时单独亮回来。
 * 想做到就只能把那一行挪到被淡化的子树之外（重绘副本 / `movableContent`），
 * 坐标会漂、还会打断正在进行的拖动手势。所以改成：**信息独立浮在最上层**。
 *
 * ⚠️ 这个 HUD **绝对不能吃触摸**：不要给它挂任何 `pointerInput` / `clickable`，
 * 否则会挡住用户正在进行的拖动（Compose 里挂上触摸处理的兄弟节点会抢命中）。
 */
@Composable
fun SliderHud(modifier: Modifier = Modifier) {
  val drag by SettingsDrag.active.collectAsStateWithLifecycle()
  val info = drag ?: return

  Column(
    modifier
      .width(HUD_WIDTH)
      .background(ScexColors.Panel)
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = info.label ?: "调整中",
        color = ScexColors.Heading,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(10.dp))
      Text(
        text = formatValue(info.value),
        color = ScexColors.AccentForm,
        fontFamily = JetBrainsMono,
        fontSize = 13.sp,
      )
    }

    Spacer(Modifier.height(8.dp))

    // 滑杆本体的缩略图：轨道 + 已选段 + 拇指。纯绘制，不带任何手势。
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(HUD_TRACK_HEIGHT),
    ) {
      val span = (info.range.endInclusive - info.range.start).takeIf { it > 0f } ?: 1f
      val fraction = ((info.value - info.range.start) / span).coerceIn(0f, 1f)
      val centerY = size.height / 2f
      val trackHeight = size.height * 0.18f

      drawRect(
        color = ScexColors.Border,
        topLeft = Offset(0f, centerY - trackHeight / 2f),
        size = Size(size.width, trackHeight),
      )
      drawRect(
        color = ScexColors.AccentForm,
        topLeft = Offset(0f, centerY - trackHeight / 2f),
        size = Size(size.width * fraction, trackHeight),
      )
      drawCircle(
        color = ScexColors.Accent,
        radius = size.height * 0.44f,
        center = Offset(size.width * fraction, centerY),
      )
    }
  }
}

/** 取值粒度本来就不需要比两位小数更细；接近整数就按整数显示 */
private fun formatValue(value: Float): String =
  if (abs(value - value.toInt()) < 0.005f) "${value.toInt()}"
  else String.format(Locale.US, "%.2f", value)

private val HUD_WIDTH = 320.dp
private val HUD_TRACK_HEIGHT = 22.dp
