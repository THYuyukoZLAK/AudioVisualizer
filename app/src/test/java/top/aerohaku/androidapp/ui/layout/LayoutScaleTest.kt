package top.aerohaku.androidapp.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版式自动缩放的测试。
 *
 * 核心不变量只有两条：
 * 1. **装得下** —— 缩放后的设计画布不得超过可用区域（这正是小屏上「挤到一起」的根因）
 * 2. **只缩不放** —— 任何情况下都不超过设备自然密度，所以开发机上的观感逐像素不变
 */
class LayoutScaleTest {

  /** 开发机：联想 TB321FU，2560×1600 @2.125，去掉系统栏后 2560×1510 */
  private val devWidthPx = 2560
  private val devHeightPx = 1510
  private val devDensity = 2.125f

  @Test
  fun `开发机上缩放倍率应为 1（观感不变）`() {
    val density = LayoutScale.densityFor(devWidthPx, devHeightPx, devDensity)
    assertEquals(devDensity, density, 1e-3f)
    assertEquals(1f, LayoutScale.scaleFor(devWidthPx, devHeightPx, devDensity), 1e-3f)
  }

  @Test
  fun `横屏小屏应按高方向的限制等比缩小`() {
    // 2200×990px @2.75 的横屏手机：宽方向够用（1.827），高方向不够（1.394），取小者
    val density = LayoutScale.densityFor(2200, 990, 2.75f)
    assertEquals(990f / LayoutScale.REFERENCE_HEIGHT_DP, density, 1e-4f)
    assertTrue("应确实缩小了", density < 2.75f)
  }

  @Test
  fun `窄屏应按宽方向的限制缩小`() {
    // 1600×1200px @2.0：宽方向 1.329，高方向 1.690，取宽方向
    val density = LayoutScale.densityFor(1600, 1200, 2.0f)
    assertEquals(1600f / LayoutScale.REFERENCE_WIDTH_DP, density, 1e-4f)
  }

  @Test
  fun `大屏不放大`() {
    // 4K 平板：两方向都远超设计画布，应退回自然密度
    assertEquals(2.125f, LayoutScale.densityFor(6000, 3400, 2.125f), 1e-4f)
    assertEquals(0.5f, LayoutScale.densityFor(4000, 2000, 0.5f), 1e-4f)
  }

  @Test
  fun `无论什么尺寸，缩放后的设计画布都必须装得下`() {
    val sizes = listOf(
      2200 to 990, 1600 to 1200, 1280 to 720, 960 to 540,
      2560 to 1510, 3840 to 2160, 800 to 480, 3000 to 800,
    )
    for (density in listOf(1f, 1.5f, 2f, 2.125f, 2.75f, 3.5f)) {
      for ((w, h) in sizes) {
        val d = LayoutScale.densityFor(w, h, density)
        assertTrue(
          "密度非法：${w}x$h @$density → $d",
          d > 0f && d.isFinite(),
        )
        assertTrue(
          "宽装不下：${w}x$h @$density → $d（需要 ${LayoutScale.REFERENCE_WIDTH_DP * d}px）",
          LayoutScale.REFERENCE_WIDTH_DP * d <= w + 0.5f,
        )
        assertTrue(
          "高装不下：${w}x$h @$density → $d（需要 ${LayoutScale.REFERENCE_HEIGHT_DP * d}px）",
          LayoutScale.REFERENCE_HEIGHT_DP * d <= h + 0.5f,
        )
        assertTrue("不得超过自然密度：$d > $density", d <= density + 1e-6f)
      }
    }
  }

  @Test
  fun `异常输入应退回自然密度而不是崩掉`() {
    assertEquals(2f, LayoutScale.densityFor(0, 100, 2f), 1e-6f)
    assertEquals(2f, LayoutScale.densityFor(100, 0, 2f), 1e-6f)
    assertEquals(2f, LayoutScale.densityFor(-5, -5, 2f), 1e-6f)
    assertEquals(0f, LayoutScale.densityFor(1000, 1000, 0f), 1e-6f)
    assertEquals(1f, LayoutScale.scaleFor(1000, 1000, 0f), 1e-6f)
  }
}
