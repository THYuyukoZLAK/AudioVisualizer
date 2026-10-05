package top.aerohaku.androidapp.ui.particles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 粒子场的模拟测试。
 *
 * 移植的正确性主要靠三条不变量：
 * 1. **静音 = 冻结**（mfosu 的 `Rate = Σ幅度`，Σ 为 0 时时钟不走）
 * 2. **时间流速正比于能量**（两倍能量跑一半步数，结果必须逐位相同）
 * 3. **粒子永远留在画布内**（出界就重投，不会跑丢也不会消失）
 *
 * 大部分用例用 [ParticleDirection.FORWARD]：它重投时 depth 恒为 `MAX_DEPTH`，
 * 所以整场是确定性的。`RANDOM` / `FROM_*` 的 depth 是随机的，
 * 偶尔会有粒子恰好落在极小 depth 上（位移过大 → 同一步内就被重投），
 * 断言里带随机性容易变成偶发红。
 */
class ParticleFieldTest {

  private companion object {
    const val W = 2000f
    const val H = 1200f
    const val DT = 16f
    const val COUNT = 120
  }

  private fun newField(dir: ParticleDirection): ParticleField =
    ParticleField().apply {
      resize(COUNT)
      direction = dir
      restart()
    }

  // ------------------------------------------------------------------ 音频耦合

  @Test
  fun `静音时粒子完全冻结`() {
    val field = newField(ParticleDirection.FORWARD)

    field.advance(DT, energy = 0f, widthPx = W, heightPx = H)
    val frozen = field.renderBuffer().copyOf()

    repeat(60) { field.advance(DT, energy = 0f, widthPx = W, heightPx = H) }

    assertArrayEquals("静音时位置/尺寸都不该变", frozen, field.renderBuffer(), 0f)
    assertEquals(0f, field.lastRate, 0f)
    assertEquals("模拟时间也不该走", 0f, field.simulatedTimeMs, 0f)
  }

  @Test
  fun `能量倍率为 0 时退化成不动的背景`() {
    val field = newField(ParticleDirection.FROM_LEFT)
    field.energyGain = 0f

    val frozen = field.renderBuffer().copyOf()
    repeat(30) { field.advance(DT, energy = 100f, widthPx = W, heightPx = H) }

    assertArrayEquals(frozen, field.renderBuffer(), 0f)
    assertEquals(0f, field.lastRate, 0f)
  }

  /**
   * 移植的核心：`Clock.Rate = Σamplitudes`。
   * 所以「2 倍能量」必须与「跑一半的步数」完全等价。
   *
   * 断言的是 [ParticleField.simulatedTimeMs] 而不是粒子坐标 ——
   * 因为坐标侧还有「出界重投」这个随机副作用，
   * 两个场的某个粒子在同一时刻撞边界与在下一步撞边界，结果就会分叉。
   * 模拟时间不受它影响，是这条耦合的可观测量。
   */
  @Test
  fun `时间流速正比于能量（双倍能量跑一半步数结果相同）`() {
    val slow = newField(ParticleDirection.FORWARD).apply { energyGain = 1f }
    val fast = newField(ParticleDirection.FORWARD).apply { energyGain = 1f }

    repeat(10) { slow.advance(DT, energy = 3f, widthPx = W, heightPx = H) }
    repeat(5) { fast.advance(DT, energy = 6f, widthPx = W, heightPx = H) }

    // 10 步 × 16ms × 3 倍速 = 480ms
    assertEquals(480f, slow.simulatedTimeMs, 1e-2f)
    assertEquals(slow.simulatedTimeMs, fast.simulatedTimeMs, 1e-2f)
  }

  @Test
  fun `位移随能量线性放大`() {
    val single = newField(ParticleDirection.FROM_LEFT)
    val double = newField(ParticleDirection.FROM_LEFT)
    // 放慢整体速度，把一步的位移压到远小于半屏
    single.globalSpeed = 10
    double.globalSpeed = 10

    val before = single.renderBuffer().copyOf()
    single.advance(DT, energy = 1f, widthPx = W, heightPx = H)
    double.advance(DT, energy = 2f, widthPx = W, heightPx = H)

    val a = single.renderBuffer()
    val b = double.renderBuffer()
    var compared = 0
    for (i in 0 until COUNT) {
      val da = a[i * 4] - before[i * 4]
      val db = b[i * 4] - before[i * 4]
      // RANDOM / FROM_* 的 depth 是随机的，偶尔会有粒子落在极小 depth 上，
      // 一步就冲出画面被重投 —— 这种粒子跳过
      if (abs(da) > 0.1f || abs(db) > 0.1f) continue

      assertTrue("第 $i 颗粒子应有正向位移，实际 $da", da > 0f)
      assertEquals("位移应正比于能量", 2f, db / da, 0.02f)
      compared++
    }
    assertTrue("应覆盖绝大多数粒子，实际 $compared/$COUNT", compared >= COUNT * 9 / 10)
  }

  @Test
  fun `速率与能量成正比并被上限钳制`() {
    val field = ParticleField()
    field.energyGain = 1f

    assertEquals(0f, field.rateFor(0f), 0f)
    assertEquals(1f, field.rateFor(1f), 1e-6f)
    assertEquals(2f, field.rateFor(2f), 1e-6f)

    field.energyGain = 8f
    assertEquals(8f, field.rateFor(1f), 1e-5f)
    assertEquals(0f, field.rateFor(0f), 0f)

    // 不钳的话一次能量尖峰就能把粒子瞬移出画面
    assertEquals(ParticleField.MAX_RATE, field.rateFor(1e6f), 1e-5f)
  }

  // ------------------------------------------------------------------ 空间不变量

