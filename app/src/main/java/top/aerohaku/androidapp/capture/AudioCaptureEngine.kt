package top.aerohaku.androidapp.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.dsp.AudioFrame
import top.aerohaku.androidapp.dsp.SpectrumAnalyzer
import top.aerohaku.androidapp.model.TargetApp

/**
 * 音频捕获引擎：MediaProjection（仅用于授权，不录屏）→ 按 UID 抓网易云的播放音频 → 算 RMS。
 *
 * 配置全部来自 2026-10-05 的探针真机实测：
 * - `44100 Hz / MONO / PCM_16BIT` 第一个候选即成功
 * - **不需要 `RECORD_AUDIO`**（实测 `granted=false` 照样抓得到）
 * - 只按 UID 过滤，避免抓到自己 App 的输出造成啸叫
 * - ⚠️ 探针当时只验了 MONO（第一个候选就成功）。要出「左右声道电平」必须拿立体声，
 *   所以现在**把 STEREO 排在候选列表最前面**；万一设备/ROM 不支持会自动回落 MONO，
 *   此时 [AudioFrame.stereo] 为 false，UI 会明示「左右电平必然相同」。
 *
 * 本类只负责「抓音频 + 分析」，不负责前台服务与自动启停（那是 [AudioCaptureService] 的事）。
 */
class AudioCaptureEngine(private val context: Context) : AudioFrameSource {

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  @Volatile private var projection: MediaProjection? = null
  @Volatile private var recorder: AudioRecord? = null
  @Volatile private var readJob: Job? = null
  @Volatile private var analyzer: SpectrumAnalyzer? = null

  private var chunkSamples = 4096

  private val _frame = MutableStateFlow(AudioFrame.EMPTY)
  override val frame: StateFlow<AudioFrame> = _frame.asStateFlow()

  /** 当前实际使用的格式，供状态显示用；未开始时为 null */
  @Volatile override var formatDescription: String? = null
    private set

  /** 实际拿到的声道数（1 或 2） */
  @Volatile var channelCount: Int = 1
    private set

  /** 投影被系统/用户停止时回调。服务用它结束自己，避免留下一个没有投影的前台服务 */
  @Volatile var onProjectionStopped: (() -> Unit)? = null

  val hasProjection: Boolean get() = projection != null

  /** 投影这条链路要等用户授权、拿到一次性令牌之后才算「就绪」 */
  override val isReady: Boolean get() = hasProjection

  override val isRecording: Boolean get() = recorder != null

  /** 取得 MediaProjection 授权。返回 null 表示成功，否则是错误信息。 */
  @SuppressLint("MissingPermission")
  fun attachProjection(resultCode: Int, data: Intent): String? {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
      ?: return "系统没有 MediaProjectionManager"
    val newProjection = runCatching { manager.getMediaProjection(resultCode, data) }
      .getOrElse { return "getMediaProjection 失败：${it.javaClass.simpleName} ${it.message}" }
      ?: return "getMediaProjection 返回 null"

    projection?.let { runCatching { it.stop() } }
    projection = newProjection

    runCatching {
      newProjection.registerCallback(
        object : MediaProjection.Callback() {
          override fun onStop() {
            // 用户在状态栏点了「停止共享」或系统回收
            stopRecording()
            projection = null
            CaptureController.updateStatus {
              it.copy(phase = CaptureController.Phase.STOPPED, message = "投影已被系统/用户停止，需重新授权", format = "")
            }
            onProjectionStopped?.invoke()
          }
        },
        Handler(Looper.getMainLooper()),
      )
    }
    return null
  }

