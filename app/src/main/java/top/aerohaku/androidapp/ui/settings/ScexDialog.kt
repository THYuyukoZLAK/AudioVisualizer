package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 一个与设置页风格一致的对话框骨架。
 *
 * 用 Compose 的 [Dialog] 而不是 `AlertDialog`：后者自带 Material 的圆角、内边距与
 * 浮动标题，和本项目的「深底 + 方角 + 细边框」不是一套东西，改配色也盖不住圆角。
 */
@Composable
internal fun ScexDialog(
  title: String,
  onDismiss: () -> Unit,
  width: Dp = 460.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .width(width)
        // 必须给高度上限：Dialog 默认不限高，内容一多（比如导出布局那个长文本框）
        // 就会顶出屏幕，底部按钮既看不见也点不到
        .heightIn(max = DIALOG_MAX_HEIGHT)
        .background(ScexColors.Panel)
        .border(1.dp, ScexColors.Border)
        .padding(20.dp)
        // 整体滚动（连标题一起）。别改成「标题固定 + 内容 weight(1f, fill = false)」：
        // 那样外层列的高度会塌到标题那么高，内容溢出到背景外面画成半透明
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = title,
        color = ScexColors.Heading,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      content()
    }
  }
}

private val DIALOG_MAX_HEIGHT = 560.dp

/**
 * 与设置页风格一致的输入框：深底 + 方角 + 细边框。
 *
 * 不用 `TextField`／`OutlinedTextField` —— 它们带 Material 的圆角、填充色与浮动标签。
 * 占位符自己叠一层 [Text] 而不是用 `decorationBox`，少一层 lambda。
 */
@Composable
internal fun ScexTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  singleLine: Boolean = true,
  minHeight: Dp = 0.dp,
  fixedHeight: Dp? = null,
  readOnly: Boolean = false,
) {
  Box(
    modifier
      .fillMaxWidth()
      // 固定高度用于多行展示（比如导出布局那段几十行的文本）：
      // 只给 minHeight 的话，内容一长会把框子撑起来，把对话框底部的按钮顶出屏幕
      .then(
        if (fixedHeight != null) Modifier.height(fixedHeight)
        else if (minHeight > 0.dp) Modifier.heightIn(min = minHeight)
        else Modifier,
      )
      .border(1.dp, ScexColors.Border)
      .background(ScexColors.Background)
      .padding(horizontal = 10.dp, vertical = 9.dp),
  ) {
    if (value.isEmpty() && placeholder.isNotEmpty()) {
      Text(
        text = placeholder,
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = singleLine,
      readOnly = readOnly,
      textStyle = MaterialTheme.typography.bodySmall.copy(color = ScexColors.Body),
      cursorBrush = SolidColor(ScexColors.AccentForm),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * 数值输入对话框。
 *
 * 存在的理由：滑杆适合「随手拖一拖」，但要精确对齐某个值（比如 120dp 的封面边长）
 * 时，拖是拖不准的 —— 拖动时手指就挡住了读数。
 *
 * 输入**故意不做即时校验**（边打边报错很烦），只在点确定时校验一次。
 */
@Composable
internal fun NumberInputDialog(
  title: String,
  initial: Float,
  range: ClosedFloatingPointRange<Float>,
  suffix: String = "",
  decimals: Int = 0,
  onConfirm: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf(formatNumber(initial, decimals)) }
  var error by remember { mutableStateOf<String?>(null) }

  ScexDialog(title = title, onDismiss = onDismiss) {
    Hint("可输入 ${formatNumber(range.start, decimals)} ~ ${formatNumber(range.endInclusive, decimals)}$suffix")
    ScexTextField(
      value = text,
      onValueChange = {
        text = it
        error = null
      },
      placeholder = formatNumber(initial, decimals),
    )
    error?.let {
      Text(text = it, color = ScexColors.Warning, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(
        text = "确定",
        onClick = {
          val parsed = text.trim().toFloatOrNull()
          when {
            parsed == null -> error = "请输入一个数字"
            parsed < range.start || parsed > range.endInclusive ->
              error = "超出可输入范围"
            else -> onConfirm(parsed)
          }
        },
      )
      ScexGhostButton(text = "取消", onClick = onDismiss)
    }
  }
}

/** 按 [decimals] 格式化：0 位时不显示小数点，避免「120.0 dp」这种啰嗦写法 */
internal fun formatNumber(value: Float, decimals: Int): String =
  if (decimals <= 0) {
    value.roundToInt().toString()
  } else {
    String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
  }
