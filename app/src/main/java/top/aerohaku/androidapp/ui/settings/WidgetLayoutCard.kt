package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexSlider
import top.aerohaku.androidapp.theme.ScexSwitch
import top.aerohaku.androidapp.ui.layout.AnchorPoint
import top.aerohaku.androidapp.ui.layout.VisualizerLayoutStore
import top.aerohaku.androidapp.ui.layout.VisualizerWidget
import top.aerohaku.androidapp.ui.layout.WidgetConfig
import top.aerohaku.androidapp.ui.layout.describe
import top.aerohaku.androidapp.ui.layout.placement

/**
 * 部件布局编辑器。
 *
 * 定位点的选择器做成**真正的九宫格**：用户选的就是屏幕上那个位置，
 * 不需要在脑子里把「左下」翻译成一个下拉框里的字符串。
 */
@Composable
fun WidgetLayoutCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  VisualizerLayoutStore.ensureLoaded(context)
  val configs by VisualizerLayoutStore.configs.collectAsStateWithLifecycle()

  ScexCard(title = "部件布局", modifier = modifier) {
    Hint("每个部件按「九宫格定位点 + 相对该点的偏移」摆放。偏移单位为 dp，横屏下屏幕约 1200dp 宽、750dp 高。修改立即生效并保存。")
    ScexGhostButton("全部恢复默认", onClick = { VisualizerLayoutStore.resetAll(context) })

    configs.forEach { (widget, config) ->
      WidgetLayoutEditor(
        widget = widget,
        config = config,
        onChange = { VisualizerLayoutStore.update(context, widget, it) },
        onReset = { VisualizerLayoutStore.resetOne(context, widget) },
      )
    }
  }
}

@Composable
private fun WidgetLayoutEditor(
  widget: VisualizerWidget,
  config: WidgetConfig,
  onChange: (WidgetConfig) -> Unit,
  onReset: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }

  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = widget.label,
        color = ScexColors.Heading,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
          .weight(1f)
          .clickable { expanded = !expanded },
      )
      Text(
        text = config.placement().describe(),
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.clickable { expanded = !expanded },
      )
      Spacer(Modifier.width(10.dp))
      ScexSwitch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
    }

    if (expanded) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        AnchorPicker(selected = config.anchor, onSelect = { onChange(config.copy(anchor = it)) })

        ValueSlider("X 偏移", config.offsetX.value, -800f..800f, "dp") {
          onChange(config.copy(offsetX = it.dp))
        }
        ValueSlider("Y 偏移", config.offsetY.value, -800f..800f, "dp") {
          onChange(config.copy(offsetY = it.dp))
        }
        // 宽度 0 = 撑满容器宽度（进度条、歌曲数据、歌词都是这个模式）
        ValueSlider("宽度", config.width?.value ?: 0f, 0f..1400f, "dp", zeroText = "撑满") {
          onChange(config.copy(width = if (it <= 0f) null else it.dp))
        }
        // 只在「撑满」时有意义：给整行留出两侧边距
        if (config.width == null) {
          ValueSlider("右侧留白（撑满时生效）", config.trailingInset.value, 0f..400f, "dp") {
            onChange(config.copy(trailingInset = it.dp))
          }
        }
        ValueSlider("高度", config.height?.value ?: 0f, 0f..700f, "dp", zeroText = "自适应") {
          onChange(config.copy(height = if (it <= 0f) null else it.dp))
        }

        ScexGhostButton("恢复默认", onClick = onReset)
      }
    }
  }
}

/** 3×3 的定位点选择器 —— 选哪个方块就是摆在屏幕的哪个位置 */
@Composable
private fun AnchorPicker(selected: AnchorPoint, onSelect: (AnchorPoint) -> Unit) {
  val rows = listOf(
    listOf(AnchorPoint.TOP_LEFT, AnchorPoint.TOP_CENTER, AnchorPoint.TOP_RIGHT),
    listOf(AnchorPoint.CENTER_LEFT, AnchorPoint.CENTER, AnchorPoint.CENTER_RIGHT),
    listOf(AnchorPoint.BOTTOM_LEFT, AnchorPoint.BOTTOM_CENTER, AnchorPoint.BOTTOM_RIGHT),
  )

  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    rows.forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        row.forEach { point ->
          val active = point == selected
          Box(
            modifier = Modifier
              .size(ANCHOR_CELL)
              .border(1.dp, if (active) ScexColors.AccentForm else ScexColors.Border)
              .background(
                if (active) ScexColors.AccentForm.copy(alpha = 0.25f) else ScexColors.Background,
              )
              .clickable { onSelect(point) },
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = point.label,
              fontSize = 10.sp,
              color = if (active) ScexColors.Heading else ScexColors.Body,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ValueSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  suffix: String,
  zeroText: String? = null,
  onChange: (Float) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = if (zeroText != null && value <= 0f) zeroText else "${value.toInt()} $suffix",
        color = ScexColors.Body,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    ScexSlider(value = value, range = range, onValueChange = onChange, dragLabel = label)
  }
}

private val ANCHOR_CELL = 44.dp
