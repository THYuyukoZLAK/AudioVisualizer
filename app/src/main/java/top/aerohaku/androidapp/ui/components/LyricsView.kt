package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
      color = VizStyle.Fill,
      fontSize = 24.sp,
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
        color = VizStyle.Fill.copy(alpha = 0.78f),
        fontSize = 16.sp,
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
