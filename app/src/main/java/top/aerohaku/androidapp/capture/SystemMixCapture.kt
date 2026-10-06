package top.aerohaku.androidapp.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.dsp.AudioFrame
import top.aerohaku.androidapp.dsp.SpectrumAnalyzer
import top.aerohaku.androidapp.permission.Permissions

/**
 * 用 `Visualizer` 效果器读**全局输出混音**的音频，取代 `MediaProjection` 的播放捕获。
 *
 * 这条路要的权限比屏幕录制轻得多：`RECORD_AUDIO`（授权一次长期有效）+
 * `MODIFY_AUDIO_SETTINGS`（normal，装包即给），**没有**每次会话的系统授权弹窗，
 * 也没有状态栏的投放条目 —— 前者是用户最在意的。
 *
 * ## 为什么是**轮询**而不是 `setDataCaptureListener`
 *
 * 早先否掉这条路的理由是「`maxCaptureRate` 只有 20 Hz」，那是个误判：
 * 官方对 `getMaxCaptureRate()` 的定义是「**callback capture method** 的最大速率」，
 * 它**只**约束回调 API。查 AOSP `EffectVisualizer.cpp` 可见：
 *
 * - `Visualizer_process()` 在**每次音频回调**里把样本追加进 64 KB 滚动缓冲
 *   （`CAPTURE_BUF_SIZE`），**没有任何速率限制**；
 * - `VISUALIZER_CMD_CAPTURE`（即 `getWaveForm`）只是「把最近 `captureSize` 个样本取走」。
 *
 * 所以刷新粒度由**音频回调周期**决定，而不是 20 Hz。按 `captureSize = 1024 @ 48 kHz` 算，
 * 缓冲里大约每 **21 ms** 就换一批新数据。这里按 [POLL_INTERVAL_MS] 轮询，
 * 再交给 [SpectrumAnalyzer] 自己的 12 ms 节流去限制产出帧率。
 *
 * ## 电平表前端：把归一化的数据**搬回内容刻度**
 *
 * ‼️ 这里试过四种做法，前三种都被实测否掉了，记下来避免重走：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 只用 `AS_PLAYED`（不归一化，保留真实信号） | 设备音量一低，8 位量化就把波形压成几级台阶 ⇒ 「波形只有 4 位精度」，且频谱被量化噪声糊住 |
 * | `NORMALIZED` + **目标值**自动量程（把长时平均钉在 -12dB） | 波形精度对了，但它**恰好把响度起伏本身抵消掉** ⇒ 电平柱和响度失去关联 |
 * | `NORMALIZED` + 绝对锚点（不减音量） | 精度与响度都有了，但基准是**输出**的绝对 RMS ⇒ 三件组件全跟着**设备音量**缩放 |
 * | ✅ `NORMALIZED` + 绝对锚点 **− 设备音量** | 精度、响度、与音量无关，三者兼得 |
 *
 * 关键在于：`NORMALIZED` 只是把每块峰值拉到 0.99，而框架的 `getMeasurementPeakRms()`
 * 是在 AGC 与量化**之前**量的**真值**。两者一减就得到那个缩放量：
 *
 * ```
 * 缩放量(dB) = 框架绝对 RMS(dB) − 设备音量(dB) − 我们量到的 RMS(dB)
 * ```
 *
 * ‼️ 中间那一项不可省。效果器挂在输出混音的末端（AOSP `EffectVisualizer::process()`
 * 写进捕获缓冲的就是它自己的输入），所以它量的是**音量之后**的信号；不减掉就会
 * 「按键调小音量 ⇒ 三件组件一起缩」。减掉之后读数只跟「这一段有多响」有关 ——
 * 与屏幕录制那条路（抓应用流本身、实测不受输出音量影响）对齐。
 *
 * 乘上去之后波形、频谱、电平一同回到内容刻度 —— 安静段落自然变小，电平柱跟着响度走，
 * 红线也重新以满量程为基准。而且因为归一化只会把信号抬上去，这个缩放量**恒 ≤ 0**，
 * 即我们只会把信号**压回去**、绝不会放大 ⇒ **不会放大量化噪声**
 * （那正是上一版「乘样本」做法栽的地方）。
 *
 * 实现见 [MixLevelAnchor] 与 [musicOutputVolumeDb]；`SpectrumAnalyzer` 那套
 * （RMS + 3ms 准峰值 + 削顶判定）依旧一行没改。
 *
 * ## 三个已知代价
 *
 * - **响度的时间分辨率约 500ms**：`NORMALIZED` 的量化数据本身**不带任何绝对电平信息**
 *   （每块都被归一到峰值 0.99），唯一能拿到绝对值的地方是框架的
 *   `getMeasurementPeakRms()`，而它是跨最近 25 个缓冲（AOSP
 *   `MEASUREMENT_WINDOW_MAX_SIZE_IN_BUFFERS = 25`，约 500ms）的**滑动平均**。
 *   ⇒ 电平柱能跟住「段落级的强弱」，但跟不上鼓点。要跟鼓点只有屏幕录制那条路。
 * - **只有单声道**：框架返回的是「各路求和后」的单路信号（`getWaveForm` 文档写明
 *   mono），拿不到真正的左右声道。所以 [AudioFrame.stereo] 恒为 false，
 *   UI 那边会明示「左右电平必然相同」。
 * - **抓的是全局混音**：别的应用放声音也会驱动可视化。对可视化来说这通常无妨，
 *   但确实不再是「只跟目标应用联动」。
 */
