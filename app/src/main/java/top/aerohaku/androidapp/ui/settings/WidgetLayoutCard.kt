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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import top.aerohaku.androidapp.ui.layout.MAX_ALPHA
import top.aerohaku.androidapp.ui.layout.MAX_FONT_SCALE
import top.aerohaku.androidapp.ui.layout.MAX_GAIN
import top.aerohaku.androidapp.ui.layout.MIN_ALPHA
import top.aerohaku.androidapp.ui.layout.MIN_FONT_SCALE
import top.aerohaku.androidapp.ui.layout.MIN_GAIN
import top.aerohaku.androidapp.ui.layout.VisualizerLayoutStore
import top.aerohaku.androidapp.ui.layout.VisualizerWidget
import top.aerohaku.androidapp.ui.layout.WidgetConfig
import top.aerohaku.androidapp.ui.layout.describe
import top.aerohaku.androidapp.ui.layout.placement
import java.util.Locale

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
    Hint("每个部件的位置通过选中的定位点+和定位点的相对位置存储。\n偏移单位为 dp，横屏下屏幕约 1200dp 宽、750dp 高。")
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
        ValueSlider("高度", config.height?.value ?: 0f, 0f..700f, "dp", zeroText = "自适应", disabled = config.lockAspect) {
          onChange(config.copy(height = if (it <= 0f) null else it.dp))
        }

        // ---------------------------------------------------------------- 外观
        if (widget.isText) {
          ValueSlider("字号", config.fontScale, MIN_FONT_SCALE..MAX_FONT_SCALE, "×", decimals = 2) {
            onChange(config.copy(fontScale = it))
          }
        }
        if (widget == VisualizerWidget.SPECTRUM) {
          ValueSlider("显示增益", config.gain, MIN_GAIN..MAX_GAIN, "×", decimals = 2) {
            onChange(config.copy(gain = it))
          }
          Hint("调整频谱图的增益，值越大同样响度下频谱越高。")
        }
        if (widget == VisualizerWidget.ALBUM_ART) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledValue("锁定 1:1", modifier = Modifier.weight(1f))
            ScexSwitch(
              checked = config.lockAspect,
              onCheckedChange = { onChange(config.copy(lockAspect = it)) },
            )
          }
        }
        ValueSlider("不透明度", config.alpha, MIN_ALPHA..MAX_ALPHA, "", decimals = 2) {
          onChange(config.copy(alpha = it))
        }
        ColorRow(current = config.color) { onChange(config.copy(color = it)) }

        ScexGhostButton("恢复默认", onClick = onReset)
      }
    }
  }
}

/**
 * 「颜色」一行：左边标题，右边一个小色块加十六进制值，点整行开小窗调。
 *
 * 调色摊开要占四行（预览 + 输入框 + 三根滑杆），塞在卡片里会把别的设置全挤出屏幕，
 * 而它又不是每次都要动的东西 —— 折成一行，要用时再开窗。
 */
@Composable
private fun ColorRow(current: Int, onPick: (Int) -> Unit) {
  var open by remember { mutableStateOf(false) }

  Row(
    Modifier
      .fillMaxWidth()
      .clickable { open = true }
      .padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "颜色",
      color = ScexColors.Muted,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
    )
    Box(
      Modifier
        .size(COLOR_SWATCH)
        .border(1.dp, ScexColors.Border)
        .background(Color(current)),
    )
    Text(
      text = hexOf(current),
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    // 只有色块与数值的话，看着像纯展示 —— 加个箭头说明「点得开」
    Text(
      text = "›",
      color = ScexColors.Muted,
      style = MaterialTheme.typography.bodySmall,
    )
  }

  if (open) {
    ColorPickerDialog(current = current, onPick = onPick, onDismiss = { open = false })
  }
}

/**
 * 调色小窗：预览块 + 十六进制输入框 + RGB 三根滑杆。
 *
 * 三样东西改的是同一个值、互为镜像：打字、拖滑杆、看预览块，
 * 从哪一样下手都行，另外两样立刻跟上。
 *
 * 改一下**立即生效**（小窗遮不住部件，后台在实时变），所以留的是「还原」而不是「取消」
 * —— 已经写进去的东西再挂个「取消」是骗人的。
 *
 * 只管 RGB。透明度归上面那根「不透明度」滑杆 ——
 * 同一个部件有两个地方调透明度只会互相打架。
 */
