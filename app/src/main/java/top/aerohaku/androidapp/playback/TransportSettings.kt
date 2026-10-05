package top.aerohaku.androidapp.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 右侧播放控制栏的设置。
 */
data class TransportSettings(
  /**
   * 页面上多久没人碰，就把控制栏淡出去（秒）。**0 = 不自动隐藏**。
   *
   * 默认 10 秒 —— 和看视频时那排按钮的行为一样：不动它就让位给画面，碰一下屏幕再回来。
   * 可视化界面本身是拿来看的，一排按钮长期杵在那儿挺碍眼。
   */
  val autoHideSeconds: Int = DEFAULT_AUTO_HIDE_SECONDS,
) {
  companion object {
    const val DEFAULT_AUTO_HIDE_SECONDS = 10
    const val MIN_AUTO_HIDE_SECONDS = 0
    const val MAX_AUTO_HIDE_SECONDS = 60

    /** 滑杆的取值粒度：没必要精确到 1 秒，5 秒一档更好拖 */
    const val AUTO_HIDE_STEP = 5

    fun formatAutoHide(seconds: Int): String =
      if (seconds <= 0) "不自动隐藏" else "${seconds}s"
  }
}

/** 播放控制栏设置的持久化。做法与 [top.aerohaku.androidapp.ui.particles.ParticleSettingsStore] 一致 */
object TransportSettingsStore {

  private const val PREF_NAME = "transport_rail"
  private const val KEY_AUTO_HIDE = "autoHideSeconds"

  private val _state = MutableStateFlow(TransportSettings())
  val state: StateFlow<TransportSettings> = _state.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val defaults = TransportSettings()
      _state.value = TransportSettings(
        autoHideSeconds = prefsOf(context)
          .getInt(KEY_AUTO_HIDE, defaults.autoHideSeconds)
          .coerceIn(TransportSettings.MIN_AUTO_HIDE_SECONDS, TransportSettings.MAX_AUTO_HIDE_SECONDS),
      )
      loaded = true
    }
  }

  fun setAutoHideSeconds(context: Context, seconds: Int) {
    ensureLoaded(context)
    val clamped = seconds.coerceIn(
      TransportSettings.MIN_AUTO_HIDE_SECONDS,
      TransportSettings.MAX_AUTO_HIDE_SECONDS,
    )
    _state.value = _state.value.copy(autoHideSeconds = clamped)
    prefsOf(context).edit().putInt(KEY_AUTO_HIDE, clamped).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
