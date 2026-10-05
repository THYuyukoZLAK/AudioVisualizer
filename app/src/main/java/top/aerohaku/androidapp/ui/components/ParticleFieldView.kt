package top.aerohaku.androidapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import top.aerohaku.androidapp.display.FrameStats
import top.aerohaku.androidapp.ui.particles.ParticleField
import top.aerohaku.androidapp.ui.particles.ParticleSettings

/**
 * 音频能量来源。
 *
 * 刻意用**普通对象**而不是 Compose 状态：粒子每帧都要读一次最新能量，
 * 如果走 `StateFlow.collectAsState`，每个音频帧（约 40fps）都会触发一次重组，
 * 而重组出来的值其实只被帧回调读走。用一个 `@Volatile` 字段就够了。
 */
class ParticleEnergySource {
  @Volatile var energy: Float = 0f
}

/**
 * 伪 3D 视差粒子背景（移植自 mfosu，见 [ParticleField] 的类注释）。
 *
 * ## 为什么用 `drawBehind` + 帧计数，而不是 Compose 动画
 *
 * 粒子场是一个**有状态的自推进模拟**（每颗粒子的 depth 都要跨帧累积），
 * 不适合用 `animateFloatAsState` 那套声明式动画表达。所以：
 *
 * - `withFrameNanos` 循环里推进模拟（副作用，不参与快照）
 * - [revision] 只用来**触发重绘**：它在 [DrawScope] 里被读到，
 *   而 Compose 的快照系统对 **draw 阶段**的读取是单独记账的，
 *   所以每帧只失效重绘，**不会触发重组**。
 *
 * @param settings 粒子配置，变化时会即时生效
 * @param energy 当前音频能量，由 [ParticleEnergySource] 提供
 */
@Composable
fun ParticleFieldView(
  settings: ParticleSettings,
  energy: ParticleEnergySource,
  modifier: Modifier = Modifier,
) {
  val field = remember { ParticleField() }
  val revision = remember { mutableIntStateOf(0) }

  // [宽, 高]（px）。用普通数组装，避免每帧 onSizeChanged 触发重组
  val canvasSize = remember { FloatArray(2) }

  // 帧回调是一次性启动的长跑协程，闭包里的 settings 会发霉，得走 rememberUpdatedState
  val latestSettings = rememberUpdatedState(settings)

  // 把配置推进那个「非快照」的模拟对象。
  // 注意这里**没有**因为方向变化而重投粒子 —— mfosu 也只对「粒子数」做 Restart，
  // 换方向时粒子是从当前位置直接改走新方向的。
  SideEffect {
    field.globalSpeed = settings.globalSpeed
    field.energyGain = settings.energyGain
    field.baseAlpha = settings.opacity
    field.direction = settings.direction
    field.resize(settings.count)
  }

  LaunchedEffect(field) {
    var lastNanos = 0L
    while (true) {
      withFrameNanos { now ->
        // 实测帧率：withFrameNanos 由 Choreographer 驱动，
        // 所以这个值就是「本窗口实际跑到的垂直同步帧率」
        FrameStats.onFrame(now)

        val dtMs = if (lastNanos == 0L) FALLBACK_DT_MS else (now - lastNanos) / 1_000_000f
        lastNanos = now

        // 尺寸基准按**画布宽度**算，而不是按 dp：
        // mfosu 的 0.5~4px 是画在约 500px 宽的面板上的（即宽度的 0.1%~0.8%），
        // 只有按宽度等比换算才能在 2560px 的屏幕上保持同样的相对观感。
        field.sizeUnitPx = canvasSize[0] / ParticleField.SIZE_REFERENCE_WIDTH *
          latestSettings.value.sizeScale

        // 上限 100ms：从后台切回来时不要一下子跳过好几秒的模拟
        field.advance(
          dtRealMs = dtMs.coerceIn(0f, MAX_DT_MS),
          energy = energy.energy,
          widthPx = canvasSize[0],
          heightPx = canvasSize[1],
        )
        revision.intValue++
      }
    }
  }

  Canvas(
    modifier = modifier
      .fillMaxSize()
      .onSizeChanged {
        canvasSize[0] = it.width.toFloat()
        canvasSize[1] = it.height.toFloat()
      },
  ) {
    drawParticleField(field, revision.intValue)
  }
}

/** 低于这个不透明度就不用画了（1/255 ≈ 0.0039，肉眼已不可见） */
private const val MIN_VISIBLE_ALPHA = 0.004f

private const val FALLBACK_DT_MS = 16f
private const val MAX_DT_MS = 100f

/**
 * 画一帧粒子。
 *
 * @param frame **不参与绘制**，只是为了让本函数在 draw 阶段读一次 [revision]，
 *   从而建立「帧计数变化 → 重绘」的依赖。见 [ParticleFieldView] 的注释。
 */
private fun DrawScope.drawParticleField(field: ParticleField, frame: Int) {
  val count = field.count
  val w = size.width
  val h = size.height
  if (count <= 0 || w <= 0f || h <= 0f) return

  val buf = field.renderBuffer()
  for (i in 0 until count) {
    val o = i shl 2
    val alpha = buf[o + 3]
    if (alpha <= MIN_VISIBLE_ALPHA) continue

    val radius = buf[o + 2] * 0.5f
    val cx = buf[o] * w
    val cy = buf[o + 1] * h
    if (cx + radius < 0f || cy + radius < 0f || cx - radius > w || cy - radius > h) continue

    // 圆形粒子。这个形状也更贴近 mfosu 的原实现 ——
    // 它每颗贴的是一张 40×40 的**软圆点**图，而不是方块。
    // 颜色固定用 [VizStyle.Fill]（纯白）：mfosu 的 `ParticlesColour` 虽然可配，
    // 但默认就是 #ffffff，且本 app 的既有风格是「所有图形纯白」。
    drawCircle(
      color = VizStyle.Fill.copy(alpha = alpha),
      radius = radius,
      center = Offset(cx, cy),
    )
  }
}
