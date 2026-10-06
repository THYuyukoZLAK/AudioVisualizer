package top.aerohaku.androidapp.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.R
import top.aerohaku.androidapp.model.TargetApp
import top.aerohaku.androidapp.playback.MediaBrowserNowPlayingSource
import top.aerohaku.androidapp.playback.NowPlayingState

/**
 * 音频捕获服务。
 *
 * 用哪台引擎由 [AudioSourceSettingsStore] 里的来源设置决定：
 *
 * - [AudioSource.SYSTEM_MIX]（备选）—— `Visualizer` 挂在全局混音上。
 *   **不起前台服务**：这条路没有一次性令牌要保，也就没必要挂一条常驻通知。
 *   服务从可见的 Activity 里 `startService` 起来即可。
 *
 * - [AudioSource.PLAYBACK_CAPTURE] —— `MediaProjection` + `AudioPlaybackCapture`。
 *   下面三条硬性约束都来自真机实测：
 *   1. Android 14+ 必须先 `startForeground(type=mediaProjection)` 再 `getMediaProjection()`
 *   2. 服务一旦起来就**长期持有投影**，自动启停只停「读音频」不停服务 ——
 *      否则投影被回收，下次又得弹一次授权
 *   3. 投影被用户从状态栏停掉时，服务要跟着结束
 *
 * 除上述差异外，自动启停、状态上报、帧转发两条链路完全共用。
 */
class AudioCaptureService : Service() {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  /** 当前这台引擎；[ACTION_START] 之前为 null */
  private var engine: AudioFrameSource? = null
  private var frameJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()

    // 只要捕获在跑就保持元数据连接：自动启停依赖 NowPlayingState，
    // 界面退到后台时也得能知道「现在到底在不在播」。
    MediaBrowserNowPlayingSource.retain(this, MediaBrowserNowPlayingSource.REASON_CAPTURE)

    // 自动启停：播放状态 × 自动开关，任一变化都重新决策
    scope.launch {
      combine(NowPlayingState.nowPlaying, CaptureController.autoStartStop) { nowPlaying, auto ->
        nowPlaying to auto
      }.collect { (nowPlaying, auto) -> applyPolicy(nowPlaying?.isPlaying == true, auto) }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val source = AudioSourceSettingsStore.source.value
        attachEngine(source)

        if (source == AudioSource.PLAYBACK_CAPTURE) {
          // 必须先起前台服务，否则 Android 14+ 会拒绝 getMediaProjection
          startForegroundNow()
          if (!attachProjection(intent)) return START_NOT_STICKY
        } else {
          CaptureController.setStatus(
            CaptureController.Status(CaptureController.Phase.STARTING, message = "正在接入全局混音…"),
          )
        }

        // 通道就绪后立刻按当前策略决策一次（不必等播放状态变化）
        applyPolicy(
          isPlaying = NowPlayingState.nowPlaying.value?.isPlaying == true,
          auto = CaptureController.autoStartStop.value,
        )
      }

      ACTION_STOP -> {
        engine?.release()
        engine = null
        frameJob?.cancel()
        frameJob = null
        CaptureController.setStatus(
          CaptureController.Status(CaptureController.Phase.STOPPED, message = "已停止"),
        )
        stopSelf()
      }

