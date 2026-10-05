package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import top.aerohaku.androidapp.dsp.AudioFrame
import top.aerohaku.androidapp.dsp.SpectrumAnalyzer

/**
 * 频谱图 —— 纯白实心柱 + 底部一条白线作基座，无圆角、无边框、无背景。
 *
 * 数据侧仍是 mfosu 模型（见 [SpectrumAnalyzer]）；这里只负责画。
 *
 * ## 关于标尺（[showScale]）
 *
 * 刻度用**相对满度的百分比**，**刻意不画 dB 刻度** —— 频谱的纵轴是线性幅度
 * （mfosu 的模型就是这样，见 `SpectrumAnalyzer.DEFAULT_DISPLAY_GAIN`），
 * 在线性轴上标 dB 会让人误以为纵轴是对数的，属于误导。
 * 需要 dB 读数的地方是电平表，那边才是真 dB
 * （`VizStyle.METER_FLOOR_DB`..`METER_CEIL_DB`，即 -60..+6）。
 *
 * 默认关闭：版式稿要求「无边框、无背景」，标尺本身也是线条。
 *
 * ⚠️ 自己订阅 [frames]，避免每帧把整页拖进重组。
 */
@Composable
fun SpectrumView(
  frames: StateFlow<AudioFrame>,
  modifier: Modifier = Modifier,
  showScale: Boolean = false,
) {
  val frame by frames.collectAsStateWithLifecycle()
  val spectrum = frame.spectrum

  // 文本测量放在 draw 之外：每帧重新测量就白费了
  val measurer = rememberTextMeasurer()
  val scaleLabels = remember(measurer) {
    SCALE_FRACTIONS.map { fraction ->
      fraction to measurer.measure(
        text = AnnotatedString("${(fraction * 100).toInt()}%"),
        style = TextStyle(
          color = VizStyle.Fill.copy(alpha = 0.9f),
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          shadow = VizStyle.TextShadow,
        ),
      )
    }
  }

  Canvas(modifier.fillMaxSize()) {
    val baseline = VizStyle.LINE_DP.dp.toPx()
    val plotBottom = (size.height - baseline).coerceAtLeast(1f)

    // 1) 先铺网格线 —— 必须在柱子之前，柱子才压得住它
    if (showScale) {
      scaleLabels.forEach { (fraction, _) ->
        val y = plotBottom - plotBottom * fraction
        drawRect(
          color = VizStyle.Fill.copy(alpha = SCALE_LINE_ALPHA),
          topLeft = Offset(0f, (y - SCALE_LINE_DP.dp.toPx() / 2f).coerceAtLeast(0f)),
          size = Size(size.width, SCALE_LINE_DP.dp.toPx()),
        )
      }
    }

    val count = if (spectrum.isEmpty()) PLACEHOLDER_BARS else spectrum.size
    val slot = size.width / count
    val barWidth = (slot * BAR_WIDTH_RATIO).coerceAtLeast(1f)

    for (index in 0 until count) {
      val value = spectrum.getOrElse(index) { 0f }
      if (value <= 0f) continue
      val height = value * plotBottom
      // 亚像素高度画出来会是一条灰线，不如直接不画
      if (height < 0.75f) continue
      drawRect(
        color = VizStyle.Fill,
        topLeft = Offset(index * slot + (slot - barWidth) / 2f, plotBottom - height),
        size = Size(barWidth, height),
      )
    }

    // 2) 基座：横跨整个频谱宽度的一条白线
    drawRect(
      color = VizStyle.Fill,
      topLeft = Offset(0f, size.height - baseline),
      size = Size(size.width, baseline),
    )

    // 3) 标尺数值最后画，压在柱子之上才读得到。
    //    靠右上角对齐，免得压住左侧那排低频柱。
    if (showScale) {
      scaleLabels.forEach { (fraction, layout) ->
        val y = plotBottom - plotBottom * fraction
        drawText(
          textLayoutResult = layout,
          topLeft = Offset(
            x = (size.width - layout.size.width - LABEL_MARGIN_DP.dp.toPx()).coerceAtLeast(0f),
            y = (y - layout.size.height).coerceAtLeast(0f),
          ),
        )
      }
    }
  }
}

/** 还没拿到数据时占位的柱子数（与 DSP 默认柱数一致） */
private const val PLACEHOLDER_BARS = SpectrumAnalyzer.DEFAULT_BAR_COUNT

/** 每格宽度里柱子实际占的比例（其余是间隙）—— 与 mfosu 的 3px 柱 / 4.18px 间隔一致 */
private const val BAR_WIDTH_RATIO = 0.7f

/** 标尺刻度所在的高度比例（相对满度） */
private val SCALE_FRACTIONS = listOf(0.25f, 0.5f, 0.75f, 1f)

private const val SCALE_LINE_ALPHA = 0.22f
private const val SCALE_LINE_DP = 1f
private const val LABEL_MARGIN_DP = 4f
