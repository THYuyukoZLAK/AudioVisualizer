package top.aerohaku.androidapp.ui.particles

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 粒子的运动方向。命名沿用 mfosu 的 `ParticlesDirection`，
 * 但 `Left / Right / Up / Down` 在 mfosu 里指的是**从哪条边进入**（不是往哪走），
 * 所以这边改成了不会误读的 `FROM_*`。
 */
enum class ParticleDirection(val label: String, val description: String) {
  RANDOM("随机漂移", "每个粒子沿平滑噪声缓慢转向，不会看出重复"),
  FORWARD("迎面而来", "从深处冲向镜头，越近越大越快"),
  BACKWARDS("向远方去", "从镜头前退回深处"),
  FROM_LEFT("从左侧来", "贴着左边进入，向右横穿"),
  FROM_RIGHT("从右侧来", "贴着右边进入，向左横穿"),
  FROM_TOP("从上方来", "贴着上边进入，向下横穿"),
  FROM_BOTTOM("从下方来", "贴着下边进入，向上横穿"),
}

/**
 * 伪 3D 视差粒子场 —— 移植自 mfosu（osu! 插件 IGPlayer）的
 * `ParticlesDrawable` + `Particles` + `CurrentRateContainer`。
 *
 * ## mfosu 的原实现
 *
 * ```
 * Particle                     → 一个 depth ∈ [1, 1000] 的「点」
 * Particle.CurrentPosition     → 归一化坐标（-0.5 .. 0.5，屏幕中心为原点）
 * Particle.CurrentSize         → Map(depth, 1000 → 1,  0.5px → 4px)   越近越大
 * Particle.CurrentAlpha        → size < 1px 时从 0 淡入到 1       越近越实
 * reset() / outOfBounds        → 出界就换个位置重新投进来
 * ```
 *
 * **视差**来自 `baseComponent = max_depth / currentDepth × dt`：
 * 每颗粒子的位移都按 `1/depth` 缩放，所以近处的跑得飞快、远处的几乎不动，
 * 这就是「视差」的全部来源（不需要任何 3D 变换）。
 *
 * ## 「随音频运动」的真正来源
 *
 * 这一条藏在继承链里，只看 `ParticlesDrawable` 是看不出来的：
 *
 * ```
 * MusicAmplitudesProvider.Update()
 *   → OnAmplitudesUpdate(FrequencyAmplitudes)          // 256 格的瞬时幅度
 * MusicIntensityController
 *   → Intensity = amplitudes.Sum()                     // ★ 全部格子求和
 * CurrentRateContainer.LoadComplete()
 *   → intensityController.Intensity.BindValueChanged(rate => Rate = rate.NewValue)
 * RateAdjustableContainer
 *   → Rate = clock.Rate                                // 驱动一个私有 StopwatchClock
 * ParticlesDrawable.Update()
 *   → timeDiff = Clock.ElapsedFrameTime × 0.05         // ← 已经被 Rate 缩放过了
 * ```
 *
 * 也就是说：**粒子场的「时间流速」直接等于音频总能量**。
 * 安静时全场近乎冻结，鼓点一响所有粒子一起冲出去。
 *
 * 本类照搬这条链路，只用 [AudioFrame.energy] 替代 `ΣFrequencyAmplitudes`：
 * `dtSim = dtReal × energy × energyGain`。
 * 顺带一提，连 `Clock.CurrentTime` 也是被 Rate 缩放的 ——
 * 所以随机方向用的噪声相位在响的时候也走得更快，这里同样用 [simTimeMs] 复刻。
 *
 * ## 与 mfosu 的差异
 *
 * 1. **不用贴图**。mfosu 每颗粒子画一个 40×40 的软圆点贴图四边形，
 *    这里直接用 `drawCircle` 画实心圆 —— 省掉一张资源，
 *    形状上也贴近原实现（那张贴图本身就是个软圆点）。
 * 2. **噪声换成自实现的 1D 平滑值噪声**。mfosu 用 `OpenSimplexNoise`，
 *    真实观感只是「方向随时间平滑变化」，2D simplex 那个 150 行的梯度表对背景尘埃毫无意义。
 * 3. **尺寸单位是 dp**。mfosu 的 0.5~4px 是画在一小块面板上的；
 *    直接照搬到 2560×1600 全屏会细到看不见，所以以 1dp 为基准单位再乘用户倍率。
 * 4. **速率钳到 [[MAX_RATE]]**。mfosu 不钳，能量求和的绝对标度一旦偏大就会出现瞬移。
 *
 * ## 线程
 *
 * 只在 UI 线程的帧回调里被 [advance]；内部全是预分配的 `FloatArray`，逐帧零分配。
 */
