package top.aerohaku.androidapp.playback

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.model.NowPlaying
import top.aerohaku.androidapp.model.TargetApp

/**
 * **备用**元数据通道：通过「通知使用权」读系统 MediaSession，
 * 把 [TargetApp.PACKAGE] 的当前播放写进 [NowPlayingState]。
 *
 * ⚠️ 默认不生效 —— 只有 [MetadataSettingsStore] 里的 `useNotificationFallback`
 * 被用户打开后才往 [NowPlayingState] 写数据。默认链路是 [MediaBrowserNowPlayingSource]，
 * 它直接连网易云对外开放的接口，**不需要任何敏感授权**。
 *
 * 保留这条通道的理由：老版本网易云可能没开放 `ucar.UCarService`，
 * 或者服务在但读不出曲目信息 —— 那时用户可以选择改用系统级读取。
 *
 * 读取策略：
 *  - 1 秒轮询兜底（实测最稳，MediaController 的 metadata 是本地缓存，开销极小）
 *  - 额外挂 [MediaSessionManager.OnActiveSessionsChangedListener]，会话出现/消失时立即反应
 *
 * 注意：本服务只负责「读元数据」，不碰音频。
 */
class NowPlayingListenerService : NotificationListenerService() {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var pollJob: Job? = null
  private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
  private var lastDiagKey: String? = null
  private var hadSession = false

  /** 与默认链路一样，防网易云推残缺包（没封面、没 mediaId） */
  private val carryOver = MetadataCarryOver()

  private val component: ComponentName
    get() = ComponentName(this, NowPlayingListenerService::class.java)

  override fun onListenerConnected() {
    super.onListenerConnected()
    NowPlayingState.setListenerConnected(true)
    NowPlayingState.diagnostic("通知使用权已连接，开始监听 ${TargetApp.LABEL}")

    val manager = getSystemService(MediaSessionManager::class.java)
    val listener = MediaSessionManager.OnActiveSessionsChangedListener {
      scope.launch { runCatching { pollOnce() } }
    }
    sessionListener = listener
    runCatching { manager?.addOnActiveSessionsChangedListener(listener, component) }
      .onFailure { NowPlayingState.diagnostic("挂会话变化监听失败：$it") }

    pollJob?.cancel()
    pollJob = scope.launch {
      while (isActive) {
        runCatching { pollOnce() }.onFailure { NowPlayingState.diagnostic("轮询异常：$it") }
        delay(POLL_INTERVAL_MS)
      }
    }
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    NowPlayingState.setListenerConnected(false)
    NowPlayingState.publish(MetadataSource.NOTIFICATION, null)
    NowPlayingState.diagnostic("通知使用权已断开")
    sessionListener?.let { listener ->
      runCatching { getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(listener) }
    }
    sessionListener = null
    pollJob?.cancel()
    pollJob = null
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun pollOnce() {
    // ⚠️ 备用通道没打开就什么都不做。
    // 系统可能因为别的原因把本服务绑定着，但默认链路（MediaBrowser，零权限）才是正主；
    // 那种情况下这里不该往 NowPlayingState 写数据。
    if (!MetadataSettingsStore.state.value.useNotificationFallback) {
      NowPlayingState.publish(MetadataSource.NOTIFICATION, null)
      return
    }

    val manager = getSystemService(MediaSessionManager::class.java) ?: return
    val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull() ?: return

    // 能成功调用 getActiveSessions 就说明通知使用权确实生效了。
    // 用它兜底纠正 listenerConnected：进程被回收后重启时，如果 onListenerConnected
    // 因为某种原因没有到达，UI 会一直显示「等待绑定」，而功能其实完全正常。
    if (!NowPlayingState.listenerConnected.value) {
      NowPlayingState.setListenerConnected(true)
      NowPlayingState.diagnostic("由轮询成功反推：监听服务已连通")
    }

    val controller: MediaController? = sessions.firstOrNull { it.packageName == TargetApp.PACKAGE }
    if (controller == null) {
      if (hadSession) {
        hadSession = false
        NowPlayingState.diagnostic("${TargetApp.LABEL} 的会话已消失")
      }
      NowPlayingState.publish(MetadataSource.NOTIFICATION, null)
      return
    }
    hadSession = true

    // 字段映射与默认链路（MediaBrowser）共用同一个实现，免得两条路读出来的东西不一致；
    // 同样要过「补齐」，否则残缺包照样会把封面与歌词打没
    val snapshot = carryOver.merge(snapshotOf(controller), hasUserRating(controller))
    NowPlayingState.publish(MetadataSource.NOTIFICATION, snapshot)

    val diagKey = "${snapshot.identity}|${snapshot.status}|${snapshot.positionMs}"
    if (diagKey != lastDiagKey) {
      lastDiagKey = diagKey
      NowPlayingState.diagnostic(snapshot.diagnosticLine("通知使用权"))
    }
  }

  private companion object {
    const val POLL_INTERVAL_MS = 1_000L
  }
}
