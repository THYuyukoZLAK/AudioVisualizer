package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import top.aerohaku.androidapp.dsp.AudioFrame

/**
 * 波形图 —— 上下两条白线标明边界，中间一条白色折线。
 *
 * 版式稿要求「除波形图上下有两条白线标明边界外，没有边框」。所以：
 * 原来那条**中轴线已去掉**（它会和数据线混在一起），改为上下两条边界线来界定幅度范围。
 * 折线上限被压到边界线内侧，波形不会压住边界。
 *
 * ⚠️ 自己订阅 [frames]，避免每帧把整页拖进重组。
 */
@Composable
fun WaveformView(
  frames: StateFlow<AudioFrame>,
  modifier: Modifier = Modifier,
) {
  val frame by frames.collectAsStateWithLifecycle()
  val waveform = frame.waveform

  Canvas(modifier.fillMaxSize()) {
    val lineWidth = VizStyle.LINE_DP.dp.toPx()

    // 上下边界白线
    drawRect(VizStyle.Fill, Offset.Zero, Size(size.width, lineWidth))
    drawRect(VizStyle.Fill, Offset(0f, size.height - lineWidth), Size(size.width, lineWidth))

    if (waveform.size < 2) return@Canvas

    val middle = size.height / 2f
    // 留出边界线本身的厚度，避免折线贴上边界后糊成一条
    val amplitude = (middle - lineWidth * 2f).coerceAtLeast(1f)
    val step = size.width / (waveform.size - 1f)

    val path = Path()
    waveform.forEachIndexed { index, value ->
      val x = index * step
      val y = middle - value * amplitude
      if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(path = path, color = VizStyle.Fill, style = Stroke(width = lineWidth))
  }
}
