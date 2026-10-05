package top.aerohaku.androidapp.ui.layout

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 用户自己保存的版式预设。
 *
 * 与内置的 [LayoutPreset]（枚举、不可删）的区别：这个是普通 data class，
 * 用户把当前微调好的布局存下来就是一个，也可以删掉。
 *
 * 之所以连 [lyricsAlign] 一起存：它和部件位置是同一个版式的两个组成部分 ——
 * 「歌词在右上」配左对齐会很难看，两者必须一起切换（同 [LayoutPreset] 的理由）。
 */
data class UserPreset(
  /** 内部标识。生成后不变，即使后来改了名字 */
  val id: String,
  val name: String,
  /** 第二行灰字 */
  val note: String,
  val lyricsAlign: LyricsAlign,
  val configs: Map<VisualizerWidget, WidgetConfig>,
)

/** 自动命名用的前缀 */
internal const val USER_PRESET_PREFIX = "用户预设"

/**
 * 下一个可用的「用户预设 - N」编号。
 *
 * 取现有名称里**已用过的最大编号 + 1**，而不是「预设个数 + 1」——
 * 后者在删掉中间某个之后会撞名（留下两个「用户预设 - 2」）。
 * 只认完整的「用户预设 - 数字」；用户自己起的名字不参与编号。
 * 没有可识别的编号时从 1 开始。
 */
internal fun nextUserPresetIndex(existingNames: List<String>): Int {
  val pattern = Regex("^" + Regex.escape(USER_PRESET_PREFIX) + """\s*-\s*(\d+)$""")
  val maxUsed = existingNames
    .mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    .maxOrNull()
  return (maxUsed ?: 0) + 1
}

/**
 * 默认备注 = 创建时间。
 *
 * 参数化 `now` 只是为了能测 —— 直接调 [LocalDateTime.now] 的话断言只能写成正则匹配。
 */
internal fun defaultPresetNote(now: LocalDateTime = LocalDateTime.now()): String =
  now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
