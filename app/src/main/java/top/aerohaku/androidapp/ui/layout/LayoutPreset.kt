package top.aerohaku.androidapp.ui.layout

import androidx.compose.ui.unit.dp

/**
 * 内置版式预设。
 *
 * 预设 = **一套完整的部件位置** + **歌词对齐方式**，
 * 因为「歌词在右上」这个版式如果配左对齐会很难看（文字会飘在框中间），
 * 两者必须一起切换。
 */
enum class LayoutPreset(val label: String, val description: String) {

  /** 初版版式：歌词独占右上角一大块 */
  LYRICS_RIGHT(
    label = "默认：歌词在右上",
    description = "歌曲数据在封面右侧；歌词在右上角，右对齐",
  ),

  /** 当前版式：歌词回到左列、占满整行 */
  LYRICS_BELOW(
    label = "默认：歌词在封面下方",
    description = "歌曲数据在封面右侧并占满余下整行；歌词在封面下方，占满整行、左对齐",
  ),
  ;

  /** 该预设对应的歌词对齐方式 */
  val lyricsAlign: LyricsAlign
    get() = when (this) {
      LYRICS_RIGHT -> LyricsAlign.END
      LYRICS_BELOW -> LyricsAlign.START
    }

  /** 该预设的部件位置 */
  val configs: Map<VisualizerWidget, WidgetConfig>
    get() = when (this) {
      LYRICS_RIGHT -> LYRICS_RIGHT_CONFIGS
      LYRICS_BELOW -> LYRICS_BELOW_CONFIGS
    }

  companion object {
    val default: LayoutPreset = LYRICS_BELOW

    /** 整行的左右边距 */
    private val MARGIN = 64.dp

    /** 专辑封面尺寸；歌曲数据的高度跟着它走，两栏顶部对齐后观感才稳 */
    private val ART = 120.dp

    /** 封面与右侧歌曲数据之间的间隙 */
    private val GAP = 16.dp

    private val COMMON_BOTTOM: Map<VisualizerWidget, WidgetConfig> = mapOf(
      VisualizerWidget.SPECTRUM to
        WidgetConfig(AnchorPoint.BOTTOM_LEFT, offsetX = 64.dp, offsetY = (-124).dp, width = 900.dp, height = 220.dp),
      VisualizerWidget.LEVEL_METERS to
        WidgetConfig(AnchorPoint.BOTTOM_LEFT, offsetX = 64.dp, offsetY = (-48).dp, width = 280.dp, height = 56.dp),
      VisualizerWidget.WAVEFORM to
        WidgetConfig(AnchorPoint.BOTTOM_LEFT, offsetX = 352.dp, offsetY = (-48).dp, width = 612.dp, height = 56.dp),
      VisualizerWidget.PROGRESS to
        WidgetConfig(AnchorPoint.TOP_LEFT, offsetX = 0.dp, offsetY = 0.dp, width = null, height = 9.dp),
    )

    /**
     * 歌词在右上：歌曲数据是**固定窄栏**（340dp），右边缘 540dp，
     * 给歌词框（左边缘 580dp）留 40dp 间隙。
     */
    private val LYRICS_RIGHT_CONFIGS: Map<VisualizerWidget, WidgetConfig> =
      COMMON_BOTTOM + mapOf(
        VisualizerWidget.ALBUM_ART to
          WidgetConfig(AnchorPoint.TOP_LEFT, offsetX = MARGIN, offsetY = 32.dp, width = ART, height = ART),
        VisualizerWidget.TRACK_INFO to
          WidgetConfig(
            AnchorPoint.TOP_LEFT,
            offsetX = MARGIN + ART + GAP,
            offsetY = 32.dp,
            width = 340.dp,
            height = ART,
          ),
        VisualizerWidget.LYRICS to
          WidgetConfig(
            AnchorPoint.TOP_RIGHT,
            offsetX = -MARGIN,
            offsetY = 32.dp,
            width = 560.dp,
            height = 132.dp,
          ),
      )

    /**
     * 歌词在封面下方：两个文本部件都是 `width = null`（撑满）+ `trailingInset` 留右边距。
     *
     * ```
     * [专辑封面] 歌曲数据（占满右侧余下的整行宽度）
     * 歌词（占满整行，在封面下方）
     * ```
     */
    private val LYRICS_BELOW_CONFIGS: Map<VisualizerWidget, WidgetConfig> =
      COMMON_BOTTOM + mapOf(
        VisualizerWidget.ALBUM_ART to
          WidgetConfig(AnchorPoint.TOP_LEFT, offsetX = MARGIN, offsetY = 32.dp, width = ART, height = ART),
        VisualizerWidget.TRACK_INFO to
          WidgetConfig(
            AnchorPoint.TOP_LEFT,
            offsetX = MARGIN + ART + GAP,
            offsetY = 32.dp,
            width = null,
            height = ART,
            trailingInset = MARGIN,
          ),
        VisualizerWidget.LYRICS to
          WidgetConfig(
            AnchorPoint.TOP_LEFT,
            offsetX = MARGIN,
            offsetY = 32.dp + ART + GAP,
            width = null,
            height = 68.dp,
            trailingInset = MARGIN,
          ),
      )
  }
}