  /** 开始读音频。返回 null 表示成功；重复调用是安全的（已在读就直接返回）。 */
  @SuppressLint("MissingPermission")
  override fun startRecording(): String? {
    if (recorder != null) return null

    val activeProjection = projection ?: return "尚未获得捕获授权"

    val targetUid = resolveTargetUid() ?: return "取不到 ${TargetApp.PACKAGE} 的 uid"

    val failures = mutableListOf<String>()
    for ((rate, mask) in CANDIDATES) {
      val channels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
      val description = "$rate Hz / ${if (channels == 2) "立体声" else "单声道"}"
      try {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
          .addMatchingUid(targetUid)
          .build()

        val minBytes = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
          failures += "$description：getMinBufferSize=$minBytes"
          continue
        }

        val candidate = AudioRecord.Builder()
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(rate)
              .setChannelMask(mask)
              .build(),
          )
          .setBufferSizeInBytes(minBytes * 4)
          .setAudioPlaybackCaptureConfig(captureConfig)
          .build()

        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
          failures += "$description：未初始化（state=${candidate.state}）"
          candidate.release()
          continue
        }

        val newAnalyzer = SpectrumAnalyzer(sampleRate = rate, channelCount = channels)
        analyzer = newAnalyzer
        channelCount = channels
        recorder = candidate
        // 每块约 16.7ms（即 60fps）—— 帧数 × 声道数 = 交错样本数。
        // 曾经的 rate/25（=40ms/块）会把可视化锁在 25fps，
        // 在 165Hz 屏幕上肉眼可见地卡。512 样本的 FFT 窗本身就占 11.6ms，
        // 所以比 60fps 再密也不会多出信息量。
        chunkSamples = ((rate / 60) * channels).coerceAtLeast(512)
        formatDescription = description

        candidate.startRecording()
        runReadLoop(candidate, newAnalyzer)
        return null
      } catch (t: Throwable) {
        failures += "$description：${t.javaClass.simpleName} ${t.message}"
      }
    }

    formatDescription = null
    return "所有音频格式均失败：\n" + failures.joinToString("\n")
  }

  override fun stopRecording() {
    readJob?.cancel()
    readJob = null
    recorder?.let { rec ->
      runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
      runCatching { rec.release() }
    }
    recorder = null
    analyzer?.reset()
    _frame.value = AudioFrame.EMPTY
  }

  override fun release() {
    stopRecording()
    runCatching { projection?.stop() }
    projection = null
    scope.cancel()
  }

  private fun runReadLoop(activeRecorder: AudioRecord, activeAnalyzer: SpectrumAnalyzer) {
    readJob?.cancel()
    readJob = scope.launch {
      val buffer = ShortArray(chunkSamples)
      while (isActive) {
        val read = try {
          activeRecorder.read(buffer, 0, buffer.size)
        } catch (t: Throwable) {
          -1
        }
        if (read < 0) break
        if (read == 0) continue

        // 采集线程只做「搬运 + 分析」，不阻塞在 UI 上；
        // 分析器内部已把产出限制在 ~60fps（见 SpectrumAnalyzer.MIN_FRAME_INTERVAL_MS），
        // 所以这里不必额外节流。
        activeAnalyzer.process(buffer, read, SystemClock.elapsedRealtime())?.let { _frame.value = it }
      }
      _frame.value = AudioFrame.EMPTY
    }
  }

  private fun resolveTargetUid(): Int? = runCatching {
    context.packageManager.getApplicationInfo(TargetApp.PACKAGE, 0).uid
  }.getOrNull()

  /** 供诊断：把 uid 查询失败的真实原因暴露出来（见 PLAN 文档「坑六」） */
  fun uidFailureReason(): String? {
    if (resolveTargetUid() != null) return null
    return try {
      context.packageManager.getApplicationInfo(TargetApp.PACKAGE, 0)
      "未预期：其实能查到"
    } catch (t: Throwable) {
      "${t.javaClass.name}: ${t.message}"
    }
  }

  private companion object {
    /**
     * 候选音频格式，按优先级尝试。
     * **STEREO 优先** —— 左右声道电平需要它；不支持时自动回落 MONO（此时 stereo=false）。
     */
    val CANDIDATES = listOf(
      44_100 to AudioFormat.CHANNEL_IN_STEREO,
      48_000 to AudioFormat.CHANNEL_IN_STEREO,
      44_100 to AudioFormat.CHANNEL_IN_MONO,
      48_000 to AudioFormat.CHANNEL_IN_MONO,
    )
  }
}
