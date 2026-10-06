package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexSlider
import top.aerohaku.androidapp.theme.ScexSwitch
import top.aerohaku.androidapp.ui.layout.BlurAlgorithm
import top.aerohaku.androidapp.ui.layout.LyricsAlign
import top.aerohaku.androidapp.ui.layout.VisualizerAppearanceStore
import kotlin.math.roundToInt

/**
 * 外观配置：背景暗化强度 / 专辑封面白框 / 歌词对齐。
 *
 * 背景是用户自己的专辑封面，亮度完全不可控 —— 暗化强度必须可调，
 * 否则遇到极浅色的封面时白色图形会糊掉、遇到深色封面又会显得死黑。
 */
@Composable
fun AppearanceCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  VisualizerAppearanceStore.ensureLoaded(context)
  val appearance by VisualizerAppearanceStore.state.collectAsStateWithLifecycle()

  ScexCard(title = "外观", modifier = modifier) {
    LabeledValue("背景暗化", VisualizerAppearanceStore.formatDarken(appearance.backgroundDarken))
    ScexSlider(
      value = appearance.backgroundDarken,
      range = VisualizerAppearanceStore.MIN_DARKEN..VisualizerAppearanceStore.MAX_DARKEN,
      onValueChange = { VisualizerAppearanceStore.setBackgroundDarken(context, it) },
      dragLabel = "背景暗化",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("背景虚化", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = appearance.backgroundBlur.enabled,
        onCheckedChange = { VisualizerAppearanceStore.setBackgroundBlurEnabled(context, it) },
      )
    }
    if (appearance.backgroundBlur.enabled) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        LabeledValue("虚化算法", modifier = Modifier.weight(1f))
        BlurAlgorithm.entries.forEach { option ->
          ChoiceChip(
            text = option.label,
            selected = appearance.backgroundBlur.algorithm == option,
            onClick = { VisualizerAppearanceStore.setBackgroundBlurAlgorithm(context, option) },
          )
          Spacer(Modifier.width(6.dp))
        }
      }
      Hint(appearance.backgroundBlur.algorithm.note)

      FloatSlider(
        label = "虚化程度",
        value = appearance.backgroundBlur.amount.toFloat(),
        range = VisualizerAppearanceStore.MIN_BLUR_AMOUNT.toFloat()..
          VisualizerAppearanceStore.MAX_BLUR_AMOUNT.toFloat(),
        display = VisualizerAppearanceStore.formatBlurAmount(appearance.backgroundBlur.amount),
        onChange = { VisualizerAppearanceStore.setBackgroundBlurAmount(context, it.roundToInt()) },
      )
      Hint(
        "模糊在缩小到短边 128 像素的缩略图上做，再放大铺满整屏 —— " +
          "既没有放大痕迹，开销也和屏幕分辨率无关。程度 0 就是不卷积。",
      )
    }

    Spacer(Modifier.width(1.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("专辑封面白框", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = appearance.albumArtBorder,
        onCheckedChange = { VisualizerAppearanceStore.setAlbumArtBorder(context, it) },
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("歌词对齐", modifier = Modifier.weight(1f))
      // 切换版式预设时这个值会跟着变（见 LayoutPreset.lyricsAlign）
      LyricsAlign.entries.forEach { option ->
        ChoiceChip(
          text = option.label,
          selected = appearance.lyricsAlign == option,
          onClick = { VisualizerAppearanceStore.setLyricsAlign(context, option) },
        )
        Spacer(Modifier.width(6.dp))
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("频谱标尺与数值", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = appearance.showSpectrumScale,
        onCheckedChange = { VisualizerAppearanceStore.setShowSpectrumScale(context, it) },
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("电平标尺与数值", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = appearance.showLevelScale,
        onCheckedChange = { VisualizerAppearanceStore.setShowLevelScale(context, it) },
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("小屏自动缩放", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = appearance.autoScale,
        onCheckedChange = { VisualizerAppearanceStore.setAutoScale(context, it) },
      )
    }
    Hint("根据屏幕尺寸缩放组件，默认开启，可避免默认布局下组件重叠。\n建议开启。")

    ScexGhostButton("恢复默认", onClick = { VisualizerAppearanceStore.reset(context) })
  }
}

/** 「标签 —— 值」一行 */
@Composable
internal fun LabeledValue(label: String, value: String? = null, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Text(
      text = label,
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    if (value != null) {
      Text(text = value, color = ScexColors.Heading, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** 次级说明文字 */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = ScexColors.Muted,
    style = MaterialTheme.typography.bodySmall,
    modifier = modifier,
  )
}

/**
 * 二选一的方形小按钮。
 * skill 的「选项列表」规范：背景 `#022335` + 边框 `#0f5377`，
 * 选中态边框 `#1985a1` + 背景 `rgba(25,133,161,.15)`，**无圆角**。
 */
@Composable
internal fun ChoiceChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .border(1.dp, if (selected) ScexColors.AccentForm else ScexColors.Border)
      .background(
        if (selected) ScexColors.AccentForm.copy(alpha = 0.15f) else ScexColors.Background,
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.Center,
  ) {
    Text(
      text = text,
      color = if (selected) ScexColors.Heading else ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
  }
}
