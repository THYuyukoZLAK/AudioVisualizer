package top.aerohaku.androidapp.display

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕相关设置。
 *
 * 「保持常亮」默认**开**：这个应用就是拿来看的，盯着频谱看一会儿屏幕自己熄掉很别扭；
 * 而且在播放中熄屏时音频捕获与渲染都还在跑，白白耗电又看不到东西。
 */
data class ScreenSettings(
  val keepScreenOn: Boolean = true,
  /**
   * 全屏：隐藏状态栏与任务栏（immersive）。
   *
   * 默认**开**：本应用就是个全屏可视化，顶部压一条状态栏、底下压一条任务栏
   * 既挡内容又不协调。平板（Android 12L+）那条常驻任务栏也属于系统栏，会一起收掉。
   *
   * ⚠️ 不是「禁用」：从屏幕边缘上滑仍会临时唤出，过一会儿自己收回去 ——
   * 所以不会把用户锁在一个退不出去的界面里。
   */
  val hideSystemBars: Boolean = true,
)

/**
 * 屏幕设置的持久化。做法与 [top.aerohaku.androidapp.ui.particles.ParticleSettingsStore] 一致。
 *
 * 放在 `display/` 而不是 `ui/` 下：它管的是**窗口行为**，不是某个界面的外观。
 */
object ScreenSettingsStore {

  private const val PREF_NAME = "screen_behavior"
  private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
  private const val KEY_HIDE_SYSTEM_BARS = "hideSystemBars"

  private val _state = MutableStateFlow(ScreenSettings())
  val state: StateFlow<ScreenSettings> = _state.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val defaults = ScreenSettings()
      _state.value = ScreenSettings(
        keepScreenOn = prefsOf(context).getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
        hideSystemBars = prefsOf(context).getBoolean(KEY_HIDE_SYSTEM_BARS, defaults.hideSystemBars),
      )
      loaded = true
    }
  }

  fun setKeepScreenOn(context: Context, value: Boolean) {
    ensureLoaded(context)
    _state.value = _state.value.copy(keepScreenOn = value)
    prefsOf(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
  }

  fun setHideSystemBars(context: Context, value: Boolean) {
    ensureLoaded(context)
    _state.value = _state.value.copy(hideSystemBars = value)
    prefsOf(context).edit().putBoolean(KEY_HIDE_SYSTEM_BARS, value).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
