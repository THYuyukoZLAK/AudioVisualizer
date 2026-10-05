package top.aerohaku.androidapp.display

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 可视化页面的**实测帧率**。
 *
 * 为什么需要它：`DisplayRefresh` 读到的是「显示模式」的刷新率，
 * 那只能说明窗口拿到了高刷模式，**不能说明我们真的按那个速率在画**。
 * 真正的证据是 `withFrameNanos` 回调的间隔。
 *
 * 数据由 `ParticleFieldView` 的帧循环喂进来（一秒统计一次），
 * 设置页读它显示。注意：进入设置页后可视化页退出组合、帧循环停止，
 * 所以界面上显示的是**离开可视化页之前的最后一次测量值**。
 *
 * 每秒最多写一次状态，且在可视化页（没有订阅者）时几乎无开销。
 */
object FrameStats {

  private const val WINDOW_NANOS = 1_000_000_000L

  private val _fps = MutableStateFlow(0f)

  /** 最近一次统计窗口内的平均帧率（Hz） */
  val fps: StateFlow<Float> = _fps.asStateFlow()

  private var frames = 0
  private var windowStartNanos = 0L

  /** 每帧调用一次（UI 线程） */
  fun onFrame(frameNanos: Long) {
    if (windowStartNanos == 0L) {
      windowStartNanos = frameNanos
      return
    }
    frames++
    val elapsed = frameNanos - windowStartNanos
    if (elapsed >= WINDOW_NANOS) {
      _fps.value = frames * 1e9f / elapsed
      frames = 0
      windowStartNanos = frameNanos
    }
  }
}
