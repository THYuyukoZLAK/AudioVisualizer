package top.aerohaku.androidapp.display

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import java.util.Locale

/**
 * 显示刷新率的查询与请求。
 *
 * ## 关于「垂直同步」
 *
 * **Compose 本身就是垂直同步的**：`withFrameNanos` 的帧回调由 `Choreographer` 驱动，
 * 每个 vsync 周期至多一次。所以不需要任何额外的同步代码，
 * 真正可能被卡住的只有两处：
 *
 * 1. **窗口模式**。系统默认可能只给应用 60Hz（本机平板最高 165Hz），
 *    要用 `preferredRefreshRate` 主动请求，见 [requestHighest]。
 * 2. **数据产出速率**。频谱/波形/电平表只在新的 `AudioFrame` 到达时更新，
 *    而这个速率由音频采集块大小决定，与显示无关 ——
 *    详见 `SpectrumAnalyzer.MIN_FRAME_INTERVAL_MS` 与 `AudioCaptureEngine.chunkSamples`。
 *
 * 粒子层走的是帧回调，所以它天然跟着显示刷新率跑。
 */
object DisplayRefresh {

  /**
   * 取当前上下文的 [Display]。
   *
   * API 30 起用 `Context.getDisplay()`（**非视觉上下文会抛异常**，所以一律 runCatching）；
   * 29 只能退回已废弃的 `WindowManager.getDefaultDisplay()`。
   */
  fun displayOf(context: Context): Display? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      context.display
    } else {
      @Suppress("DEPRECATION")
      (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
  }.getOrNull()

  /** 当前显示模式的实际刷新率（Hz）。系统会因为内容负载在多个模式间切换，所以这是个动态值 */
  fun currentRate(context: Context): Float? =
    displayOf(context)?.let { display -> runCatching { display.mode.refreshRate }.getOrNull() }

  /** 显示支持的最高刷新率（Hz）。部分设备的 `supportedModes` 会抛异常，故兜一层 */
  fun maxRate(context: Context): Float? = displayOf(context)?.let { display ->
    runCatching { display.supportedModes?.maxOfOrNull { it.refreshRate } }.getOrNull()
      ?: runCatching { display.refreshRate }.getOrNull()
  }

  /**
   * 请求窗口以显示支持的最高刷新率运行，返回请求到的 Hz（失败返回 null）。
   *
   * ⚠️ 这只是**建议**：
   * - 若窗口已经设了 `preferredDisplayModeId`，`preferredRefreshRate` 会被忽略；
   * - 厂商 ROM 或用户在系统设置里给本应用指定的刷新率上限优先级更高。
   *
   * 所以别把它当保证 —— 设置页那行读数读的是 `Display.mode` 的**实际值**，
   * 只有那个才说明真相。
   */
  fun requestHighest(activity: Activity): Float? {
    val max = maxRate(activity) ?: return null
    val attrs = activity.window.attributes
    if (attrs.preferredDisplayModeId != 0) return null
    if (attrs.preferredRefreshRate >= max) return max
    attrs.preferredRefreshRate = max
    activity.window.attributes = attrs
    return max
  }

  /** 供 UI 显示：「当前 / 最高 Hz」 */
  fun describe(context: Context): String {
    val current = currentRate(context)
    val max = maxRate(context)
    if (current == null && max == null) return "未知"
    return String.format(Locale.US, "%.0f / %.0f Hz", current ?: 0f, max ?: 0f)
  }
}