internal class SystemMixCapture(private val context: Context) : AudioFrameSource {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  @Volatile private var visualizer: Visualizer? = null
  @Volatile private var pollJob: Job? = null
  @Volatile private var analyzer: SpectrumAnalyzer? = null

  private val _frame = MutableStateFlow(AudioFrame.EMPTY)
  override val frame: StateFlow<AudioFrame> = _frame.asStateFlow()

  /** 把归一化的数据搬回**内容**绝对刻度（再减掉设备音量）—— 见 [MixLevelAnchor] */
  private val anchor = MixLevelAnchor()

  override var formatDescription: String? = null
    private set

  /** 混音这条链路不依赖任何一次性令牌，随时可以开始（权限不够在 [startRecording] 里报） */
  override val isReady: Boolean get() = true

  override val isRecording: Boolean get() = visualizer != null

  @SuppressLint("MissingPermission")
  override fun startRecording(): String? {
    if (visualizer != null) return null
    if (!Permissions.isRecordAudioGranted(context)) {
      return "「全局混音」需要录音权限，请先授权"
    }

    val effect = try {
      Visualizer(GLOBAL_SESSION)
    } catch (t: Throwable) {
      return "创建 Visualizer 失败：${t.javaClass.simpleName} ${t.message}"
    }

    val size = pickCaptureSize()
    try {
      effect.setCaptureSize(size)
      effect.setScalingMode(SCALING_MODE)
      // 电平要用框架自己量的绝对 peak/RMS（见类注释）
      effect.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
      effect.setEnabled(true)
    } catch (t: Throwable) {
      runCatching { effect.release() }
      return "初始化 Visualizer 失败：${t.javaClass.simpleName} ${t.message}"
    }

    val rate = effect.samplingRate.takeIf { it > 0 } ?: FALLBACK_RATE
    val newAnalyzer = SpectrumAnalyzer(sampleRate = rate, channelCount = MONO_CHANNELS)
    analyzer = newAnalyzer
    visualizer = effect
    formatDescription = "$rate Hz / $size 采样块 / 混音单声道"

    runPollLoop(effect, newAnalyzer, size)
    return null
  }

  override fun stopRecording() {
    pollJob?.cancel()
    pollJob = null
    visualizer?.let { effect ->
      // setEnabled 会抛 IllegalStateException；release 也可能抛，都不能让它冒到服务层
      runCatching { effect.setEnabled(false) }
      runCatching { effect.release() }
    }
    visualizer = null
    analyzer?.reset()
    anchor.reset()
    _frame.value = AudioFrame.EMPTY
  }

  override fun release() {
    stopRecording()
    scope.cancel()
  }

  private fun runPollLoop(effect: Visualizer, activeAnalyzer: SpectrumAnalyzer, size: Int) {
    pollJob?.cancel()
    pollJob = scope.launch {
      val waveform = ByteArray(size)
      val samples = ShortArray(size)
      var lastMs = SystemClock.elapsedRealtime()
      // 设备音量：把框架那侧「音量之后」的绝对值换算回内容刻度，见类注释
      var volumeDb = musicOutputVolumeDb(context)
      var volumeReadMs = lastMs

      while (isActive) {
        val status = try {
          effect.getWaveForm(waveform)
        } catch (t: Throwable) {
          Visualizer.ERROR
        }
        if (status != Visualizer.SUCCESS) break

        val now = SystemClock.elapsedRealtime()
        // `getWaveForm` 返回的是**滑动窗口**，所以两次调用之间窗口前进的时长
        // 就是墙上时间 —— 直接拿它当自动量程的时间步长，不要按采样率去算
        val dtMs = (now - lastMs).toFloat()
        lastMs = now

        // 音量档位读取很便宜，但没必要每 8ms 都读 —— 250ms 足够跟上按键调整
        if (now - volumeReadMs >= VOLUME_POLL_INTERVAL_MS) {
          volumeReadMs = now
          volumeDb = musicOutputVolumeDb(context)
        }

        u8ToS16(waveform, samples, size)

        // 把 NORMALIZED 的数据搬回**内容自己的**绝对刻度。
        // 框架的 `mRms`（mB，除 100 得 dB）是在 AGC 与量化**之前**量的真值，
        // 而归一化只改变了每块的缩放，所以「它 − 我们量到的」正好是那个缩放量。
        // ‼️ 但它量的是**音量之后**的信号（效果器在输出混音末端），所以还要减掉
        // 设备音量，否则读数会跟着音量档位一起缩放 —— 见 [musicOutputVolumeDb]。
        // ‼️ `rmsDbOf` 必须拿**缩放前**的样本算，否则反馈环里带了个积分器。
        val measurement = Visualizer.MeasurementPeakRms()
        if (effect.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS) {
          anchor.update(measurement.mRms / 100f, rmsDbOf(samples, size), dtMs, volumeDb)
          anchor.apply(samples, size)
        }

        activeAnalyzer.process(samples, size, now)?.let { _frame.value = it }

        delay(POLL_INTERVAL_MS)
      }

      _frame.value = AudioFrame.EMPTY
    }
  }

