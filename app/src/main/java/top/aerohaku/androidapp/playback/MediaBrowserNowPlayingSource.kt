package top.aerohaku.androidapp.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.model.TargetApp

/**
 * 默认的元数据来源：直接连网易云对外开放的 `MediaBrowserService`，读它自己的 `MediaSession`。
 *
 * ## 为什么这条路更好
 *
 * 「通知使用权」是系统里最容易被当成隐私风险的授权之一（能读所有应用的通知）。
 * 而 `ucar.UCarService`（`ucar` = 车机）是**网易云主动对外声明的标准接口**，
 * manifest 里没有 `permission` 限制，本来就是给车机/表盘这类第三方客户端连的。
 * 2026-10-05 真机实测：连得上、拿得到 session token、标题/歌手/专辑/封面/时长/位置/`mediaId`
 * 全都有，且 `mediaId` 与通知使用权路径的 `songId` 完全一致。
 *
 * 客户端只用**平台** API（`android.media.browse.MediaBrowser`，API 21+），
 * 本项目 `minSdk = 29` ⇒ **零依赖**。
 *
 * ## ⚠️ 副作用：会把网易云的进程拉起来
 *
 * `MediaBrowser.connect()` 走的是 `bindService(BIND_AUTO_CREATE)`，而 `UCarService`
 * 跑在网易云的**主进程**里。也就是说，只要本 App 在跑就可能把没在播放的网易云拉起来。
 *
 * 所以这里不常驻，用「保持理由」（retainer）计数控制：
 * 界面在前台、或者音频捕获服务在跑，才保持连接；都撤了就断开并停止重连。
 *
 * ## 为什么不用 `MediaController.registerCallback` 就够了、还要轮询
 *
 * 实测某些 ROM/版本上 `onMetadataChanged` 不一定会到（尤其是位置更新），
 * 1 秒轮询读取的是本地缓存，开销极小，用来兜底。
 */
object MediaBrowserNowPlayingSource {

  /** 车机互联服务。名字与路径来自 `dumpsys package com.netease.cloudmusic` 实测 */
  private const val SERVICE_CLASS = "${TargetApp.PACKAGE}.module.ucar.UCarService"

  private const val POLL_INTERVAL_MS = 1_000L

  /**
   * 连不上时的重试间隔。故意拉长：每次失败都意味着一次 bind 尝试，
   * 太频繁没意义（正常情况就是「网易云没在跑」）。
   */
  private const val RECONNECT_INTERVAL_MS = 5_000L

  private val component = ComponentName(TargetApp.PACKAGE, SERVICE_CLASS)

  /**
   * 「保持连接」的理由。集中定义，避免调用点把字符串写错、
   * 导致 `release` 对不上 `retain` 而永远断不开。
   */
  const val REASON_UI = "ui"
  const val REASON_CAPTURE = "capture"

  /**
   * 「断开」的宽限期。
   *
   * 系统的屏幕捕获授权对话框会把本 Activity 顶到 `onStop`，用户点一下回来通常是几秒 ——
   * 如果 `onStop` 就立刻断开、回来又重建，会白白产生一次「断开→重连」的抖动：
   * 界面上一闪、而且重连后拿到的往往是网易云推的**残缺包**（见 [MetadataCarryOver]）。
   * 所以等一会儿再断，期间只要又有人 `retain` 就取消。
   */
  private const val RELEASE_GRACE_MS = 8_000L

  /** 全程在主线程：`bindService` 与 `MediaBrowser` 的回调都投递回主线程 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private var appContext: Context? = null
  private var loopJob: Job? = null
  private var releaseJob: Job? = null

  /** 网易云会推残缺包，用它把缺的字段补齐。⚠️ 故意**不**因重连而清空，见类里的宽限期注释 */
  private val carryOver = MetadataCarryOver()

  private var browser: MediaBrowser? = null
  private var controller: MediaController? = null
  private var connected = false
  private var connecting = false
  private var lastDiagKey: String? = null

  /** 「要保持连接」的理由集合，为空即断开。见类注释里的副作用说明 */
  private val retainers = mutableSetOf<String>()

  fun retain(context: Context, reason: String) {
    appContext = context.applicationContext
    // 宽限期内又回来了：取消待执行的断开
    releaseJob?.cancel()
    releaseJob = null
    retainers += reason
    ensureLoop()
  }

  fun release(reason: String) {
    retainers -= reason
    if (retainers.isNotEmpty()) return

    releaseJob?.cancel()
    releaseJob = scope.launch {
      delay(RELEASE_GRACE_MS)
      if (retainers.isNotEmpty()) return@launch
      loopJob?.cancel()
      loopJob = null
      teardown()
      NowPlayingState.publish(MetadataSource.BROWSER, null)
    }
  }

