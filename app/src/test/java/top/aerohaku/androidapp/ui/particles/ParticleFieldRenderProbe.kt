package top.aerohaku.androidapp.ui.particles

import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 临时的离线渲染探针（不是断言型测试）：
 * 把粒子场直接光栅化成 PNG，用来肉眼确认「粒子真的画得出来、分布合理、随能量变化」。
 *
 * 单元测试能证明数值不变量，但证明不了「看起来有没有东西」——
 * 比如 alpha 阈值算错会导致整场全透明，而所有断言依然通过。
 */
class ParticleFieldRenderProbe {

  private companion object {
    const val W = 960
    const val H = 540

    /** 与真机一致的尺寸基准：画布宽度 / 500（mfosu 的参考面板宽度） */
    const val SIZE_UNIT_PX = W / ParticleField.SIZE_REFERENCE_WIDTH

    /** 与产品默认值一致，让预览图的观感就是真机的观感 */
    const val DEFAULT_SIZE_SCALE = ParticleSettings.DEFAULT_SIZE_SCALE
    const val DEFAULT_OPACITY = ParticleSettings.DEFAULT_OPACITY

    const val OUT_DIR = "build/particle-preview"
  }

  private fun render(field: ParticleField, out: File) {
    val image = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color(34, 40, 52) // 近似「暗化后的封面背景」
    g.fillRect(0, 0, W, H)

    val buf = field.renderBuffer()
    for (i in 0 until field.count) {
      val o = i shl 2
      val alpha = buf[o + 3]
      if (alpha <= 0.004f) continue
      val s = buf[o + 2]
      val d = maxOf(1, Math.round(s))
      val x = Math.round(buf[o] * W - s * 0.5f)
      val y = Math.round(buf[o + 1] * H - s * 0.5f)
      val c = (255 * alpha).toInt().coerceIn(0, 255)
      g.color = Color(c, c, c)
      g.fillOval(x, y, d, d)
    }
    g.dispose()

    out.parentFile?.mkdirs()
    ImageIO.write(image, "png", out)
  }

  @Test
  fun `渲染若干帧供人工查看`() {
    val field = ParticleField()
    field.resize(ParticleField.DEFAULT_COUNT)
    field.direction = ParticleDirection.RANDOM
    field.sizeUnitPx = SIZE_UNIT_PX * DEFAULT_SIZE_SCALE
    field.baseAlpha = DEFAULT_OPACITY
    field.energyGain = 1f
    field.restart()

    // 静音：应该几乎全黑（粒子冻结 + 还没淡入）
    field.advance(16f, energy = 0f, widthPx = W.toFloat(), heightPx = H.toFloat())
    render(field, File("$OUT_DIR/00-silence.png"))

    // 中等音量跑 120 帧
    repeat(120) { field.advance(16f, energy = 3f, widthPx = W.toFloat(), heightPx = H.toFloat()) }
    render(field, File("$OUT_DIR/01-mid.png"))

    // 大声跑 60 帧：同样的真实时间，但应该明显跑得更远
    repeat(60) { field.advance(16f, energy = 12f, widthPx = W.toFloat(), heightPx = H.toFloat()) }
    render(field, File("$OUT_DIR/02-loud.png"))

    // 换个方向看看。
    // 注意：粒子尺寸按画布宽度等比缩放，所以「覆盖率」与分辨率无关 ——
    // 本预览（宽 960）看到的密度就是 2560×1600 真机上同样的密度。
    val forward = ParticleField()
    forward.resize(ParticleField.DEFAULT_COUNT)
    forward.direction = ParticleDirection.FORWARD
    forward.sizeUnitPx = SIZE_UNIT_PX * DEFAULT_SIZE_SCALE
    forward.baseAlpha = DEFAULT_OPACITY
    forward.energyGain = 1f
    forward.restart()
    repeat(150) { forward.advance(16f, energy = 4f, widthPx = W.toFloat(), heightPx = H.toFloat()) }
    render(forward, File("$OUT_DIR/03-forward.png"))

    // 可见粒子占比（断言一下别整场透明）
    val buf = forward.renderBuffer()
    val visible = (0 until forward.count).count { buf[it * 4 + 3] > 0.02f }
    println("PROBE forward 可见粒子 = $visible / ${forward.count}")
    val sizes = (0 until forward.count).map { buf[it * 4 + 2] }
    println("PROBE 尺寸范围 = ${sizes.min()} .. ${sizes.max()}")
    println("PROBE 输出目录 = ${File(OUT_DIR).absolutePath}")
    check(visible > 0) { "Forward 方向应有可见粒子" }
  }
}
