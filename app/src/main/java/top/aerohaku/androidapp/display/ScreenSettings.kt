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
)

/**
 * 屏幕设置的持久化。做法与 [top.aerohaku.androidapp.ui.particles.ParticleSettingsStore] 一致。
 *
 * 放在 `display/` 而不是 `ui/` 下：它管的是**窗口行为**，不是某个界面的外观。
 */
object ScreenSettingsStore {

  private const val PREF_NAME = "screen_behavior"
  private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"

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
      )
      loaded = true
    }
  }

  fun setKeepScreenOn(context: Context, value: Boolean) {
    ensureLoaded(context)
    _state.value = _state.value.copy(keepScreenOn = value)
    prefsOf(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