  private fun ensureLoop() {
    if (loopJob?.isActive == true) return
    loopJob = scope.launch {
      NowPlayingState.diagnostic(
        "元数据：开始连接 ${TargetApp.LABEL} 的 MediaBrowserService（$SERVICE_CLASS）",
      )
      while (currentCoroutineContext().isActive) {
        if (!connected && !connecting) connect()
        if (connected) pollOnce()
        delay(if (connected) POLL_INTERVAL_MS else RECONNECT_INTERVAL_MS)
      }
    }
  }

  private fun connect() {
    val context = appContext ?: return
    connecting = true
    // 构造与 connect() 必须在同一个 Looper 线程上，所以整条链路都在主线程跑
    val fresh = MediaBrowser(context, component, connectionCallback, null)
    browser = fresh
    runCatching { fresh.connect() }.onFailure { t ->
      teardown()
      NowPlayingState.diagnostic(
        "元数据：connect() 抛异常 ${t.javaClass.simpleName}: ${t.message}",
      )
    }
  }

  private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
    override fun onConnected() {
      connecting = false
      connected = true

      val context = appContext
      // MediaBrowser.getSessionToken() 返回的**已经是**平台 android.media.session.MediaSession.Token，
      // 不像 androidx 的 MediaSessionCompat.Token 还要再取一层 .sessionToken
      val token = browser?.sessionToken
      if (context == null || token == null) {
        NowPlayingState.diagnostic("元数据：已连接但拿不到 sessionToken，稍后重试")
        teardown()
        return
      }

      NowPlayingState.setBrowserConnected(true)
      controller = MediaController(context, token).also { it.registerCallback(controllerCallback) }
      NowPlayingState.diagnostic("元数据(MediaBrowser)：已连接，拿到会话")
      // 把这个会话交给播放控制层（上一首/播放暂停/下一首/播放列表）
      TransportController.attach(controller)
      pollOnce()
    }

    override fun onConnectionFailed() {
      teardown()
      NowPlayingState.diagnostic(
        "元数据(MediaBrowser)：连接失败（${TargetApp.LABEL} 未运行或服务拒绝），" +
          "${RECONNECT_INTERVAL_MS / 1000}s 后重试",
      )
    }

    override fun onConnectionSuspended() {
      teardown()
      NowPlayingState.diagnostic("元数据(MediaBrowser)：连接被挂起，稍后重连")
    }
  }

  private val controllerCallback = object : MediaController.Callback() {
    override fun onMetadataChanged(metadata: MediaMetadata?) = pollOnce()

    override fun onPlaybackStateChanged(state: PlaybackState?) {
      TransportController.onPlaybackStateChanged(state)
      pollOnce()
    }

    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
      TransportController.refreshQueue()
    }

    override fun onSessionDestroyed() = teardown()
  }

  /** 断开并复位。可能从 `MediaBrowser` 自己的回调里调用（官方推荐在失败回调里 disconnect） */
  private fun teardown() {
    controller?.let { c -> runCatching { c.unregisterCallback(controllerCallback) } }
    controller = null
    browser?.let { b -> runCatching { b.disconnect() } }
    browser = null
    connected = false
    connecting = false
    NowPlayingState.setBrowserConnected(false)
    // 会话没了，控制栏也得跟着失效（否则按钮按下去静默没反应）
    TransportController.attach(null)
  }

  private fun pollOnce() {
    val current = controller
    // 连上了但没曲目信息（例如刚启动还没开播）→ 明确报「没有」，别把上一首留在界面上
    if (current == null || current.metadata == null) {
      NowPlayingState.publish(MetadataSource.BROWSER, null)
      return
    }

    // ⚠️ 必须过一遍「补齐」：网易云会推残缺包（没封面、没 mediaId），
    // 直接拿它就发布会导致背景变黑、歌词被清空。见 [MetadataCarryOver]
    val snapshot = carryOver.merge(snapshotOf(current), hasUserRating(current))
    NowPlayingState.publish(MetadataSource.BROWSER, snapshot)

    val key = "${snapshot.identity}|${snapshot.status}|${snapshot.positionMs}|${snapshot.artwork?.width}"
    if (key != lastDiagKey) {
      lastDiagKey = key
      NowPlayingState.diagnostic(snapshot.diagnosticLine("MediaBrowser"))
      // 换歌/换状态了，让播放列表刷新一下「当前项」高亮
      TransportController.refreshQueue()
    }
  }
}
