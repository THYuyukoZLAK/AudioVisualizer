package top.aerohaku.androidapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 可视化页面的统一风格约定（见用户给的版式稿）。
 *
 * - 所有图形填充**纯白**，**不做圆角**
 * - 除波形图上下两条白线、频谱图下方一条白线（基座）之外，**没有边框、没有背景**
 * - 背景 = 专辑封面 + 高斯模糊 + 30% 暗化
 */
object VizStyle {

  /** 所有图形的填充色 */
  val Fill: Color = Color.White

  /** 各个部件的默认图形线宽（dp） */
  const val LINE_DP = 1.5f

  /** 背景暗化强度 */
  const val BACKGROUND_DARKEN = 0.30f

  /**
   * 左右声道电平表的显示范围。
   * 用 60dB 而不是 [top.aerohaku.androidapp.dsp.Levels.MIN_DB] 的 75dB ——
   * 音乐电平基本落在 -60..0 之间，60dB 量程下分辨率更高、更好读。
   */
  const val METER_FLOOR_DB = -60f
  const val METER_CEIL_DB = 0f

  /** dB → 0..1（电平表用） */
  fun meterFraction(db: Float): Float =
    ((db - METER_FLOOR_DB) / (METER_CEIL_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)

  /**
   * 白色文字的投影。
   *
   * 背景是用户自己的专辑封面，亮度完全不可控 —— 纯白字压在浅色封面上会直接看不见。
   * 加一层柔和的黑色投影之后，在深色和浅色底上都能读。
   * 这不是「加背景」，只是给文字留一点对比度余量。
   */
  val TextShadow: Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.55f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
    blurRadius = 6f,
  )

  /** 带到投影的文本样式片段 */
  val TextShadowStyle: TextStyle = TextStyle(shadow = TextShadow, fontSize = 14.sp)
}
