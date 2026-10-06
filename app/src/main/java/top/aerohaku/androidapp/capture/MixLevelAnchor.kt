package top.aerohaku.androidapp.capture

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * 把 `NORMALIZED` 的数据**搬回「内容自己的」绝对刻度**。
 *
 * ## 问题一：归一化抹掉了响度
 *
 * 混音的音频数据必须走 `NORMALIZED`（8 位量化发生在框架 AGC **之后**，不归一化的话
 * 设备音量一低，波形就只剩几级台阶 —— 见 [SystemMixCapture]）。但归一化的定义就是
 * 「每块都拉到满幅」，于是**很小的声音看起来也满格**，响度信息全没了。
 *
 * 与此同时，框架的 `getMeasurementPeakRms()` 是在 AGC 与量化**之前**取样的，
 * 它给的是**真正的绝对 RMS**。两个一减就得到那个缩放量：
 *
 * ```
 * 缩放量(dB) = 绝对 RMS(dB) − 我们量到的 RMS(dB)
 * ```
 *
 * 因为归一化**只会把信号抬上去**（每块峰值被拉到 0.99，而真实峰值 ≤ 1），
 * 这个缩放量必然 **≤ 0** —— 也就是我们只会把信号**压回去**，绝不会放大。
 * 这一点很重要：它意味着**量化噪声不会被放大**（那正是上一版「乘样本」做法栽的地方）。
 *
 * ## 问题二：框架测的是「音量之后」的信号
 *
 * `Visualizer` 挂在输出混音的末端，AOSP `EffectVisualizer::process()` 写入捕获缓冲的就是
 * 效果器的输入本身 —— 音量在混音阶段已经乘进去了。所以上面那个「绝对 RMS」其实是
 * **输出**的绝对 RMS：用户一调音量，三件可视化组件就一起缩放。那不正确。
 *
 * 修法就是把这一级衰减减掉（[musicOutputVolumeDb]），基准换成**内容自己的**绝对 RMS：
 *
 * ```
 * 缩放量(dB) = 绝对 RMS(dB) − 设备音量(dB) − 我们量到的 RMS(dB)
 * ```
 *
 * 于是读数只跟「这首歌这一段有多响」有关，与音量档位无关 —— 和屏幕录制那条路一致。
 *
 * 把样本乘上这个缩放量之后，波形、频谱、电平三项就都回到内容刻度上了：
 * 安静段落自然变小、电平柱跟着响度走、红线也重新以满量程为基准。
 *
 * ## 为什么「不会放大」这条硬保证依然成立
 *
 * 归一化把每块峰值拉到 0.99，而我们搬回去的目标是**内容的峰值**（≤ 1，即 ≤ 0dB），
 * 所以理想缩放量始终 ≤ 0dB（严格说是 +0.09dB 那点零头，来自 0.99 与 1 的差），
 * 夹在 0 上正好。
 *
 * 万一音量估计偏小导致「想放大」，会被这一夹**拒绝**：那时读数退回「归一化原值」——
 * 它本来就是与音量无关的（`fscale = 0.99/maxSample` 里音量被约掉了），只是起伏被抹平。
 * ⇒ 补偿失败的方向是**安全的**：只会丢掉起伏，绝不会变成跟着音量走。
 *
 * ## ‼️ 这里**故意不用「目标值」自动量程**
 *
 * 曾经把长时平均钉在一个固定的目标电平（-12dB）上，本意是「不随设备音量漂」。
 * 实测下来那是**自相矛盾**的：它恰好把用户想看的**响度起伏本身**抵消掉了 ——
 * 读数的动态被抹平、和响度失去关联。
 *
 * 现在这里**没有任何目标值**，只有「框架的绝对测量 − 设备音量」这一个基准。
 */