  /**
   * 挑一个落在设备支持区间里的**2 的幂**（`setCaptureSize` 的硬要求）。
   *
   * 太小的块频率分辨率不够（120 根柱会糊成一片），太大的块跟不上鼓点 ——
   * 1024 @ 48 kHz 刚好每 21 ms 换一批，是个折中。实测区间一般是 `[128, 1024]`。
   */
  private fun pickCaptureSize(): Int {
    val range = Visualizer.getCaptureSizeRange()
    val min = range.getOrNull(0) ?: MIN_CAPTURE_SIZE
    val max = range.getOrNull(1) ?: PREFERRED_SIZE
    return Integer.highestOneBit(PREFERRED_SIZE.coerceIn(min, max)).coerceAtLeast(min)
  }

  private companion object {
    /** `Visualizer(0)` = 挂在**输出混音**上，而不是某个具体的音频会话 */
    const val GLOBAL_SESSION = 0

    const val PREFERRED_SIZE = 1024
    const val MIN_CAPTURE_SIZE = 128

    /**
     * `NORMALIZED`：框架在**每个音频缓冲**内先按自身峰值归一化，**再**做 8 位量化。
     *
     * 这一点直接决定了精度。不归一化的话，量化就落在「已被设备音量衰减过」的信号上 ——
     * 实测音量档 5/15 时信号比满刻度低约 **29dB**，8 位里只用得到几位，
     * 于是波形看上去只剩几级台阶（「只有 4 位精度」），频谱也会被量化噪声糊住、看着不动。
     *
     * ‼️ 代价是它抹掉了电平的**绝对值**，所以电平改由框架的 `getMeasurementPeakRms()`
     * （在 AGC 与量化**之前**取样）+ [MixLevelAnchor] 搬回**内容**刻度 ——
     * 并减掉设备音量（[musicOutputVolumeDb]），见类注释「电平表前端」。
     *
     * 顺带一提：红线在这个模式下是**真正的削顶检测**。每块峰值都被归一到 0.99，
     * 于是 3ms 准峰值量到的正是**波峰因数** —— 普通音乐约 -6~-10dB（不亮，正确），
     * 只有波形被压平成方波时才会够到 -3dB 阈值。
     */
    const val SCALING_MODE = Visualizer.SCALING_MODE_NORMALIZED

    /** 混音只有一路，见类注释 */
    const val MONO_CHANNELS = 1

    /** `samplingRate` 读不出来时的兜底（正常设备都是 44100 / 48000） */
    const val FALLBACK_RATE = 48000

    /**
     * 8 ms ≈ 125 Hz。比音频回调周期（约 21 ms）密得多，保证每次数据更新后都能尽快取到；
     * 真正的**产出**帧率由 `SpectrumAnalyzer.MIN_FRAME_INTERVAL_MS` 限制在约 60fps，
     * 所以多轮询几次不会多画。
     */
    const val POLL_INTERVAL_MS = 8L

    /**
     * 设备音量的重读间隔。
     *
     * 音量键是离散、低频的操作，250ms 足够跟上；而 [MixLevelAnchor] 自己又有一级
     * 300ms 平滑，两级叠起来也就一瞬。
     */
    const val VOLUME_POLL_INTERVAL_MS = 250L
  }
}

/**
 * 8-bit **无符号** PCM → 16-bit 有符号，左移 8 位。
 *
 * `Visualizer` 给的是 8 位数据，而 [SpectrumAnalyzer] 只认 PCM16 —— 这里做一次搬运。
 * 左移不引入新信息（数据本来就只有 8 位），只是把量程对齐到 `[-32768, 32512]`，
 * 于是频谱、能量、电平那几个算法一行都不用改。
 *
 * 中位是 `0x80`（无符号的 128）→ 映射到 0，与 AOSP 写入捕获缓冲时 `^0x80` 的偏置一致。
 */
internal fun u8ToS16(src: ByteArray, dst: ShortArray, count: Int = src.size) {
  for (i in 0 until count) {
    dst[i] = (((src[i].toInt() and 0xFF) - 128) shl 8).toShort()
  }
}
