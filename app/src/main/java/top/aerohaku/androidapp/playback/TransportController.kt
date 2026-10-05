package top.aerohaku.androidapp.playback

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放列表（播放队列）里的一项，只留 UI 需要的字段 */
data class QueueEntry(
  val queueId: Long,
  val title: String,
  val artist: String?,
  val mediaId: String?,
  /** 尽力而为：平台 API 不告诉你「当前是第几项」，只能拿 mediaId 比 */
  val isCurrent: Boolean,
)

/**
 * 播放控制：上一首 / 播放暂停 / 下一首 / 跳转到播放列表某项 / 循环模式。
 *
 * 全部走网易云通过 `MediaBrowser` 暴露的标准 `MediaController.TransportControls`。
 * 2026-10-05 真机实测（临时探针打出来的）：
 *
 * ```
 * rootId=nm_root
 * actions=0x336 → PLAY,PAUSE,PLAY_PAUSE,SKIP_NEXT,SKIP_PREV,SEEK   ← 它自己声明支持这些
 * customActions=5
 *   ucar.media.action.PLAY_MODE   name=播放模式                     ← 循环/随机模式走这个私有 action
 * queue(API30+)=13                                                 ← 播放队列能直接读
 * ```
 *
 * ⚠️ 循环模式这里有个**明摆着的缺陷**，不是没做而是做不到：
 * **读不到当前是哪一种模式**。
 *
 *  - 平台 `PlaybackState` 里根本没有 repeat mode 字段 ——
 *    `PlaybackState.REPEAT_MODE_*` 与 `MediaController.getRepeatMode()` 在 `android.jar` 里
 *    都不存在（已用 `javap` 逐个确认过）
 *  - androidx 那条路要的 `androidx.media.session.MediaControllerCompat`，
 *    在 `androidx.media:media:1.7.0` 里也没有（只有 legacy 名的
 *    `android.support.v4.media.session.MediaControllerCompat`）
 *  - 网易云自己在 metadata 里放了 `ucar.media.metadata.PLAY_MODE`，但它是**非标量类型**，
 *    `getString` 返回 null、`getLong` 返回 0，而 `MediaMetadata` 没有通用取值接口，
 *    `getDescription().extras` 也不会把未识别的键带出来（实测恒为 null）
 *
 * 所以那个按钮只能「点一下切一下」，界面上不显示当前模式。
 *
 * ⚠️ 其次：网易云**声明**了这些 `actions`，但声明与实现是两回事。
 * 真机上按下去有没有反应，只能挨个试。
 */
object TransportController {

  /**
   * 网易云声明的可执行动作位掩码（`actions=0x336`）。
   * UI 用它决定哪些按钮该亮着。
   */
  private val _supportedActions = MutableStateFlow(0L)
  val supportedActions: StateFlow<Long> = _supportedActions.asStateFlow()

  private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
  val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

  /** 是否连上了可用的会话。连不上时整条控制栏应当隐藏 */
  private val _available = MutableStateFlow(false)
  val available: StateFlow<Boolean> = _available.asStateFlow()

  private var controller: MediaController? = null

  internal fun attach(controller: MediaController?) {
    this.controller = controller
    _available.value = controller != null
    if (controller == null) {
      _queue.value = emptyList()
      _supportedActions.value = 0L
    } else {
      _supportedActions.value = controller.playbackState?.actions ?: 0L
      refreshQueue()
    }
  }

  internal fun onPlaybackStateChanged(state: PlaybackState?) {
    _supportedActions.value = state?.actions ?: 0L
  }

  /** `getQueue()` 是本地缓存读，开销极小；网易云会主动推队列变化，所以随时刷都行 */
  internal fun refreshQueue() {
    // getQueue() 是 API 30+ 才有的
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val current = controller ?: return
    val items = runCatching { current.queue }.getOrNull().orEmpty()
    val playing = NowPlayingState.nowPlaying.value

    _queue.value = items.map { item ->
      val description = item.description
      val mediaId = description.mediaId
      val title = description.title?.toString().orEmpty()
      QueueEntry(
        queueId = item.queueId,
        title = title,
        artist = description.subtitle?.toString(),
        mediaId = mediaId,
        // 平台 API 不告诉咱「当前是第几项」，只能猜：
        // 先比 mediaId（两条链路的格式常不一致，多半比不中），
        // 比不中再退回比标题 —— 实测这样才高亮得出来。
        isCurrent = when {
          mediaId != null && mediaId == playing?.mediaId -> true
          title.isNotBlank() && title == playing?.title -> true
          else -> false
        },
      )
    }
  }

  fun previous() {
    runCatching { controller?.transportControls?.skipToPrevious() }
  }

  fun play() {
    runCatching { controller?.transportControls?.play() }
  }

  fun pause() {
    runCatching { controller?.transportControls?.pause() }
  }

  fun next() {
    runCatching { controller?.transportControls?.skipToNext() }
  }

  fun jumpTo(queueId: Long) {
    runCatching { controller?.transportControls?.skipToQueueItem(queueId) }
  }
}
