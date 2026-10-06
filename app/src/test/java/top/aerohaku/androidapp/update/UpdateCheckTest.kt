package top.aerohaku.androidapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」里**不需要网络**的那部分。
 *
 * ‼️ 这里只测纯函数（版本号解析/比较、结果判定、字段清洗与兜底），**不测 `parseRelease`**：
 * `org.json` 属于 android.jar，在本地 JVM 单测里是桩（调用直接抛 `Stub!`），
 * 所以 `parseRelease` 里只有取值调用、没有任何判断，判断全在它调的纯函数里。
 * 网络路径（超时、403 限流、连接被重置）同样只能在真机上验。
 */
class UpdateCheckTest {

  @Test
  fun `版本号解析`() {
    assertEquals(listOf(1, 4), parseVersion("1.4"))
    assertEquals(listOf(1, 4, 0), parseVersion("v1.4.0"))
    assertEquals(listOf(2, 0), parseVersion("V2.0"))
    assertEquals(listOf(1, 4), parseVersion("  v1.4  "))
    // 后缀忽略掉（GitHub 上常见的 -beta1 / +build）
    assertEquals(listOf(1, 4), parseVersion("1.4-beta1"))
  }

  @Test
  fun `解析不出数字就返回 null`() {
    assertNull(parseVersion("nightly"))
    assertNull(parseVersion(""))
    assertNull(parseVersion("v"))
    assertNull(parseVersion("1.x"))
  }

  @Test
  fun `补零之后相等`() {
    assertEquals(0, compareVersions("1.4", "v1.4.0"))
    assertEquals(0, compareVersions("1.4.0", "1.4.0"))
  }

  @Test
  fun `逐段比大小而不是比字符串`() {
    // 按字符串比会认为 "1.10" < "1.9"，这是最经典的翻车点
    assertEquals(1, compareVersions("1.10", "1.9"))
    assertEquals(-1, compareVersions("1.4", "1.5"))
    assertEquals(-1, compareVersions("1.4", "2"))
    assertEquals(1, compareVersions("2.0", "1.99.99"))
  }

  @Test
  fun `有解析不了的一侧就返回 null`() {
    assertNull(compareVersions("1.4", "nightly"))
    assertNull(compareVersions("nightly", "1.4"))
  }

  @Test
  fun `远端更高才提示更新`() {
    val newer = release("v1.5")
    assertTrue(decideUpdate("1.4", newer) is UpdateCheckResult.UpdateAvailable)

    val same = release("v1.4")
    assertTrue(decideUpdate("1.4", same) is UpdateCheckResult.UpToDate)

    // 本地是自编的开发版，比远端还新 —— 不能反过来提示「降级」
    val older = release("v1.3")
    assertTrue(decideUpdate("1.4", older) is UpdateCheckResult.UpToDate)
  }

  @Test
  fun `tag 不是版本号时退化成字符串比较`() {
    val odd = release("nightly")
    assertTrue(decideUpdate("1.4", odd) is UpdateCheckResult.UpdateAvailable)
    assertTrue(decideUpdate("nightly", odd) is UpdateCheckResult.UpToDate)
  }

  @Test
  fun `JSON null 不能变成字符串 null 跑到界面上`() {
    // GitHub 允许「只打 tag、不写说明」，那时 body 是 JSON 的 null，
    // 而 optString() 会把它变成**字符串 "null"**（JSONObject.NULL.toString()）
    assertEquals("", normalizeJsonField("null"))
    assertEquals("", normalizeJsonField(null))
    assertEquals("", normalizeJsonField(""))
    assertEquals("", normalizeJsonField("  "))
    assertEquals("v1.5", normalizeJsonField("  v1.5  "))
  }

  @Test
  fun `字段缺失时退回兜底值`() {
    val info = releaseFrom(tag = "v1.5", name = "", notes = "", pageUrl = "")

    // name 缺失 → 退回 tag
    assertEquals("v1.5", info.name)
    // html_url 缺失 → 退回 Releases 页，保证退路按钮永远有得点
    assertEquals(RELEASES_URL, info.pageUrl)
    // body 缺失 → 空说明（界面据此不显示「展开更新说明」）
    assertEquals("", info.notes)
  }

  @Test
  fun `连 tag 都没有才算解析失败`() {
    try {
      releaseFrom(tag = "", name = "whatever", notes = "", pageUrl = "")
      throw AssertionError("没有 tag 时应该抛 MalformedReleaseException")
    } catch (expected: MalformedReleaseException) {
      // 预期
    }
  }

  private fun release(tag: String) =
    ReleaseInfo(tag = tag, name = tag, notes = "", pageUrl = RELEASES_URL)
}