@Composable
private fun ColorPickerDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  // 开窗那一刻的颜色，给「还原」用。remember 故意不带 key：
  // 拖滑杆会让 current 一直变，但这个基准值得在整个开窗期间钉住
  val initial = remember { current }

  ScexDialog(title = "颜色", onDismiss = onDismiss) {
    ColorPicker(current = current, onPick = onPick)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(text = "完成", onClick = onDismiss)
      ScexGhostButton(text = "还原", onClick = { onPick(initial) })
    }
  }
}

@Composable
private fun ColorPicker(current: Int, onPick: (Int) -> Unit) {
  var text by remember { mutableStateOf(hexOf(current)) }

  // 颜色被别处改了（拖滑杆、切预设、恢复默认）就同步回输入框；
  // 若是自己刚因为打字写出去的那个值，认出来就别回写，
  // 否则光标会跳、正在敲的半截输入会被打断
  LaunchedEffect(current) {
    if (current != parseHexColor(text)) text = hexOf(current)
  }

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 实时预览 —— 这里看到的颜色就是部件上会画出来的那个
      Box(
        Modifier
          .size(COLOR_PREVIEW)
          .border(1.dp, ScexColors.Border)
          .background(Color(current)),
      )
      ScexTextField(
        value = text,
        onValueChange = { raw ->
          // 只留十六进制字符并截到 6 位：粘贴 `#FF0000FF`、带空格或小写的写法都能用
          text = "#" + raw.filter(::isHexDigit).take(6)
          // 凑满 6 位就立刻生效，不必等回车或失焦
          parseHexColor(text)?.let(onPick)
        },
        placeholder = "#FFFFFF",
        modifier = Modifier.weight(1f),
      )
    }

    ValueSlider("R", ((current shr 16) and 0xFF).toFloat(), 0f..255f, "") {
      onPick(withChannel(current, 16, it.toInt()))
    }
    ValueSlider("G", ((current shr 8) and 0xFF).toFloat(), 0f..255f, "") {
      onPick(withChannel(current, 8, it.toInt()))
    }
    ValueSlider("B", (current and 0xFF).toFloat(), 0f..255f, "") {
      onPick(withChannel(current, 0, it.toInt()))
    }
  }
}

private fun isHexDigit(c: Char) = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

/** `#RRGGBB`：输入框里只显示 RGB，透明度不在这里 */
private fun hexOf(argb: Int): String =
  String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

/** 认得出就返回一个**不透明**的 ARGB；位数不够或有非法字符则返回 null */
private fun parseHexColor(text: String): Int? =
  text.removePrefix("#")
    .takeIf { it.length == 6 }
    ?.toIntOrNull(16)
    ?.let { OPAQUE or it }

/** 换掉 RGB 三通道之一（[shift] = 16/8/0），并把 alpha 归一成不透明 */
private fun withChannel(current: Int, shift: Int, value: Int): Int {
  val cleared = current and 0xFFFFFF and (0xFF shl shift).inv()
  return OPAQUE or cleared or ((value and 0xFF) shl shift)
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
  decimals: Int = 0,
  disabled: Boolean = false,
  onChange: (Float) -> Unit,
) {
  var editing by remember { mutableStateOf(false) }

  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      // 点数值直接输入 —— 拖滑杆适合随手调，但要精确到某个值（比如封面边长取整 120dp）时
      // 拖是拖不准的，而且拖动时手指还挡住了读数
      Text(
        text = when {
          disabled -> "跟随宽度"
          zeroText != null && value <= 0f -> zeroText
          // 空 suffix 不补那个空格（RGB 三根滑杆就不带单位）
          suffix.isEmpty() -> formatNumber(value, decimals)
          else -> "${formatNumber(value, decimals)} $suffix"
        },
        color = if (disabled) ScexColors.Muted else ScexColors.Body,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (disabled) {
          Modifier
        } else {
          Modifier
            .clickable { editing = true }
            .padding(horizontal = 6.dp, vertical = 2.dp)
        },
      )
    }
    if (!disabled) {
      ScexSlider(value = value, range = range, onValueChange = onChange, dragLabel = label)
    }
  }

  if (editing) {
    NumberInputDialog(
      title = label,
      initial = value,
      range = range,
      suffix = suffix,
      decimals = decimals,
      onConfirm = {
        onChange(it)
        editing = false
      },
      onDismiss = { editing = false },
    )
  }
}

private val ANCHOR_CELL = 44.dp

/** 「颜色」那一行右侧的小色块 */
private val COLOR_SWATCH = 22.dp

/** 调色小窗里的预览块：比滑杆矮一点，与输入框同行 */
private val COLOR_PREVIEW = 44.dp

/** 不透明黑：颜色控件的写出一律带上它（透明度由「不透明度」滑杆负责） */
private val OPAQUE = 0xFF000000.toInt()
