package top.aerohaku.androidapp.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LrcParser 是纯 JVM 逻辑（不依赖 Android），所以可以直接跑单元测试 */
class LrcParserTest {

  @Test
  fun `解析基本时间戳`() {
    val lines = LrcParser.parse(
      """
      [00:12.340]第一句
      [00:15.000]第二句
      [01:02.500]第三句
      """.trimIndent(),
    )
    assertEquals(3, lines.size)
    assertEquals(12_340L, lines[0].timeMs)
    assertEquals("第一句", lines[0].text)
    assertEquals(15_000L, lines[1].timeMs)
    assertEquals(62_500L, lines[2].timeMs)
  }

  @Test
  fun `支持冒号分隔的百分秒与无小数写法`() {
    val lines = LrcParser.parse("[00:01:50]A\n[00:02]B\n[00:03.5]C")
    assertEquals(1_500L, lines[0].timeMs)
    assertEquals(2_000L, lines[1].timeMs)
    assertEquals(3_500L, lines[2].timeMs)
  }

  @Test
  fun `跳过元数据行`() {
    val lines = LrcParser.parse(
      """
      [ti:标题]
      [ar:歌手]
      [by:某人]
      [00:01.000]正文
      """.trimIndent(),
    )
    assertEquals(1, lines.size)
    assertEquals("正文", lines[0].text)
  }

  @Test
  fun `一行多个时间戳会展开成多行`() {
    val lines = LrcParser.parse("[00:01.000][00:05.000]重复句")
    assertEquals(2, lines.size)
    assertEquals(1_000L, lines[0].timeMs)
    assertEquals(5_000L, lines[1].timeMs)
    assertTrue(lines.all { it.text == "重复句" })
  }

  @Test
  fun `空文本行被丢弃`() {
    val lines = LrcParser.parse("[00:01.000]\n[00:02.000]有内容")
    assertEquals(1, lines.size)
    assertEquals(2_000L, lines[0].timeMs)
  }

  @Test
  fun `应用 offset 偏移`() {
    val lines = LrcParser.parse("[offset:+500]\n[00:10.000]A")
    assertEquals(10_500L, lines[0].timeMs)
  }

  @Test
  fun `输出按时间排序`() {
    val lines = LrcParser.parse("[00:10.000]B\n[00:05.000]A")
    assertEquals(listOf("A", "B"), lines.map { it.text })
  }

  @Test
  fun `按时间戳合并翻译`() {
    val lines = LrcParser.parse(
      lrc = "[00:01.000]Hello\n[00:05.000]World",
      translation = "[00:01.000]你好\n[00:05.000]世界",
    )
    assertEquals("你好", lines[0].translation)
    assertEquals("世界", lines[1].translation)
  }

  @Test
  fun `翻译时间戳有轻微偏差也能合并`() {
    val lines = LrcParser.parse(
      lrc = "[00:01.000]Hello",
      translation = "[00:01.050]你好",
    )
    assertEquals("你好", lines[0].translation)
  }

  @Test
  fun `没有翻译时 translation 为 null`() {
    val lines = LrcParser.parse("[00:01.000]Hello")
    assertNull(lines[0].translation)
  }

  @Test
  fun `翻译与原文相同则不重复显示`() {
    val lines = LrcParser.parse(
      lrc = "[00:01.000]Hello",
      translation = "[00:01.000]Hello",
    )
    assertNull(lines[0].translation)
  }

  @Test
  fun `空输入返回空列表`() {
    assertTrue(LrcParser.parse(null).isEmpty())
    assertTrue(LrcParser.parse("").isEmpty())
    assertTrue(LrcParser.parse("   \n  ").isEmpty())
  }

  @Test
  fun `indexAt 二分查找`() {
    val lines = LrcParser.parse("[00:01.000]A\n[00:05.000]B\n[00:09.000]C")
    assertEquals(-1, LrcParser.indexAt(lines, 0))
    assertEquals(0, LrcParser.indexAt(lines, 1_000))
    assertEquals(0, LrcParser.indexAt(lines, 4_999))
    assertEquals(1, LrcParser.indexAt(lines, 5_000))
    assertEquals(2, LrcParser.indexAt(lines, 999_999))
    assertEquals(-1, LrcParser.indexAt(emptyList(), 1_000))
  }

  @Test
  fun `真实网易云歌词片段`() {
    val raw = """
      [00:00.000] 作词 : Aiobahn
      [00:01.000] 作曲 : Aiobahn
      [00:14.320]INTERNET OVERDOSE
      [00:18.560]ネットに依存する
    """.trimIndent()
    val lines = LrcParser.parse(raw)
    assertEquals(4, lines.size)
    assertEquals(14_320L, lines[2].timeMs)
    assertEquals("INTERNET OVERDOSE", lines[2].text)
  }
}