      else -> stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    frameJob?.cancel()
    engine?.release()
    engine = null
    scope.cancel()
    MediaBrowserNowPlayingSource.release(MediaBrowserNowPlayingSource.REASON_CAPTURE)
    super.onDestroy()
  }

  // ------------------------------------------------------------------ 引擎装配

  /**
   * 装配指定来源的引擎并接上帧转发。
   *
   * 无条件重建：`ACTION_START` 本来就可能带着一份**新的**投影令牌进来，
   * 沿用旧对象反而会把「旧令牌已失效」这种状态带过来。
   */
  private fun attachEngine(source: AudioSource) {
    frameJob?.cancel()
    engine?.release()

    val created: AudioFrameSource = when (source) {
      AudioSource.SYSTEM_MIX -> SystemMixCapture(this)
      AudioSource.PLAYBACK_CAPTURE -> AudioCaptureEngine(this).apply {
        onProjectionStopped = { stopSelf() }
      }
    }
    engine = created

    // 可视化帧（频谱 / 波形 / 左右声道电平）转发给 UI
    frameJob = scope.launch { created.frame.collect { CaptureController.setAudioFrame(it) } }
  }

  /** 拿到并装上投影令牌。返回 false 表示已经报错并停了服务 */
  private fun attachProjection(intent: Intent): Boolean {
    val resultData = extractResultData(intent)
    if (resultData == null) {
      fail("缺少 MediaProjection 授权数据")
      return false
    }

    CaptureController.setStatus(
      CaptureController.Status(CaptureController.Phase.STARTING, message = "正在建立捕获通道…"),
    )

    val projection = engine as? AudioCaptureEngine
    if (projection == null) {
      fail("内部错误：当前引擎不是投影捕获")
      return false
    }

    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val error = projection.attachProjection(resultCode, resultData)
    if (error != null) {
      fail(error)
      return false
    }

    projection.uidFailureReason()?.let {
      CaptureController.updateStatus { status -> status.copy(message = "uid 查询失败：$it") }
    }
    return true
  }

  private fun fail(message: String) {
    CaptureController.setStatus(
      CaptureController.Status(CaptureController.Phase.ERROR, message = message),
    )
    stopSelf()
  }

  // ------------------------------------------------------------------ 自动启停

  /** 自动启停的核心决策 —— 两条链路一视同仁 */
  private fun applyPolicy(isPlaying: Boolean, auto: Boolean) {
    val active = engine ?: return
    // 投影要等令牌；混音随时就绪
    if (!active.isReady) return

    val shouldRecord = if (auto) isPlaying else true

    if (shouldRecord) {
      if (active.isRecording) {
        CaptureController.updateStatus { it.copy(heldBecauseIdle = false) }
        return
      }
      val error = active.startRecording()
      if (error != null) {
        CaptureController.setStatus(
          CaptureController.Status(CaptureController.Phase.ERROR, message = error),
        )
      } else {
        CaptureController.setStatus(
          CaptureController.Status(
            phase = CaptureController.Phase.RUNNING,
            format = active.formatDescription.orEmpty(),
            message = if (auto) "跟随播放自动启停" else "手动模式（持续捕获）",
            heldBecauseIdle = false,
          ),
        )
      }
    } else {
      if (active.isRecording) active.stopRecording()
      // 「停」只停读音频：投影路线要保住令牌，混音路线也顺手保住 Visualizer 对象，
      // 免得下次播放又要重新建一遍
      val kept = if (AudioSourceSettingsStore.source.value == AudioSource.PLAYBACK_CAPTURE) {
        "投影保持有效"
      } else {
        "混音接入保持有效"
      }
      CaptureController.updateStatus {
        it.copy(
          heldBecauseIdle = true,
          message = "${TargetApp.LABEL} 未在播放，已暂停读取音频（$kept）",
        )
      }
    }
  }

  // ------------------------------------------------------------------ 前台服务

  private fun extractResultData(intent: Intent): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(EXTRA_RESULT_DATA)
    }

  private fun ensureChannel() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "音频捕获", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  /** ⚠️ 只有投影路线会走到这里；混音路线刻意不起前台服务 */
  private fun startForegroundNow() {
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("正在捕获 ${TargetApp.LABEL} 的音频")
      .setContentText("仅读取音频，不录制屏幕画面")
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    private const val CHANNEL_ID = "audio-capture"
    private const val NOTIFICATION_ID = 0x9A02

    const val ACTION_START = "top.aerohaku.androidapp.capture.START"
    const val ACTION_STOP = "top.aerohaku.androidapp.capture.STOP"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    /** 投影路线：把用户刚授权拿到的令牌交给服务，走前台服务 */
    fun start(context: Context, resultCode: Int, data: Intent) {
      val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, data)
      }
      context.startForegroundService(intent)
    }

    /**
     * 全局混音路线：没有投影令牌，也就**不起前台服务**（于是没有常驻通知）。
     *
     * ⚠️ 必须用 `startService` 而不是 `startForegroundService` ——
     * 后者要求服务在 5 秒内调用 `startForeground()`，而这条路压根不调，会被系统判为失败。
     * 从**可见的 Activity** 调用 `startService` 是允许的（后台启动服务的限制不适用于前台界面）。
     */
    fun startMix(context: Context) {
      context.startService(
        Intent(context, AudioCaptureService::class.java).apply { action = ACTION_START },
      )
    }

    fun stop(context: Context) {
      runCatching {
        context.startService(Intent(context, AudioCaptureService::class.java).apply { action = ACTION_STOP })
      }
    }
  }
}