class ParticleField {

  companion object {
    /** depth 的取值区间：数值越小＝离镜头越近 */
    const val MIN_DEPTH = 1f
    const val MAX_DEPTH = 1000f

    /** 粒子在屏幕上的尺寸区间（单位：由 [sizeUnitPx] 定义的基准单位） */
    const val PARTICLE_MIN_SIZE = 0.5f
    const val PARTICLE_MAX_SIZE = 4f

    /**
     * 尺寸基准：mfosu 的 `0.5~4px` 是画在约 500px 宽的面板（TypeB 画布）上的，
     * 也就是**画面宽度的 0.1% ~ 0.8%**。
     *
     * 所以 [sizeUnitPx] 应当取 `画布宽度 / 这个值`，而不是 `1dp 换算成的 px` ——
     * 后者在 2560px 的平板上只能给出原效果的 1/5 相对尺寸，看上去就是一层没刮干净的沙。
     */
    const val SIZE_REFERENCE_WIDTH = 500f

    /** mfosu `depth_speed_multiplier`：把 ms 折算成 depth 增量 */
    const val DEPTH_SPEED_MULTIPLIER = 0.05f

    /** mfosu `side_speed_multiplier`：横穿类方向的位移系数 */
    const val SIDE_SPEED_MULTIPLIER = 0.0005f

    /** mfosu 噪声位移的除数（`offset / 5000`） */
    private const val NOISE_DIVISOR = 5000f

    /** 每颗粒子的噪声相位散布范围（秒） */
    private const val NOISE_PHASE_RANGE = 4096f

    /**
     * 粒子数默认值。与 mfosu 一致（500 / 范围 50..1000）。
     *
     * 之所以能直接沿用，是因为 [sizeUnitPx] 是按画布宽度算的：
     * 粒子面积与大屏面积同比例放大，于是「覆盖率」与分辨率无关，
     * 同样的粒子数在任何屏幕上都是同一个观感。
     *
     * ⚠️ 这一项就是本效果的**性能旋钮**（每颗粒子一个 drawRect），
     * 低端机卡的话先降它。
     */
    const val DEFAULT_COUNT = 500
    const val MIN_COUNT = 50

    /** 上调余地：比 mfosu 的 1000 多一点，留给“想要更密”的场合 */
    const val MAX_COUNT = 1500

    const val DEFAULT_GLOBAL_SPEED = 100
    const val MIN_GLOBAL_SPEED = 1
    const val MAX_GLOBAL_SPEED = 200

    /**
     * 音频能量 → 时间流速的倍率。对应 mfosu 那个「直接拿来当 Rate 用」的 Σ 幅度。
     *
     * mfosu 里是**直接用** `ΣFrequencyAmplitudes`，也就是倍率 1；
     * 但我们自己的 FFT 幅度标度比 BASS 小 —— 这一差异在频谱那边已经量化过：
     * 同一段音乐，mfosu 的柱高增益取 2.0，我们这边要取 6.4 才等效，
     * 即 `我们的幅度 ≈ BASS 幅度 × 0.3125`。
     * 于是「Σ_ours × 倍率 ≈ Σ_bass」给出 `倍率 ≈ 1 / 0.3125 ≈ 3.2`。
     *
     * 这是个**推导值**（BASS 的绝对标度无法从源码确认，只能靠这个比值外推），
     * 所以做成可调，并配了实时读数方便微调。
     */
    const val DEFAULT_ENERGY_GAIN = 3.2f
    const val MIN_ENERGY_GAIN = 0f
    const val MAX_ENERGY_GAIN = 20f

    /** 速率上限。mfosu 不钳，但两边 Σ 幅度的绝对标度不同，不钳会有瞬移风险 */
    const val MAX_RATE = 40f

    /**
     * 随机深度投放时的最多重试次数。
     *
     * 深度 uniform 取在 [1, 1000]，而「投在画面内」的概率只有约 1/3，
     * 所以 mfosu 直接递归重投（期望 ~3 次）。这里用有限次重试代替递归，
     * 超过上限就压到最远处（必定在画面内）。
     */
    private const val MAX_RESET_ATTEMPTS = 12

    /** 固定种子：粒子布局每次启动都一样，方便单测断言；观感上察觉不到 */
    private const val RNG_SEED = 20261005
  }

