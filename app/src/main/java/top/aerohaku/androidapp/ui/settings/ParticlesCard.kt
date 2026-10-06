package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.capture.CaptureController
import top.aerohaku.androidapp.display.DisplayRefresh
import top.aerohaku.androidapp.display.FrameStats
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexSlider
import top.aerohaku.androidapp.theme.ScexStatRow
import top.aerohaku.androidapp.theme.ScexSwitch
import top.aerohaku.androidapp.ui.particles.ParticleDirection
import top.aerohaku.androidapp.ui.particles.ParticleField
import top.aerohaku.androidapp.ui.particles.ParticleSettings
import top.aerohaku.androidapp.ui.particles.ParticleSettingsStore
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 背景粒子配置（移植自 mfosu 的 `Particles` 设置面板）。
 *
 * 最后那条「能量倍率」是 mfosu 没有的：它直接把 `Σ频谱幅度` 当作粒子的时间流速，
 * 两边幅度标度不同没法照抄，所以做成可调 —— 配合上面那行实时读数调一次就够。
 */
@Composable
fun ParticlesCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  ParticleSettingsStore.ensureLoaded(context)
  val settings by ParticleSettingsStore.state.collectAsStateWithLifecycle()

  ScexCard(title = "背景粒子", modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      LabeledValue("启用", modifier = Modifier.weight(1f))
      ScexSwitch(
        checked = settings.enabled,
        onCheckedChange = { ParticleSettingsStore.setEnabled(context, it) },
      )
    }

    Hint("伪 3D 视差星野，复刻自 mfosu（osu! 插件 IGPlayer/LLin）。")

    Spacer(Modifier.width(1.dp))
    EnergyReadout(settings.energyGain)
    Spacer(Modifier.width(1.dp))

    FloatSlider(
      label = "粒子数量",
      value = settings.count.toFloat(),
      range = ParticleField.MIN_COUNT.toFloat()..ParticleField.MAX_COUNT.toFloat(),
      display = settings.count.toString(),
      onChange = { ParticleSettingsStore.setCount(context, it.roundToInt()) },
    )

    FloatSlider(
      label = "粒子尺寸",
      value = settings.sizeScale,
      range = ParticleSettings.MIN_SIZE_SCALE..ParticleSettings.MAX_SIZE_SCALE,
      display = ParticleSettingsStore.formatSizeScale(settings.sizeScale),
      onChange = { ParticleSettingsStore.setSizeScale(context, it) },
    )

    FloatSlider(
      label = "粒子不透明度",
      value = settings.opacity,
      range = ParticleSettings.MIN_OPACITY..ParticleSettings.MAX_OPACITY,
      display = ParticleSettingsStore.formatOpacity(settings.opacity),
      onChange = { ParticleSettingsStore.setOpacity(context, it) },
    )

    FloatSlider(
      label = "整体速度",
      value = settings.globalSpeed.toFloat(),
      range = ParticleField.MIN_GLOBAL_SPEED.toFloat()..ParticleField.MAX_GLOBAL_SPEED.toFloat(),
      display = "${settings.globalSpeed}%",
      onChange = { ParticleSettingsStore.setGlobalSpeed(context, it.roundToInt()) },
    )

    FloatSlider(
      label = "能量倍率（音频 → 流速）",
      value = settings.energyGain,
      range = ParticleField.MIN_ENERGY_GAIN..ParticleField.MAX_ENERGY_GAIN,
      display = if (settings.energyGain <= 0f) {
        "关（匀速）"
      } else {
        ParticleSettingsStore.formatGain(settings.energyGain)
      },
      onChange = { ParticleSettingsStore.setEnergyGain(context, it) },
    )
    Hint("粒子受音频影响的移动速度，为 0 时粒子完全静止。")

    Spacer(Modifier.width(1.dp))

    LabeledValue("方向")
    ParticleDirection.entries.chunked(DIRECTION_CHIPS_PER_ROW).forEach { row ->
      Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        row.forEach { direction ->
          ChoiceChip(
            text = direction.label,
            selected = settings.direction == direction,
            onClick = { ParticleSettingsStore.setDirection(context, direction) },
          )
          Spacer(Modifier.width(6.dp))
        }
      }
    }
    Spacer(Modifier.width(1.dp))
    Hint(settings.direction.description)

    Spacer(Modifier.width(1.dp))
    DisplayRefreshRow()
    MeasuredFpsRow()

    ScexGhostButton("恢复默认", onClick = { ParticleSettingsStore.reset(context) })
  }
}

/**
 * 显示刷新率。系统会按内容负载在多个模式间切换，所以每次回到前台重新读 ——
 * 这里的「当前」才是能否真正跑上高刷的证据（`preferredRefreshRate` 只是建议）。
 */
@Composable
private fun DisplayRefreshRow() {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var text by remember { mutableStateOf(DisplayRefresh.describe(context)) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) text = DisplayRefresh.describe(context)
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  ScexStatRow("显示刷新率（当前 / 最高）", text)
}

/**
 * 粒子层实测帧率（由 `ParticleFieldView` 的帧回调统计）。
 *
 * ⚠️ 进入设置页后可视化页会退出组合、帧循环停掉，
 * 所以这里显示的是**离开可视化页之前的最后一次测量值**。
 */
@Composable
private fun MeasuredFpsRow() {
  val fps by FrameStats.fps.collectAsStateWithLifecycle()
  ScexStatRow(
    "粒子层实测帧率",
    if (fps <= 0f) "—（尚未进入可视化页）" else String.format(Locale.US, "%.1f Hz", fps),
  )
}

/**
 * 实时读数。
 *
 * 刻意收成独立的 composable：它会跟着音频帧（约 40fps）重组，
 * 写在 [ParticlesCard] 里会让整张卡片连带下面所有内容一起重组。
 */
@Composable
private fun EnergyReadout(gain: Float) {
  val frame by CaptureController.audioFrame.collectAsStateWithLifecycle()
  val energy = frame.energy
  val rate = (energy * gain).coerceIn(0f, ParticleField.MAX_RATE)

  ScexStatRow("音频能量 Σ 频谱幅度", String.format(Locale.US, "%.3f", energy))
  ScexStatRow(
    "→ 粒子时间流速",
    if (rate <= 0f) "0（冻结）" else String.format(Locale.US, "%.2f×", rate),
  )
}

/** 「标签 + 当前值 + 滑杆」三件套。与 [ValueSlider] 的区别是它能按任意格式显示浮点值 */
@Composable
internal fun FloatSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  display: String,
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
        text = display,
        color = ScexColors.Body,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    ScexSlider(value = value, range = range, onValueChange = onChange, dragLabel = label)
  }
}

private const val DIRECTION_CHIPS_PER_ROW = 4
