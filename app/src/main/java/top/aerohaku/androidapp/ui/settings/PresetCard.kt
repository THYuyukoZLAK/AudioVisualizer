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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.ui.layout.LayoutPreset
import top.aerohaku.androidapp.ui.layout.VisualizerLayoutStore

/**
 * 版式预设。
 *
 * 上半是两个**内置**预设（「默认：…」，不可删），下半是用户自己存的。
 * 用户预设来自「将当前布局保存为预设」—— 微调完部件位置后可以整套存下来，
 * 之后一键切回，不用每次重新拖。
 */
@Composable
fun PresetCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  VisualizerLayoutStore.ensureLoaded(context)
  val activeId by VisualizerLayoutStore.activeId.collectAsStateWithLifecycle()
  val userPresets by VisualizerLayoutStore.userPresets.collectAsStateWithLifecycle()
  val configs by VisualizerLayoutStore.configs.collectAsStateWithLifecycle()
  var showSaveDialog by remember { mutableStateOf(false) }

  ScexCard(title = "版式预设", modifier = modifier) {
    LayoutPreset.entries.forEach { option ->
      PresetRow(
        name = option.label,
        note = option.description,
        selected = activeId == option.name,
        onClick = { VisualizerLayoutStore.applyPreset(context, option) },
      )
    }

    userPresets.forEach { preset ->
      PresetRow(
        name = preset.name,
        note = preset.note,
        selected = activeId == preset.id,
        onClick = { VisualizerLayoutStore.applyUserPreset(context, preset.id) },
        onDelete = { VisualizerLayoutStore.deleteUserPreset(context, preset.id) },
      )
    }

    Spacer(Modifier.width(1.dp))

    ScexGhostButton(
      text = "将当前布局保存为预设",
      onClick = { showSaveDialog = true },
    )

    // 手动拖过任何一个部件之后，预设名就名不副实了 —— 直接说出来，
    // 而不是继续显示一个已经不准的选中态。
    // 注意这里读的是 Store 的即时值（而不是上面 collect 到的 configs），
    // 因为 configs 只是用来触发重组的，判等交给 Store 自己做更省事。
    if (!VisualizerLayoutStore.isPresetIntact()) {
      Hint(
        "当前布局已被手动调整过，与「${VisualizerLayoutStore.activeName() ?: "所选预设"}」不再一致。" +
          "点上面任意预设可复位，或把现在这一套存成新预设。",
      )
    }
  }

  if (showSaveDialog) {
    // 默认值在对话框弹出的那一刻取一次即可：它只是占位符，
    // 用户在对话框里打字时不需要它在底下悄悄变
    val defaultName = remember { VisualizerLayoutStore.nextUserPresetName() }
    val defaultNote = remember { VisualizerLayoutStore.defaultUserPresetNote() }

    SavePresetDialog(
      defaultName = defaultName,
      defaultNote = defaultNote,
      onConfirm = { name, note ->
        VisualizerLayoutStore.saveCurrentAsUserPreset(context, name, note)
        showSaveDialog = false
      },
      onDismiss = { showSaveDialog = false },
    )
  }
}

@Composable
private fun PresetRow(
  name: String,
  note: String,
  selected: Boolean,
  onClick: () -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .border(1.dp, if (selected) ScexColors.AccentForm else ScexColors.Border)
      .background(
        if (selected) ScexColors.AccentForm.copy(alpha = 0.15f) else ScexColors.Background,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      Modifier
        .weight(1f)
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = if (selected) "▸ $name" else name,
        color = if (selected) ScexColors.Heading else ScexColors.Body,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      )
      Hint(note)
    }
    // 删除只对用户预设开放：内置预设是「恢复默认」的落点，删了就回不去了
    if (onDelete != null) {
      Box(
        Modifier
          .clickable(onClick = onDelete)
          .padding(horizontal = 14.dp, vertical = 14.dp),
      ) {
        Text(
          text = "删除",
          color = ScexColors.Warning,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

/**
 * 新建预设对话框。
 *
 * 用 Compose 的 [Dialog] 而不是 `AlertDialog`：后者自带 Material 的圆角与内边距，
 * 和本项目的「深底 + 方角 + 细边框」不是一套东西。
 */
@Composable
private fun SavePresetDialog(
  defaultName: String,
  defaultNote: String,
  onConfirm: (name: String, note: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .width(460.dp)
        .background(ScexColors.Panel)
        .border(1.dp, ScexColors.Border)
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "保存当前布局为预设",
        color = ScexColors.Heading,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Hint("当前的部件位置、尺寸与歌词对齐会一起存下来，之后可以一键切回。")

      Spacer(Modifier.width(1.dp))

      Text("名称", color = ScexColors.Body, style = MaterialTheme.typography.bodyMedium)
      ScexTextField(value = name, onValueChange = { name = it }, placeholder = defaultName)

      Text("备注", color = ScexColors.Body, style = MaterialTheme.typography.bodyMedium)
      ScexTextField(value = note, onValueChange = { note = it }, placeholder = defaultNote)

      Hint("留空即使用上面的灰色默认值。")

      Spacer(Modifier.width(1.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScexGhostButton(text = "保存", onClick = { onConfirm(name.trim(), note.trim()) })
        ScexGhostButton(text = "取消", onClick = onDismiss)
      }
    }
  }
}

/**
 * 与设置页风格一致的输入框：深底 + 方角 + 细边框。
 *
 * 不用 `TextField`／`OutlinedTextField` —— 它们带 Material 的圆角、填充色与浮动标签，
 * 和这一套样式对不上，改配色也盖不住圆角。
 * 占位符自己叠一层 [Text] 而不是用 `decorationBox`，少一层 lambda。
 */
@Composable
private fun ScexTextField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier
      .fillMaxWidth()
      .border(1.dp, ScexColors.Border)
      .background(ScexColors.Background)
      .padding(horizontal = 10.dp, vertical = 9.dp),
  ) {
    if (value.isEmpty()) {
      Text(
        text = placeholder,
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = ScexColors.Body),
      cursorBrush = SolidColor(ScexColors.AccentForm),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