  /** 当前粒子数。改它请用 [resize] */
  var count: Int = 0
    private set

  var direction: ParticleDirection = ParticleDirection.RANDOM
  var globalSpeed: Int = DEFAULT_GLOBAL_SPEED
  var energyGain: Float = DEFAULT_ENERGY_GAIN

  /**
   * 尺寸基准单位（px）。应当取 `画布宽度 / [SIZE_REFERENCE_WIDTH]` 再乘用户倍率，
   * 于是 [PARTICLE_MIN_SIZE]..[PARTICLE_MAX_SIZE] 就是「画面宽度的 0.1%..0.8%」。
   */
  var sizeUnitPx: Float = 1f

  /**
   * 整体不透明度（0..1），乘在每颗粒子由深度算出的 alpha 上。
   * mfosu 没有这一项（它粒子层直接不透明），但作为**背景**粒子需要能压淡，
   * 否则会和前景的白色部件抢注意力。
   */
  var baseAlpha: Float = 1f

  /** 最近一帧用的速率，供设置页显示。0 表示静音（此时全场冻结） */
  var lastRate: Float = 0f
    private set

  /**
   * 累计的「模拟时间」（ms）= `Σ(dtReal × rate)`。
   *
   * 这是音频耦合的**直接可观测量**：它完全由能量决定，不受粒子重投/出界等
   * 位置侧副作用影响，所以「双倍能量跑一半步数应该得到同一个值」。
   * 与 mfosu 里那个被 Rate 缩放的 `Clock.CurrentTime` 是同一个东西。
   */
  var simulatedTimeMs: Float = 0f
    private set

  private var capacity = 0

  private var posX = FloatArray(0)
  private var posY = FloatArray(0)
  private var depth = FloatArray(0)
  private var initX = FloatArray(0)
  private var initY = FloatArray(0)
  private var phase = FloatArray(0)

  /** 每 4 个 float 一颗粒子：x, y, 尺寸(px), 不透明度。绘制时直接读这个，避免逐帧分配 */
  private var render = FloatArray(0)

  /**
   * 模拟时间（ms）。它是**被能量缩放过的**时间 ——
   * 与 mfosu 里 Rate 同时作用在 `Clock.CurrentTime` 与 `ElapsedFrameTime` 上一致，
   * 所以噪声相位在响的时候也走得更快。
   */
  private var simTimeMs = 0f

  private val rng = Random(RNG_SEED)

  constructor() {
    resize(DEFAULT_COUNT)
  }

  /**
   * 绘制用的 buffer，长度 = `count * 4`，每 4 个 float 一颗粒子：
   * `[0]` x、`[1]` y（**归一化 0..1**，绘制方乘画布宽高）、
   * `[2]` 尺寸（px）、`[3]` 不透明度。
   *
   * **内容逐帧就地更新，不要持有引用。**
   */
  fun renderBuffer(): FloatArray = render

  /** 按当前 [energyGain] 把能量折成时间流速 */
  fun rateFor(energy: Float): Float =
    (energy * energyGain).coerceIn(0f, MAX_RATE)