internal class MixLevelAnchor(
  private val timeConstantMs: Float = DEFAULT_TIME_CONSTANT_MS,
  private val maxCutDb: Float = DEFAULT_MAX_CUT_DB,
  private val maxBoostDb: Float = DEFAULT_MAX_BOOST_DB,
  private val gateDb: Float = DEFAULT_GATE_DB,
) {

  /** 当前缩放（线性）。只会 ≤ 1，见类注释 */
  var scale: Float = 1f
    private set

  /** 当前缩放（dB），供诊断 */
  var scaleDb: Float = 0f
    private set

  private var smoothedDb = 0f
  private var started = false

  /**
   * 用一次测量更新缩放。[absoluteRmsDb] 与 [ourRmsDb] 都是**缩放前**的电平。
   *
   * @param absoluteRmsDb 框架 `getMeasurementPeakRms()` 给的绝对 RMS（dB）。
   *   ⚠️ 它是**音量之后**的值 —— 必须配合 [volumeDb] 才能变回内容刻度。
   * @param ourRmsDb 我们自己从**缩放前**的样本算出的 RMS（dB）。
   *   ⚠️ 必须取缩放前的，否则反馈环里带了个积分器，会漂。
   * @param volumeDb 设备音量对信号的衰减（≤ 0 dB，来自 [musicOutputVolumeDb]）。
   *   传 0 表示不补偿（读数会跟着音量走）。
   */
  fun update(absoluteRmsDb: Float, ourRmsDb: Float, dtMs: Float, volumeDb: Float = 0f) {
    // 两侧都得有效：静音时框架会返回 -96dB（-9600mB），这时候算出来的缩放量没有意义
    if (absoluteRmsDb < gateDb || ourRmsDb < gateDb) return

    // 减掉设备音量 ⇒ 基准从「输出的绝对 RMS」变成「内容的绝对 RMS」
    val contentRmsDb = absoluteRmsDb - volumeDb.coerceIn(-MAX_VOLUME_DB, 0f)
    val wantDb = (contentRmsDb - ourRmsDb).coerceIn(-maxCutDb, maxBoostDb)
    // 平滑的时间常数取得比框架自己的测量窗口（约 500ms）短，
    // 这样主要的时间平滑来自框架那一侧的绝对值，我们不再额外拖慢它
    val a = exp(-dtMs.coerceIn(1f, MAX_STEP_MS) / timeConstantMs)
    smoothedDb = if (started) smoothedDb * a + wantDb * (1f - a) else wantDb
    started = true

    scaleDb = smoothedDb
    scale = 10f.pow(smoothedDb / 20f)
  }

  /** 就地把缩放施加到样本上 */
  fun apply(samples: ShortArray, count: Int) {
    if (count <= 0) return
    val k = scale
    if (k == 1f) return
    for (i in 0 until count) {
      samples[i] = (samples[i] * k).toInt().coerceIn(MIN_S16, MAX_S16.toInt()).toShort()
    }
  }

  /** 重开一路捕获时复位 */
  fun reset() {
    scale = 1f
    scaleDb = 0f
    smoothedDb = 0f
    started = false
  }

  companion object {
    /** 300ms：比框架自己的测量窗口（约 500ms）短，别让两级平滑叠出明显延迟 */
    const val DEFAULT_TIME_CONSTANT_MS = 300f

    /**
     * 最多压回去多少。给得宽是安全的 —— 归一化本来就可能把很弱的信号抬上来很多；
     * 而且这是**压小**，不会暴露量化噪声。
     */
    const val DEFAULT_MAX_CUT_DB = 60f

    /**
     * 缩放量的上限 —— 取 **0**，即**绝不允许放大**。
     *
     * 这不是保守，而是由事实推出来的：目标刻度是**内容的峰值**（≤ 0dB），而我们的
     * 数据已被归一化到峰值 0.99，所以正确的缩放量**必然 ≤ 0**。既然永远只需压小，
     * 就把「只会压小」做成硬保证 —— 它同时意味着**量化噪声绝不会被放大**
     * （上一版乘样本的做法就是栽在这里）。
     *
     * 副作用是「音量估计偏小」时会拒绝补偿，此时读数退回归一化原值：
     * 依然是**与音量无关**的，只是少了起伏。失败方向是安全的，见类注释。
     */
    const val DEFAULT_MAX_BOOST_DB = 0f

    /** 音量衰减的夹取下限，防御性（个别设备曲线可能给得很极端） */
    const val MAX_VOLUME_DB = 96f

    /** 低于这个电平就不更新（静音时框架给的是 -96dB） */
    const val DEFAULT_GATE_DB = -90f

    /** 单次时间步长上限，防止长卡顿后一次跳太远 */
    const val MAX_STEP_MS = 100f

    const val MIN_S16 = -32768
    const val MAX_S16 = 32767
  }
}

/**
 * 一段 PCM16 的 RMS（dBFS）。缩放前算它，用来和框架的绝对 RMS 相减。
 *
 * 返回 [Levels.MIN_DB] 之类的下限值表示「这一段是静音」。
 */
internal fun rmsDbOf(samples: ShortArray, count: Int): Float {
  if (count <= 0) return SILENT_DB
  var acc = 0.0
  for (i in 0 until count) {
    val v = samples[i] / 32768.0
    acc += v * v
  }
  val rms = kotlin.math.sqrt(acc / count).toFloat()
  return if (rms <= 1e-6f) SILENT_DB else 20f * log10(rms)
}

internal const val SILENT_DB = -99f
