package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import top.aerohaku.androidapp.dsp.AudioFrame
import java.util.Locale

/**
 * 左右声道电平：实心白柱 + 峰值保持线 + dB 数值。
 *
 * 版式稿要求「没有边框、没有背景」，所以：
 * - 原来那些刻度竖线全部去掉了 —— 保留的是**柱形 + dB 数值标识**这两样真正承载信息的元素
 * - **连轨道底板也不画**：空电平就该是空的，靠右侧 dB 数值表达量程
 *
 * ⚠️ 峰值线画在柱子之后但**与柱子同色**，如果 alpha 也相同就会糊在一起看不见。
 * 所以 RMS 柱用 88% 白、峰值线用 100% 白 —— 仍是「纯白图形」，只是靠透明度拉开层次。
 *
 * ## 关于标尺（[showScale]）
 *
 * 这里画的 dB 刻度是**真 dB**（量程 `VizStyle.METER_FLOOR_DB`..0），和频谱图那边
 * 刻意用百分比不同 —— 电平表本来就是 dB 域的，标 dB 才有意义。
 * 默认关闭：版式稿要求「无边框、无背景」，标尺本身也是线条。
 */
@Composable
fun LevelMeterView(
  frames: StateFlow<AudioFrame>,
  modifier: Modifier = Modifier,
  showScale: Boolean = false,
) {
  val frame by frames.collectAsStateWithLifecycle()

  // 两行平分部件高度；用 weight 而不是 fillMaxHeight —— 后者的高度上限是整个部件，
  // 两行叠加会溢出容器
  Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    MeterRow("L", frame.rmsDbL, frame.peakDbL, showScale, Modifier.weight(1f))
    MeterRow("R", frame.rmsDbR, frame.peakDbR, showScale, Modifier.weight(1f))
  }
}

@Composable
private fun MeterRow(
  label: String,
  rmsDb: Float,
  peakDb: Float,
  showScale: Boolean,
  modifier: Modifier = Modifier,
) {
  // 文本测量放在 draw 之外：每帧重新测量就白费了
  val measurer = rememberTextMeasurer()
  val ticks = remember(measurer, showScale) {
    if (!showScale) {
      emptyList()
    } else {
      METER_TICKS_DB.map { db ->
        db to measurer.measure(
          text = AnnotatedString("$db"),
          style = TextStyle(
            color = VizStyle.Fill.copy(alpha = 0.9f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            shadow = VizStyle.TextShadow,
          ),
        )
      }
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = label,
      modifier = Modifier.width(LABEL_WIDTH_DP.dp),
      color = VizStyle.Fill,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      style = TextStyle(shadow = VizStyle.TextShadow),
    )

    Canvas(
      Modifier
        .weight(1f)
        .fillMaxHeight(),
    ) {
      // 刻意**不画轨道/底板** —— 版式稿要求部件没有背景，
      // 量程范围由右侧的 dB 数值表达。这和频谱柱的处理保持一致：
      // 没有电平就是空的，而不是一堆半透明方块。
      //
      // 柱子只占行高的一部分并垂直居中：铺满整行的话，电平一高就变成一大块白方块，
      // 既看不出是「柱状图」，视觉上又像又加了一层底板。
      val barHeight = (size.height * BAR_HEIGHT_RATIO).coerceAtMost(BAR_MAX_HEIGHT_DP.dp.toPx())
      val barTop = (size.height - barHeight) / 2f

      // 标尺网格线先画，让柱子压在上面
      ticks.forEach { (db, _) ->
        val x = VizStyle.meterFraction(db.toFloat()) * size.width
        drawRect(
          color = VizStyle.Fill.copy(alpha = SCALE_LINE_ALPHA),
          topLeft = Offset((x - SCALE_LINE_WIDTH / 2f).coerceIn(0f, size.width - SCALE_LINE_WIDTH), 0f),
          size = Size(SCALE_LINE_WIDTH, size.height),
        )
      }

      val rmsWidth = VizStyle.meterFraction(rmsDb) * size.width
      if (rmsWidth > 0.5f) {
        drawRect(
          color = VizStyle.Fill.copy(alpha = RMS_ALPHA),
          topLeft = Offset(0f, barTop),
          size = Size(rmsWidth, barHeight),
        )
      }

      val peakX = VizStyle.meterFraction(peakDb) * size.width
      if (peakX > 1f) {
        drawRect(
          color = VizStyle.Fill,
          topLeft = Offset((peakX - PEAK_LINE_WIDTH).coerceIn(0f, size.width - PEAK_LINE_WIDTH), barTop),
          size = Size(PEAK_LINE_WIDTH, barHeight),
        )
      }

      // 刻度数值最后画，压在柱子上才读得到；贴着画布底部对齐
      ticks.forEach { (db, layout) ->
        val x = VizStyle.meterFraction(db.toFloat()) * size.width
        drawText(
          textLayoutResult = layout,
          topLeft = Offset(
            x = (x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)),
            y = (size.height - layout.size.height).coerceAtLeast(0f),
          ),
        )
      }
    }

    Spacer(Modifier.width(8.dp))

    Text(
      text = formatDb(rmsDb),
      modifier = Modifier.width(VALUE_WIDTH_DP.dp),
      color = VizStyle.Fill,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      textAlign = TextAlign.End,
      style = TextStyle(shadow = VizStyle.TextShadow),
    )
  }
}

/** dB 未变化时字形宽度会跳，用等宽字体 + 固定宽度对齐 */
private fun formatDb(db: Float): String =
  if (db <= VizStyle.METER_FLOOR_DB + 0.05f) "-∞" else String.format(Locale.US, "%.1f", db)

/** 标尺刻度：12dB 一档。两端（0 / -60）不标 —— 那是轨道边界，标了只会挤 */
private val METER_TICKS_DB = listOf(-48, -36, -24, -12)

private const val SCALE_LINE_ALPHA = 0.25f
private const val SCALE_LINE_WIDTH = 1f

private const val LABEL_WIDTH_DP = 16
private const val VALUE_WIDTH_DP = 46
private const val PEAK_LINE_WIDTH = 2.5f
private const val RMS_ALPHA = 0.88f

/** 柱高占行高的比例 */
private const val BAR_HEIGHT_RATIO = 0.55f

/** 但不超过这个高度，否则大行高时会变成一根粗白条 */
private const val BAR_MAX_HEIGHT_DP = 12f
