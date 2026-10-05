package top.aerohaku.androidapp.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * 用户预设的**自动命名**规则。
 *
 * 这套逻辑不难，但错法很隐蔽：如果把编号取成「预设个数 + 1」，
 * 删掉中间某个之后就会撞名（留下两个「用户预设 - 2」）。
 */
class UserPresetNamingTest {

  @Test
  fun `没有历史预设时从 1 开始`() {
    assertEquals(1, nextUserPresetIndex(emptyList()))
  }

  @Test
  fun `取已用过的最大编号加一而不是个数加一`() {
    // 已经有 1 和 3（2 被删了）→ 下一个必须是 4，不能因为「只有两个」就给 3
    assertEquals(4, nextUserPresetIndex(listOf("用户预设 - 1", "用户预设 - 3")))
  }

  @Test
  fun `连续编号时逐个递增`() {
    assertEquals(3, nextUserPresetIndex(listOf("用户预设 - 1", "用户预设 - 2")))
  }

  @Test
  fun `不认自定义名字里的数字`() {
    // 只有完整的「用户预设 - 数字」才参与编号，别的名字里出现数字不算
    assertEquals(1, nextUserPresetIndex(listOf("我的布局 2", "预设-5", "用户预设")))
  }

  @Test
  fun `容忍连字符两侧空格数不一致`() {
    val names = listOf("用户预设 - 5", "用户预设-3", "用户预设 -  4", "用户预设 -  7")
    assertEquals(8, nextUserPresetIndex(names))
  }

  @Test
  fun `默认备注就是创建时间`() {
    val at = LocalDateTime.of(2026, 10, 6, 9, 5)
    assertEquals("2026-10-06 09:05", defaultPresetNote(at))
  }
}