  /**
   * 改粒子数。与 mfosu 的 `Restart(count)` 一样是**整场重投**（所有粒子会跳一下），
   * 所以只在用户拖滑杆结束时调用，别每帧调。
   */
  fun resize(newCount: Int) {
    val n = newCount.coerceIn(MIN_COUNT, MAX_COUNT)
    if (n == count && capacity >= n) return

    count = n
    if (capacity < n) {
      capacity = n
      posX = FloatArray(n)
      posY = FloatArray(n)
      depth = FloatArray(n)
      initX = FloatArray(n)
      initY = FloatArray(n)
      phase = FloatArray(n)
      render = FloatArray(n * 4)
    }
    for (i in 0 until n) resetInitial(i)
    for (i in 0 until n) updateProperties(i, render, i * 4)

    // 多出来的格子清成「不可见」，绘制时按 count 截断所以其实用不到，清一下更保险
    for (i in n until capacity) {
      val o = i * 4
      render[o] = 0f
      render[o + 1] = 0f
      render[o + 2] = 0f
      render[o + 3] = 0f
    }
  }

  /** 整场重投 */
  fun restart() {
    for (i in 0 until count) resetInitial(i)
    for (i in 0 until count) updateProperties(i, render, i * 4)
  }

  /**
   * 推进一帧。
   *
   * @param dtRealMs 真实经过时间（ms），直接来自 `withFrameNanos`
   * @param energy   当前音频能量，见 [top.aerohaku.androidapp.dsp.AudioFrame.energy]
   * @param widthPx  画布宽（px），用于修正非正方形画布上的横向/纵向速度
   * @param heightPx 画布高（px）
   */
  fun advance(dtRealMs: Float, energy: Float, widthPx: Float, heightPx: Float) {
    if (count <= 0 || widthPx <= 0f || heightPx <= 0f) return

    val rate = rateFor(energy)
    lastRate = rate

    val dtSim = dtRealMs * rate
    if (dtSim > 0f) {
      simTimeMs += dtSim

      val timeDiff = dtSim * DEPTH_SPEED_MULTIPLIER
      val speedMul = globalSpeed / 100f

      // mfosu 对非正方画布做速度补偿：长边方向乘上长短边之比，
      // 否则横屏（本 app 就是横屏）上横向运动会明显比纵向慢
      val multiplier = max(widthPx, heightPx) / min(widthPx, heightPx)
      val horizontalIsFaster = heightPx >= widthPx

      val timeSec = simTimeMs / 1000f

      for (i in 0 until count) {
        val base = MAX_DEPTH / depth[i] * timeDiff * speedMul

        when (direction) {
          ParticleDirection.FORWARD -> {
            depth[i] -= timeDiff * speedMul
            if (depth[i] < MIN_DEPTH) {
              reset(i, direction)
              continue
            }
            posX[i] = initX[i] / depth[i]
            posY[i] = initY[i] / depth[i]
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }

          ParticleDirection.BACKWARDS -> {
            depth[i] += timeDiff * speedMul
            if (depth[i] > MAX_DEPTH) {
              reset(i, direction)
              continue
            }
            posX[i] = initX[i] / depth[i]
            posY[i] = initY[i] / depth[i]
            // mfosu 这一支**不做**出界检查（位置是按 depth 除出来的，天然在框内）
          }

          ParticleDirection.FROM_LEFT -> {
            posX[i] += base * (if (horizontalIsFaster) multiplier else 1f) * SIDE_SPEED_MULTIPLIER
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }

          ParticleDirection.FROM_RIGHT -> {
            posX[i] -= base * (if (horizontalIsFaster) multiplier else 1f) * SIDE_SPEED_MULTIPLIER
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }

          ParticleDirection.FROM_TOP -> {
            posY[i] += base * (if (horizontalIsFaster) 1f else multiplier) * SIDE_SPEED_MULTIPLIER
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }

          ParticleDirection.FROM_BOTTOM -> {
            posY[i] -= base * (if (horizontalIsFaster) 1f else multiplier) * SIDE_SPEED_MULTIPLIER
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }

          ParticleDirection.RANDOM -> {
            // 1D 值噪声替代 mfosu 的 OpenSimplexNoise；两个方向取相位相差 500 的样本，
            // 对应原实现 `noise.Evaluate(t, 0)` 与 `noise.Evaluate(t, 100)`
            val ox = valueNoise1D(timeSec + phase[i])
            val oy = valueNoise1D(timeSec + phase[i] + 500f)
            posX[i] += base * (if (horizontalIsFaster) multiplier else 1f) * ox / NOISE_DIVISOR
            posY[i] += base * (if (horizontalIsFaster) 1f else multiplier) * oy / NOISE_DIVISOR
            if (outOfBounds(i)) {
              reset(i, direction)
              continue
            }
          }
        }
      }
    }

    // 尺寸/透明度每帧都重算：静音时位置不动，但用户可能刚拖过「粒子尺寸」滑杆
    for (i in 0 until count) updateProperties(i, render, i * 4)

    simulatedTimeMs = simTimeMs
  }

