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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.ui.layout.ImportOutcome
import top.aerohaku.androidapp.ui.layout.LayoutPreset
import top.aerohaku.androidapp.ui.layout.UserPreset
import top.aerohaku.androidapp.ui.layout.VisualizerLayoutStore

/**
 * 版式预设。
 *
 * 上半是两个**内置**预设（「默认：…」，不可删），下半是用户自己存的。
 * 用户预设来自「将当前布局保存为预设」—— 微调完部件位置后可以整套存下来，
 * 之后一键切回，不用每次重新拖。
 *
 * 底部还有导出／导入：导出的是一段纯文本，可以经剪贴板、聊天软件传给别的设备或别人。
 */
@Composable
fun PresetCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  VisualizerLayoutStore.ensureLoaded(context)
  val activeId by VisualizerLayoutStore.activeId.collectAsStateWithLifecycle()
  val userPresets by VisualizerLayoutStore.userPresets.collectAsStateWithLifecycle()
  var showSaveDialog by remember { mutableStateOf(false) }
  var showExportDialog by remember { mutableStateOf(false) }
  var showImportDialog by remember { mutableStateOf(false) }
  var importedName by remember { mutableStateOf<String?>(null) }

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

    Spacer(Modifier.height(4.dp))

    ScexGhostButton(
      text = "将当前布局保存为预设",
      onClick = { showSaveDialog = true },
    )

    Spacer(Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(text = "导出布局", onClick = { showExportDialog = true })
      ScexGhostButton(text = "导入布局", onClick = { showImportDialog = true })
    }
    Hint("导出的是整套布局，复制文本串可粘贴给别的设备。")

    importedName?.let {
      Hint("已导入存档为「$it」。")
    }

    // 手动拖过任何一个部件之后，预设名就名不副实了 —— 直接说出来，
    // 而不是继续显示一个已经不准的选中态
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

  if (showExportDialog) {
    // 只在打开的那一刻生成一次：导出期间用户在别处改布局不该影响这个文本框
    val text = remember { VisualizerLayoutStore.exportLayout(context) }
    ExportLayoutDialog(text = text, onDismiss = { showExportDialog = false })
  }

  if (showImportDialog) {
    ImportLayoutDialog(
      defaultName = remember { VisualizerLayoutStore.nextUserPresetName() },
      onImport = { text, name -> VisualizerLayoutStore.importLayout(context, text, name) },
      onImported = { preset ->
        importedName = preset.name
        showImportDialog = false
      },
      onDismiss = { showImportDialog = false },
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

/** 新建预设：名称与备注留空就用灰色占位符里那套默认值 */
@Composable
private fun SavePresetDialog(
  defaultName: String,
  defaultNote: String,
  onConfirm: (name: String, note: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }

  ScexDialog(title = "保存当前布局为预设", onDismiss = onDismiss) {
    Hint("存储当前所有部件的位置和样式信息")

    Text("名称", color = ScexColors.Body, style = MaterialTheme.typography.bodyMedium)
    ScexTextField(value = name, onValueChange = { name = it }, placeholder = defaultName)

    Text("备注", color = ScexColors.Body, style = MaterialTheme.typography.bodyMedium)
    ScexTextField(value = note, onValueChange = { note = it }, placeholder = defaultNote)

    Hint("留空即使用上面的灰色默认值。")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(text = "保存", onClick = { onConfirm(name.trim(), note.trim()) })
      ScexGhostButton(text = "取消", onClick = onDismiss)
    }
  }
}

/** 导出：只读展示 + 一键复制 */
@Composable
private fun ExportLayoutDialog(text: String, onDismiss: () -> Unit) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  ScexDialog(title = "导出布局", onDismiss = onDismiss, width = 640.dp) {
    Hint("复制下面的文本，在「导入布局」处粘贴应用。")
    // 固定高度：内容有四十多行，放开会把对话框底部的按钮顶出屏幕。
    // 这里只需要看得见开头、能整体复制，不靠它看完
    ScexTextField(
      value = text,
      onValueChange = {},
      singleLine = false,
      fixedHeight = 150.dp,
      readOnly = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(
        text = if (copied) "已复制到剪贴板" else "复制到剪贴板",
        onClick = {
          clipboard.setText(AnnotatedString(text))
          copied = true
        },
      )
      ScexGhostButton(text = "关闭", onClick = onDismiss)
    }
  }
}

/**
 * 导入：粘贴 + 命名 + 校验。
 *
 * 存成**新预设**而不是直接套上去 —— 别人发来的布局先收下，
 * 不替用户决定「现在就换掉你正在看的那一套」。
 *
 * 失败时**不关对话框**，把原因显示在下面，用户可以就地改文本再试。
 */
@Composable
private fun ImportLayoutDialog(
  defaultName: String,
  onImport: (text: String, name: String) -> ImportOutcome,
  onImported: (UserPreset) -> Unit,
  onDismiss: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  var text by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  ScexDialog(title = "导入布局", onDismiss = onDismiss, width = 640.dp) {
    ScexTextField(
      value = text,
      onValueChange = {
        text = it
        error = null
      },
      singleLine = false,
      fixedHeight = 130.dp,
      placeholder = "AVL-LAYOUT-1\n…把导出的文本粘到这里",
    )

    ScexGhostButton(
      text = "从剪贴板粘贴",
      onClick = {
        clipboard.getText()?.text?.let {
          text = it
          error = null
        }
      },
    )

    Text("存为", color = ScexColors.Body, style = MaterialTheme.typography.bodyMedium)
    ScexTextField(value = name, onValueChange = { name = it }, placeholder = defaultName)

    error?.let {
      Text(text = it, color = ScexColors.Warning, style = MaterialTheme.typography.bodySmall)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(
        text = "导入",
        onClick = {
          when (val outcome = onImport(text, name)) {
            is ImportOutcome.Ok -> onImported(outcome.preset)
            is ImportOutcome.Failed -> error = outcome.message
          }
        },
      )
      ScexGhostButton(text = "取消", onClick = onDismiss)
    }
  }
}
