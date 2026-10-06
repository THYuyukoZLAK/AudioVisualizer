package top.aerohaku.androidapp.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sin

/**
 * 把 `NORMALIZED` 的数据搬回内容刻度。
 *
 * 要守住的核心性质是四个：
 * 1. 缩放量 = 「框架绝对 RMS − 设备音量 − 我们量到的 RMS」，乘上去之后块 RMS 应等于
 *    内容自己的绝对 RMS；
 * 2. **缩放量恒 ≤ 0**（归一化只会把信号抬上去）—— 也就是说只会把信号压回去，
 *    绝不会放大，因此不会放大量化噪声；
 * 3. **减掉设备音量之后，读数与音量档位无关**（框架量到的是音量之后的信号）；
 * 4. 静音时不动（框架在静音时返回 -96dB，拿它算缩放量没有意义）。
 */
class MixLevelAnchorTest {

  private fun tone(amplitude: Float, count: Int = 1024): ShortArray =
    ShortArray(count) { (sin(it * 0.1) * amplitude * 32767f).toInt().toShort() }

  @Test
  fun `缩放后块 RMS 等于框架的绝对 RMS`() {
    val anchor = MixLevelAnchor()
    // 归一化后的数据看着很响（约 -6dB），但真实只有 -26dB
    val buffer = tone(0.5f)
    val ourDb = rmsDbOf(buffer, buffer.size)

    repeat(200) { anchor.update(absoluteRmsDb = -26f, ourRmsDb = ourDb, dtMs = 20f) }
    anchor.apply(buffer, buffer.size)

    assertEquals(-26f, rmsDbOf(buffer, buffer.size), 0.5f)
  }

  @Test
  fun `缩放量恒不大于 1 —— 只会压回去，绝不放大`() {
    // 边界情况：绝对 RMS 比我们量到的还大（理论上不会发生）
    val anchor = MixLevelAnchor()
    repeat(500) { anchor.update(absoluteRmsDb = 0f, ourRmsDb = -40f, dtMs = 20f) }

    assertEquals(1f, anchor.scale, 1e-6f)
  }

  @Test
  fun `安静时几乎不缩放`() {
    val anchor = MixLevelAnchor()
    val buffer = tone(0.5f)
    val ourDb = rmsDbOf(buffer, buffer.size)

    repeat(200) { anchor.update(absoluteRmsDb = ourDb, ourRmsDb = ourDb, dtMs = 20f) }

    assertEquals(1f, anchor.scale, 1e-3f)
  }

  @Test
  fun `静音时不更新`() {
    val anchor = MixLevelAnchor()

    repeat(500) { anchor.update(absoluteRmsDb = -96f, ourRmsDb = -96f, dtMs = 20f) }

    assertEquals(1f, anchor.scale, 1e-6f)
  }

  @Test
  fun `压回去的幅度有下限`() {
    val anchor = MixLevelAnchor()
    repeat(2000) { anchor.update(absoluteRmsDb = -90f, ourRmsDb = -6f, dtMs = 20f) }

    val limit = 10f.pow(-MixLevelAnchor.DEFAULT_MAX_CUT_DB / 20f)
    assertTrue("scale=${anchor.scale} 超出了 $limit", anchor.scale >= limit - 1e-6f)
  }

  @Test
  fun `复位后回到不缩放`() {
    val anchor = MixLevelAnchor()
    repeat(200) { anchor.update(absoluteRmsDb = -40f, ourRmsDb = -6f, dtMs = 20f) }
    assertTrue(anchor.scale < 0.9f)

    anchor.reset()

    assertEquals(1f, anchor.scale, 1e-6f)
  }

  @Test
  fun `静音段落的 RMS 读数走下限`() {
    val silent = ShortArray(1024)
    assertEquals(SILENT_DB, rmsDbOf(silent, silent.size), 1e-4f)
  }

  @Test
  fun `减掉设备音量后，读数与音量档位无关`() {
    // 同一段内容：音量满档时框架量到 -14dB，音量档衰减 29dB 时只量到 -43dB。
    // 我们这边（归一化后的）数据本来就与音量无关，两次都是同一块。
    val loud = MixLevelAnchor()
    val quiet = MixLevelAnchor()
    repeat(400) {
      loud.update(absoluteRmsDb = -14f, ourRmsDb = -13f, dtMs = 20f, volumeDb = 0f)
      quiet.update(absoluteRmsDb = -43f, ourRmsDb = -13f, dtMs = 20f, volumeDb = -29f)
    }

    assertEquals(loud.scaleDb, quiet.scaleDb, 1e-3f)
  }

  @Test
  fun `减掉设备音量后，读数落在内容自己的刻度上`() {
    val anchor = MixLevelAnchor()
    val buffer = tone(0.5f)
    val ourDb = rmsDbOf(buffer, buffer.size)

    // 内容自己的块 RMS 是 -10dB，输出衰减 30dB ⇒ 框架量到的是 -40dB
    repeat(400) { anchor.update(absoluteRmsDb = -40f, ourRmsDb = ourDb, dtMs = 20f, volumeDb = -30f) }
    anchor.apply(buffer, buffer.size)

    assertEquals(-10f, rmsDbOf(buffer, buffer.size), 0.5f)
  }

  @Test
  fun `不传设备音量时，刻度就是输出的绝对值`() {
    val anchor = MixLevelAnchor()
    repeat(400) { anchor.update(absoluteRmsDb = -43f, ourRmsDb = -13f, dtMs = 20f) }

    assertEquals(-43f, -13f + anchor.scaleDb, 0.5f)
  }

  @Test
  fun `音量估计偏小到「想放大」时，被硬保证拦住`() {
    val anchor = MixLevelAnchor()
    // 内容比我们量到的还响（音量估计偏小），理想缩放量为正
    repeat(400) { anchor.update(absoluteRmsDb = -6f, ourRmsDb = -13f, dtMs = 20f, volumeDb = 0f) }

    assertEquals(1f, anchor.scale, 1e-6f)
  }

  @Test
  fun `离谱的音量衰减会被夹到上限内`() {
    val anchor = MixLevelAnchor()
    repeat(400) { anchor.update(absoluteRmsDb = -13f, ourRmsDb = -13f, dtMs = 20f, volumeDb = -10000f) }

    assertEquals(1f, anchor.scale, 1e-6f)
    assertTrue(anchor.scale <= 1f)
  }
}