  // ------------------------------------------------------------------ 初始化

  /**
   * **初始投放**。对应 mfosu 的 `new Particle()` → `reset(ParticlesDirection.Forward, false)`：
   *
   * ```csharp
   * public Particle() { reset(ParticlesDirection.Forward, false); }
   * // Restart(count) → parts.Add(new Particle())
   * ```
   *
   * ⚠️ 这里必须用**随机深度**（`maxDepth = false`），不是最大深度。
   * 两者观感差别很大：
   *
   * - 随机深度 → 初始化后粒子已经散布在各个深度上，**整场立刻可见**；
   * - 最大深度 → 全部粒子尺寸取下限（0.5u）、`alpha` 正好等于 0，
   *   于是画面**完全空白**，要等音乐响起来才慢慢淡入。
   *
   * ⚠️ 这个分支很容易被误判为「不可达」而删掉：
   * 出界重投走的 `reset(direction)` 确实默认 `maxDepth = true`，
   * 但构造函数这一条是**唯一**的初始投放路径。真机上就靠截图才发现整场是空的。
   */
  private fun resetInitial(i: Int) {
    phase[i] = rng.nextFloat() * NOISE_PHASE_RANGE
    resetForward(i, maxDepth = false)
  }

  /**
   * 重投一颗粒子（出界用）。
   *
   * ⚠️ [ParticleDirection.RANDOM] 走的是 mfosu `switch` 的 `default` 分支 ——
   * 也就是**按 Backwards 那样初始化**。这不是笔误：
   * Random 只需要一个固定的 depth 作为视差基准，之后再不改动。
   */
  private fun reset(i: Int, dir: ParticleDirection) {
    phase[i] = rng.nextFloat() * NOISE_PHASE_RANGE
    when (dir) {
      ParticleDirection.FORWARD -> resetForward(i, maxDepth = true)
      ParticleDirection.BACKWARDS -> resetDepthBased(i)
      ParticleDirection.RANDOM -> resetDepthBased(i)
      ParticleDirection.FROM_LEFT -> {
        posX[i] = -0.5f
        posY[i] = randomOffset()
      }

      ParticleDirection.FROM_RIGHT -> {
        posX[i] = 0.5f
        posY[i] = randomOffset()
      }

      ParticleDirection.FROM_TOP -> {
        posX[i] = randomOffset()
        posY[i] = -0.5f
      }

      ParticleDirection.FROM_BOTTOM -> {
        posX[i] = randomOffset()
        posY[i] = 0.5f
      }
    }
  }

  /**
   * Backwards / Random 的初始化：先在「最大深度处的平面」上随机取一点，
   * 再把它投影到 ±0.5 的边框上，用两者的比值反推 depth。
   * 这样粒子一上来就正好在画面边缘，不会凭空出现在中间。
   */
  private fun resetDepthBased(i: Int) {
    val ix = randomOffset() * MAX_DEPTH
    val iy = randomOffset() * MAX_DEPTH
    initX[i] = ix
    initY[i] = iy

    val ax = abs(ix) / MAX_DEPTH
    val ay = abs(iy) / MAX_DEPTH
    if (ax > ay) {
      val ratio = ax / 0.5f
      posX[i] = if (ix > 0f) 0.5f else -0.5f
      posY[i] = (iy / MAX_DEPTH) / ratio
    } else {
      val ratio = ay / 0.5f
      posY[i] = if (iy > 0f) 0.5f else -0.5f
      posX[i] = (ix / MAX_DEPTH) / ratio
    }

    // mfosu: currentDepth = initialPosition.X / CurrentPosition.X
    // 理论上恒正；除零/NaN 只可能在 ix 恰好为 0 时出现，兜一下防止整场被 NaN 污染
    val d = ix / posX[i]
    depth[i] = if (d.isFinite() && d > 0f) d else MIN_DEPTH
  }

