package top.aerohaku.androidapp.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aerohaku.androidapp.dsp.AudioFrame

/**
 * 音频捕获的全局状态 + 设置项。
 *
 * 写入方：[AudioCaptureService]（同进程），读取方：UI。
 * 只用 StateFlow 共享，不需要 Binder/AIDL。
 */
object CaptureController {

  enum class Phase { IDLE, STARTING, RUNNING, STOPPED, ERROR }

  data class Status(
    val phase: Phase = Phase.IDLE,
    /** 实际使用的音频格式，例如 `44100Hz MONO` */
    val format: String = "",
    val message: String = "",
    /**
     * true 表示：服务与投影都还活着，只是**因为当前没在播放而暂时不读音频**
     * （自动启停的「停」只停读音频，不停服务 —— 否则 MediaProjection 会被系统回收，下次又得重新授权）
     */
    val heldBecauseIdle: Boolean = false,
  )

  private val _status = MutableStateFlow(Status())
  val status: StateFlow<Status> = _status.asStateFlow()

  private val _audioFrame = MutableStateFlow(AudioFrame.EMPTY)
  val audioFrame: StateFlow<AudioFrame> = _audioFrame.asStateFlow()

  private val _autoStartStop = MutableStateFlow(true)
  val autoStartStop: StateFlow<Boolean> = _autoStartStop.asStateFlow()

  fun setAutoStartStop(enabled: Boolean) {
    _autoStartStop.value = enabled
  }

  // ---------- 以下仅供 AudioCaptureService 使用 ----------

  internal fun setStatus(status: Status) {
    _status.value = status
  }

  internal fun updateStatus(transform: (Status) -> Status) {
    _status.value = transform(_status.value)
  }

  internal fun setAudioFrame(frame: AudioFrame) {
    _audioFrame.value = frame
  }
}
