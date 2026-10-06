package top.aerohaku.androidapp.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局文本的编解码（导出 / 导入）。
 *
 * 这套文本要经手剪贴板与聊天软件，中间还会被人手改，所以两条底线必须守住：
 * 一是导出再导入不能变形，二是文本里**没写**的字段不能沾上调用方现有布局的残留
 * —— 后者是导入功能的正确性基础，导入只该生成一份新预设，不该碰到当前布局。
 *
 * 解析里的边界（缺字段、脏值、行号报错）靠手点很难覆盖全，所以单独测。
 */
class LayoutTextTest {

  private fun okay(text: String): LayoutParseResult.Ok =
    parseLayoutText(text) as? LayoutParseResult.Ok
      ?: error("本该解析成功，实际是：${(parseLayoutText(text) as LayoutParseResult.Failed).message}")

  private fun reason(text: String): String =
    (parseLayoutText(text) as? LayoutParseResult.Failed)?.message
      ?: error("本该解析失败，实际成功了")

  // ------------------------------------------------------------------ 往返

  @Test
  fun `导出再导入逐字段一致`() {
    // 故意每项都设成非默认值，否则「一致」可能只是双方都用了默认值
    val configs = VisualizerWidget.entries.associateWith { widget ->
      WidgetConfig(
        anchor = AnchorPoint.CENTER,
        offsetX = 12.dp,
        offsetY = -34.dp,
        width = 100.dp,
        height = 56.dp,
        trailingInset = 8.dp,
        enabled = false,
        fontScale = 1.25f,
        color = 0xFFFF3B30.toInt(),
        alpha = 0.35f,
        lockAspect = true,
        gain = 2.5f,
      )
    }

    val parsed = okay(formatLayoutText(LyricsAlign.END, configs))

    assertEquals(LyricsAlign.END, parsed.align)
    assertEquals(configs, parsed.configs)
  }

  @Test
  fun `撑满的宽高不写进行文本并原样读回`() {
    val configs = mapOf(
      VisualizerWidget.LYRICS to WidgetConfig(width = null, height = null),
      VisualizerWidget.PROGRESS to WidgetConfig(width = null, height = 9.dp),
    )

    val text = formatLayoutText(LyricsAlign.START, configs)

    assertTrue(
      "撑满的宽度不该写 w 行（写个魔法值会让别人手改时看不懂）",
      text.lineSequence().none { it.startsWith("LYRICS.w=") },
    )
    assertTrue(text.lineSequence().none { it.startsWith("LYRICS.h=") })
    assertTrue(text.lineSequence().any { it == "PROGRESS.h=9.0" })

    val parsed = okay(text).configs
    assertNull(parsed.getValue(VisualizerWidget.LYRICS).width)
    assertNull(parsed.getValue(VisualizerWidget.LYRICS).height)
    assertNull(parsed.getValue(VisualizerWidget.PROGRESS).width)
    assertEquals(9.dp, parsed.getValue(VisualizerWidget.PROGRESS).height)
  }

  // ------------------------------------------------------------------ 没写的字段

  @Test
  fun `文本里没写的字段取出厂默认而不是上一套的残留`() {
    // 只有 x 被指定；其余字段（颜色、字号、透明度、锚点…）都必须是出厂默认，
    // 否则「导入别人的布局」会把他没设的东西变成我当前的设置 —— 那就不叫导入了
    val parsed = okay(
      """
      AVL-LAYOUT-1
      align=START
      LYRICS.x=10.0
      """.trimIndent(),
    )

    assertEquals(
      WidgetConfig(offsetX = 10.dp),
      parsed.configs.getValue(VisualizerWidget.LYRICS),
    )
  }

  @Test
  fun `文本没写对齐时返回 null 交由调用方兜底`() {
    // 对齐是全局项、不属于任何单个部件。返回 null 而不是假装成 START，
    // 这样导入端才能决定「沿用我当前的」而不是被静默改掉
    assertNull(okay("AVL-LAYOUT-1\nLYRICS.y=1.0").align)
  }

  // ------------------------------------------------------------------ 脏输入

  @Test
  fun `容忍前后空行与 CRLF`() {
    // 从聊天软件里复制出来的文本经常长这样
    val text = "\r\n\r\nAVL-LAYOUT-1\r\nLYRICS.y=7.0\r\n\r\n"
    assertEquals(7.dp, okay(text).configs.getValue(VisualizerWidget.LYRICS).offsetY)
  }

  @Test
  fun `不认识的行跳过而不是整段失败`() {
    val parsed = okay(
      """
      AVL-LAYOUT-1
      align=START
      FUTURE_WIDGET.x=5.0
      LYRICS.y=7.0
      LYRICS.z=7.0
      """.trimIndent(),
    )

    val lyrics = parsed.configs.getValue(VisualizerWidget.LYRICS)
    assertEquals(7.dp, lyrics.offsetY)
    assertEquals(0.dp, lyrics.offsetX)
  }

  @Test
  fun `越界的字号透明度增益被夹回合法区间`() {
    val lyrics = okay(
      """
      AVL-LAYOUT-1
      LYRICS.font=99.0
      LYRICS.gain=0.0
      LYRICS.alpha=-1.0
      """.trimIndent(),
    ).configs.getValue(VisualizerWidget.LYRICS)

    assertEquals(MAX_FONT_SCALE, lyrics.fontScale, 0f)
    assertEquals(MIN_GAIN, lyrics.gain, 0f)
    assertEquals(MIN_ALPHA, lyrics.alpha, 0f)
  }

  @Test
  fun `认不出的颜色不会污染默认色`() {
    val lyrics = okay(
      """
      AVL-LAYOUT-1
      LYRICS.color=zhop
      LYRICS.y=1.0
      """.trimIndent(),
    ).configs.getValue(VisualizerWidget.LYRICS)

    assertEquals(DEFAULT_WIDGET_COLOR, lyrics.color)
  }

  // ------------------------------------------------------------------ 拒绝

  @Test
  fun `空文本被拒`() {
    assertEquals("内容是空的", reason("   \n\n  "))
  }

  @Test
  fun `抬头不对时给出一句能照做的提示`() {
    assertTrue(reason("{\"LYRICS\":{}}").contains(EXPORT_HEADER))
  }

  @Test
  fun `坏行报出的行号与原文一致`() {
    // 抬头 1 行 + align 1 行 + 坏行 → 第 3 行。行号报错是最容易被数错的地方
    val message = reason("AVL-LAYOUT-1\nalign=START\n这行没有等号")
    assertTrue("应当指出第 3 行，实际：$message", message.contains("第 3 行"))
  }

  @Test
  fun `全是认不出的键时明确报错`() {
    assertEquals("文本里没有任何认识的配置项", reason("AVL-LAYOUT-1\nFOO=1\nBAR=2"))
  }
}