  /**
   * Forward 的初始化：位置 = 初始平面坐标 / depth。
   *
   * @param maxDepth `true` 时深度固定在最远处（出界重投走这条）；
   *   `false` 时深度在 `[MIN_DEPTH, MAX_DEPTH]` 里随机（初始投放走这条）。
   *   随机深度时大部分投点会落在画面外，所以 mfosu 会递归重投 —— 这里用有限次重试代替，
   *   超过上限就直接压到最远处（必定在画面内）。
   */
  private fun resetForward(i: Int, maxDepth: Boolean) {
    var attempt = 0
    while (true) {
      depth[i] =
        if (maxDepth || attempt >= MAX_RESET_ATTEMPTS) MAX_DEPTH
        else MIN_DEPTH + rng.nextFloat() * (MAX_DEPTH - MIN_DEPTH)
      initX[i] = randomOffset() * MAX_DEPTH
      initY[i] = randomOffset() * MAX_DEPTH
      posX[i] = initX[i] / depth[i]
      posY[i] = initY[i] / depth[i]
      if (!outOfBounds(i) || attempt >= MAX_RESET_ATTEMPTS) return
      attempt++
    }
  }

  private fun randomOffset(): Float = rng.nextFloat() - 0.5f

  // ------------------------------------------------------------------ 属性

  /**
   * 由 depth 反推尺寸与不透明度，写进 [render] 的第 [o] 起 4 个 float。
   *
   * mfosu 原式：
   * `CurrentSize = Map(depth, 1000, 1, 0.5, 4)`
   * `CurrentAlpha = CurrentSize < 1 ? Map(CurrentSize, 0.5, 1, 0, 1) : 1`
   * 这里的「1」也是以 [sizeUnitPx] 为单位的，所以淡入阈值跟着尺寸一起缩放。
   */
  private fun updateProperties(i: Int, out: FloatArray, o: Int) {
    val u = if (sizeUnitPx > 0f) sizeUnitPx else 1f
    val lo = PARTICLE_MIN_SIZE * u
    val hi = PARTICLE_MAX_SIZE * u
    val s = map(depth[i], MAX_DEPTH, MIN_DEPTH, lo, hi)

    out[o] = (posX[i] + 0.5f)
    out[o + 1] = (posY[i] + 0.5f)
    out[o + 2] = s
    out[o + 3] = baseAlpha * (if (s < u) map(s, lo, u, 0f, 1f).coerceIn(0f, 1f) else 1f)
  }

  private fun outOfBounds(i: Int): Boolean =
    posX[i] > 0.5f || posX[i] < -0.5f || posY[i] > 0.5f || posY[i] < -0.5f

  /** mfosu `MathExtensions.Map`：把 [value] 从 `[fromLow, fromHigh]` 线性映射到 `[toLow, toHigh]` */
  private fun map(value: Float, fromLow: Float, fromHigh: Float, toLow: Float, toHigh: Float): Float =
    (value - fromLow) / (fromHigh - fromLow) * (toHigh - toLow) + toLow

  // ------------------------------------------------------------------ 噪声

  /**
   * 一维平滑值噪声，输出 -1..1。
   *
   * 替代 mfosu 的 `OpenSimplexNoise`：那边只是为了拿到「随时间平滑变化的方向」，
   * 值噪声 + smoothstep 插值给的就是同一个东西，而 simplex 的梯度表对背景尘埃毫无意义。
   * 注意本实现在**整数格点**上平滑穿过，也就是每 1 秒换一个「风向」，与 mfosu 的节奏一致。
   */
  private fun valueNoise1D(x: Float): Float {
    val base = floor(x)
    val f = x - base
    val u = f * f * (3f - 2f * f)
    val n = base.toInt()
    val a = hashToUnit(n)
    val b = hashToUnit(n + 1)
    return (a + (b - a) * u) * 2f - 1f
  }

  /** 整数 → 0..1 的确定性散列 */
  private fun hashToUnit(n: Int): Float {
    var h = n * 374761393
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x00FFFFFF) / 16777215f
  }
}