  @Test
  fun `长时间运行后所有方向的粒子都留在画布内`() {
    ParticleDirection.entries.forEach { dir ->
      val field = newField(dir)
      // 400 步 × 8 倍速，足以让 Forward / Backwards 反复走完整个 depth 区间
      repeat(400) { field.advance(DT, energy = 8f, widthPx = W, heightPx = H) }

      val buf = field.renderBuffer()
      for (i in 0 until COUNT) {
        val x = buf[i * 4]
        val y = buf[i * 4 + 1]
        assertTrue("$dir 第 $i 颗粒子的 x=$x 越界", x >= -0.001f && x <= 1.001f)
        assertTrue("$dir 第 $i 颗粒子的 y=$y 越界", y >= -0.001f && y <= 1.001f)
      }
    }
  }

  @Test
  fun `尺寸与不透明度始终合法`() {
    ParticleDirection.entries.forEach { dir ->
      val field = newField(dir)
      repeat(50) { field.advance(DT, energy = 5f, widthPx = W, heightPx = H) }

      val buf = field.renderBuffer()
      for (i in 0 until COUNT) {
        val size = buf[i * 4 + 2]
        val alpha = buf[i * 4 + 3]
        assertTrue("$dir 第 $i 颗粒子尺寸 $size 非法", size > 0f)
        assertTrue("$dir 第 $i 颗粒子透明度 $alpha 非法", alpha >= 0f && alpha <= 1f)
      }
    }
  }

  /**
   * mfosu 的 `Particle` 构造函数走的是 `reset(Forward, false)` —— **随机深度**。
   *
   * 这条曾经被我误判为「不可达」而省掉，结果初始状态是「全部粒子压在 depth = 1000」：
   * 尺寸取下限 0.5u、`alpha` 恰好等于 0，**画面一片空白**，要等音乐响才慢慢淡入。
   * 真机截图才发现。所以这条用例盯的就是它。
   */
  @Test
  fun `初始化后粒子已散布在各深度而不是全部压在最近处`() {
    val field = newField(ParticleDirection.FORWARD)
    field.sizeUnitPx = 2f
    field.advance(dtRealMs = 0f, energy = 0f, widthPx = W, heightPx = H)

    val buf = field.renderBuffer()
    val sizes = (0 until COUNT).map { buf[it * 4 + 2] }
    assertTrue(
      "尺寸应有层次，实际 ${sizes.min()} .. ${sizes.max()}",
      sizes.max() - sizes.min() > 1f,
    )

    val visible = (0 until COUNT).count { buf[it * 4 + 3] > 0.2f }
    assertTrue("初始化后应有大量可见粒子，实际 $visible/$COUNT", visible >= COUNT / 3)
  }

  // ------------------------------------------------------------------ 配置

  @Test
  fun `粒子数被钳制且渲染缓冲长度跟随`() {
    val field = ParticleField()

    field.resize(1)
    assertEquals(ParticleField.MIN_COUNT, field.count)

    field.resize(999_999)
    assertEquals(ParticleField.MAX_COUNT, field.count)
    assertEquals(ParticleField.MAX_COUNT * 4, field.renderBuffer().size)
  }

  @Test
  fun `尺寸倍率线性放大渲染尺寸`() {
    val field = newField(ParticleDirection.FORWARD)

    field.sizeUnitPx = 1f
    field.advance(dtRealMs = 0f, energy = 0f, widthPx = W, heightPx = H)
    val base = field.renderBuffer()[2]

    field.sizeUnitPx = 4f
    field.advance(dtRealMs = 0f, energy = 0f, widthPx = W, heightPx = H)
    val scaled = field.renderBuffer()[2]

    assertTrue(base > 0f)
    assertEquals(4f, scaled / base, 1e-3f)
  }

  @Test
  fun `整体不透明度应等比缩放到每颗粒子的 alpha`() {
    val field = newField(ParticleDirection.FORWARD)
    field.sizeUnitPx = 2f

    field.baseAlpha = 1f
    field.advance(dtRealMs = 0f, energy = 0f, widthPx = W, heightPx = H)
    val full = field.renderBuffer().copyOf()

    field.baseAlpha = 0.5f
    field.advance(dtRealMs = 0f, energy = 0f, widthPx = W, heightPx = H)
    val half = field.renderBuffer()

    for (i in 0 until COUNT) {
      assertEquals("第 $i 颗粒子的 alpha", full[i * 4 + 3] * 0.5f, half[i * 4 + 3], 1e-5f)
      // 位置与尺寸不受不透明度影响
      assertEquals(full[i * 4], half[i * 4], 0f)
      assertEquals(full[i * 4 + 2], half[i * 4 + 2], 0f)
    }
  }

  /**
   * `dtRealMs = 0` 时位置不动，但属性仍要重算 ——
   * 否则静音时拖「粒子尺寸」滑杆会看起来没反应。
   */
  @Test
  fun `零时长推进仍会刷新尺寸与透明度`() {
    val field = newField(ParticleDirection.FORWARD)
    field.advance(DT, energy = 4f, widthPx = W, heightPx = H)

    val before = field.renderBuffer().copyOf()
    field.sizeUnitPx = 3f
    field.advance(dtRealMs = 0f, energy = 4f, widthPx = W, heightPx = H)

    val after = field.renderBuffer()
    // 位置不该变
    for (i in 0 until COUNT) {
      assertEquals(before[i * 4], after[i * 4], 0f)
      assertEquals(before[i * 4 + 1], after[i * 4 + 1], 0f)
    }
    // 尺寸应该变了
    assertTrue("尺寸应被重算", after[2] > before[2])
  }
}
