package top.aerohaku.androidapp.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aerohaku.androidapp.model.NowPlaying

/**
 * 元数据的来源。
 *
 * - [BROWSER]：直接连网易云的 `MediaBrowserService`（`ucar.UCarService`）读它自己的会话。
 *   **不需要任何敏感授权**，是默认路径。2026-10-05 真机实测确认可用。
 * - [NOTIFICATION]：走「通知使用权」读系统 MediaSession。需要敏感授权，只作为备用通道。
 */
enum class MetadataSource(val label: String) {
  NONE("无"),
  BROWSER("网易云官方接口（免授权）"),
  NOTIFICATION("系统级读取（通知使用权）"),
}

/**
 * 全局可读的「当前播放」快照。
 *
 * 写入方有两个（都在本进程内，直接用 StateFlow 共享，不需要 Binder）：
 *  - [MediaBrowserNowPlayingSource] —— 默认来源，零权限
 *  - [NowPlayingListenerService] —— 备用来源，需要通知使用权
 *
 * 读取方是 UI 与音频捕获服务。
 *
 * **仲裁规则**：两个来源各自记一份最新值（互不覆盖），对外返回 [BROWSER] 优先的那一份。
 * 这样打开备用通道不会把默认通道挤掉，而默认通道读不到时备用通道能立刻顶上。
 */
object NowPlayingState {

  private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
  val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

  /** 当前实际生效的来源，用于 UI 展示与排查 */
  private val _source = MutableStateFlow(MetadataSource.NONE)
  val source: StateFlow<MetadataSource> = _source.asStateFlow()

  /** MediaBrowser 是否已连上（连上 ≠ 有曲目，可能只是暂停着） */
  private val _browserConnected = MutableStateFlow(false)
  val browserConnected: StateFlow<Boolean> = _browserConnected.asStateFlow()

  private val _listenerConnected = MutableStateFlow(false)
  val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

  private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
  val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

  private var browserValue: NowPlaying? = null
  private var notificationValue: NowPlaying? = null

  /**
   * 某个来源发布最新快照（传 `null` 表示「这个来源现在没有东西」）。
   *
   * 会被主线程（MediaBrowser 回调）和后台线程（监听服务轮询）同时调用，所以加锁。
   */
  @Synchronized
  internal fun publish(source: MetadataSource, value: NowPlaying?) {
    when (source) {
      MetadataSource.BROWSER -> browserValue = value
      MetadataSource.NOTIFICATION -> notificationValue = value
      MetadataSource.NONE -> {
        browserValue = null
        notificationValue = null
      }
    }

    _nowPlaying.value = browserValue ?: notificationValue
    _source.value = when {
      browserValue != null -> MetadataSource.BROWSER
      notificationValue != null -> MetadataSource.NOTIFICATION
      else -> MetadataSource.NONE
    }
  }

  internal fun setBrowserConnected(value: Boolean) {
    _browserConnected.value = value
  }

  internal fun setListenerConnected(value: Boolean) {
    _listenerConnected.value = value
  }

  internal fun diagnostic(line: String) {
    _diagnostics.value = (_diagnostics.value + line).takeLast(40)
  }

  internal fun clearDiagnostics() {
    _diagnostics.value = emptyList()
  }
}
