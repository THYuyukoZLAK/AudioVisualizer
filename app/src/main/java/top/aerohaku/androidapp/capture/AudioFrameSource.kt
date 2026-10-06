package top.aerohaku.androidapp.capture

import kotlinx.coroutines.flow.StateFlow
import top.aerohaku.androidapp.dsp.AudioFrame

/**
 * 一路「能产出 [AudioFrame] 的音频源」。
 *
 * 两个实现：[AudioCaptureEngine]（MediaProjection + AudioPlaybackCapture）与
 * [SystemMixCapture]（Visualizer 效果器挂在全局混音上）。
 *
 * [AudioCaptureService] 只面向这个接口写策略 —— 自动启停、状态上报、帧转发都是共用的，
 * 不必在服务里到处判「现在是哪条链路」。
 */
internal interface AudioFrameSource {

  val frame: StateFlow<AudioFrame>

  /** 实际使用的格式，例如 `44100 Hz / 立体声`；未开始读时为 null */
  val formatDescription: String?

  val isRecording: Boolean

  /**
   * 是否已具备开始捕获的条件。
   *
   * 投影那条链路要等用户授权拿到令牌才算「就绪」；混音这条随时可以（权限不够时
   * 由 [startRecording] 返回一句能读懂的错误，而不是在这里静默卡住）。
   */
  val isReady: Boolean

  /** 开始读音频。返回 null 表示成功，否则是给用户看的错误信息 */
  fun startRecording(): String?

  fun stopRecording()

  fun release()
}
