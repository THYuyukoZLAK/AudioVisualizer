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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.R
import top.aerohaku.androidapp.model.TargetApp
import top.aerohaku.androidapp.playback.MediaBrowserNowPlayingSource
import top.aerohaku.androidapp.playback.NowPlayingState

/**
 * 音频捕获前台服务。
 *
 * 三条硬性约束（都来自真机实测）：
 *  1. Android 14+ 必须先 `startForeground(type=mediaProjection)` 再 `getMediaProjection()`
 *  2. 服务一旦起来就**长期持有投影**，自动启停只停「读音频」不停服务 ——
 *     否则投影被回收，下次又得弹一次授权
 *  3. 投影被用户从状态栏停掉时，服务要跟着结束
 */
class AudioCaptureService : Service() {

  private lateinit var engine: AudioCaptureEngine
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    engine = AudioCaptureEngine(this).apply {
      onProjectionStopped = { stopSelf() }
    }
    ensureChannel()

    // 只要捕获在跑就保持元数据连接：自动启停依赖 NowPlayingState，
    // 界面退到后台时也得能知道「现在到底在不在播」。
    MediaBrowserNowPlayingSource.retain(this, MediaBrowserNowPlayingSource.REASON_CAPTURE)

    // 可视化帧（频谱 / 波形 / 左右声道电平）转发给 UI
    scope.launch { engine.frame.collect { CaptureController.setAudioFrame(it) } }

    // 自动启停：播放状态 × 自动开关，任一变化都重新决策
    scope.launch {
      combine(NowPlayingState.nowPlaying, CaptureController.autoStartStop) { nowPlaying, auto -> nowPlaying to auto }
        .collect { (nowPlaying, auto) -> applyPolicy(nowPlaying?.isPlaying == true, auto) }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        // 必须先起前台服务，否则 Android 14+ 会拒绝 getMediaProjection
        startForegroundNow()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = extractResultData(intent)
        if (resultData == null) {
          CaptureController.setStatus(
            CaptureController.Status(CaptureController.Phase.ERROR, message = "缺少 MediaProjection 授权数据"),
          )
          stopSelf()
        } else {
          CaptureController.setStatus(CaptureController.Status(CaptureController.Phase.STARTING, message = "正在建立捕获通道…"))
          val error = engine.attachProjection(resultCode, resultData)
          if (error != null) {
            CaptureController.setStatus(CaptureController.Status(CaptureController.Phase.ERROR, message = error))
            stopSelf()
          } else {
            engine.uidFailureReason()?.let {
              CaptureController.updateStatus { status -> status.copy(message = "uid 查询失败：$it") }
            }
            // 授权成功，立刻按当前策略决策一次（不必等状态变化）
            applyPolicy(
              isPlaying = NowPlayingState.nowPlaying.value?.isPlaying == true,
              auto = CaptureController.autoStartStop.value,
            )
          }
        }
      }

      ACTION_STOP -> {
        engine.release()
        CaptureController.setStatus(CaptureController.Status(CaptureController.Phase.STOPPED, message = "已停止"))
        stopSelf()
      }

      else -> {
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    engine.release()
    scope.cancel()
    MediaBrowserNowPlayingSource.release(MediaBrowserNowPlayingSource.REASON_CAPTURE)
    super.onDestroy()
  }

  /** 自动启停的核心决策 */
  private fun applyPolicy(isPlaying: Boolean, auto: Boolean) {
    if (!engine.hasProjection) return

    val shouldRecord = if (auto) isPlaying else true

    if (shouldRecord) {
      if (engine.isRecording) {
        CaptureController.updateStatus { it.copy(heldBecauseIdle = false) }
        return
      }
      val error = engine.startRecording()
      if (error != null) {
        CaptureController.setStatus(
          CaptureController.Status(CaptureController.Phase.ERROR, message = error),
        )
      } else {
        CaptureController.setStatus(
          CaptureController.Status(
            phase = CaptureController.Phase.RUNNING,
            format = engine.formatDescription.orEmpty(),
            message = if (auto) "跟随播放自动启停" else "手动模式（持续捕获）",
            heldBecauseIdle = false,
          ),
        )
      }
    } else {
      if (engine.isRecording) engine.stopRecording()
      CaptureController.updateStatus {
        it.copy(
          heldBecauseIdle = true,
          message = "${TargetApp.LABEL} 未在播放，已暂停读取音频（投影保持有效）",
        )
      }
    }
  }

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

    fun start(context: Context, resultCode: Int, data: Intent) {
      val intent = Intent(context, AudioCaptureService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, data)
      }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      runCatching {
        context.startService(Intent(context, AudioCaptureService::class.java).apply { action = ACTION_STOP })
      }
    }
  }
}
