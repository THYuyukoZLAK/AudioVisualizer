package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import top.aerohaku.androidapp.lyrics.LyricLine
import top.aerohaku.androidapp.ui.layout.LyricsAlign

/**
 * 歌词：**只显示当前正在播放的那一句**，原文 + 翻译。
 *
 * 对齐方式由 [align] 决定，而它跟着版式预设走（见 `LayoutPreset`）：
 * 歌词在右上时用右对齐、在左下时用左对齐。
 *
 * ⚠️ 光改 `Column(horizontalAlignment = ...)` 不够 —— 那只能把「文本块」整体靠一侧，
 * 块内换行后的各行仍是左对齐。必须同时给 `Text` 加 `fillMaxWidth() + textAlign`。
 *
 * 刻意只收 [LyricLine]（不订阅数据流）—— 保持它是纯展示组件。
 */
@Composable
fun LyricsView(
  line: LyricLine?,
  align: LyricsAlign,
  modifier: Modifier = Modifier,
  color: Color = VizStyle.Fill,
  fontScale: Float = 1f,
) {
  val alignment = if (align == LyricsAlign.END) Alignment.End else Alignment.Start
  val textAlign = if (align == LyricsAlign.END) TextAlign.End else TextAlign.Start

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = alignment,
  ) {
    Text(
      text = line?.text?.takeIf { it.isNotBlank() } ?: PLACEHOLDER,
      color = color,
      fontSize = (MAIN_FONT_SP * fontScale).sp,
      fontWeight = FontWeight.Bold,
      textAlign = textAlign,
      style = TextStyle(shadow = VizStyle.TextShadow),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth(),
    )

    val translation = line?.translation?.takeIf { it.isNotBlank() }
    if (translation != null) {
      Text(
        text = translation,
        color = color.copy(alpha = 0.78f),
        fontSize = (SUB_FONT_SP * fontScale).sp,
        textAlign = textAlign,
        style = TextStyle(shadow = VizStyle.TextShadow),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private const val PLACEHOLDER = "♪"

/** 歌词原文与译文的基准字号（sp），实际字号再乘部件的 fontScale */
private const val MAIN_FONT_SP = 24f
private const val SUB_FONT_SP = 16f
