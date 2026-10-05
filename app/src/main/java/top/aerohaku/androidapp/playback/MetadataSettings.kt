package top.aerohaku.androidapp.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 元数据来源的设置。
 *
 * 默认**不开**通知使用权：只连网易云自己对外开放的 `MediaBrowserService`
 * （见 [MediaBrowserNowPlayingSource]），这条路径不需要任何敏感授权。
 */
data class MetadataSettings(
  /**
   * 备用通道：改用系统级读取（`NotificationListenerService` + `MediaSessionManager`）。
   *
   * 什么时候需要它：网易云版本偏老、没有开放 `ucar.UCarService`；
   * 或者服务在但读不到曲目信息。**默认关闭。**
   */
  val useNotificationFallback: Boolean = false,
)

/** 元数据来源设置的持久化。做法与 [top.aerohaku.androidapp.ui.particles.ParticleSettingsStore] 一致 */
object MetadataSettingsStore {

  private const val PREF_NAME = "metadata_source"
  private const val KEY_FALLBACK = "useNotificationFallback"

  private val _state = MutableStateFlow(MetadataSettings())
  val state: StateFlow<MetadataSettings> = _state.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val defaults = MetadataSettings()
      val prefs = prefsOf(context)
      _state.value = MetadataSettings(
        useNotificationFallback = prefs.getBoolean(KEY_FALLBACK, defaults.useNotificationFallback),
      )
      loaded = true
    }
  }

  fun setUseNotificationFallback(context: Context, value: Boolean) {
    ensureLoaded(context)
    _state.value = _state.value.copy(useNotificationFallback = value)
    prefsOf(context).edit().putBoolean(KEY_FALLBACK, value).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
