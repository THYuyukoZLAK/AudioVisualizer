package top.aerohaku.androidapp.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 探针：网易云音乐客户端有没有对外开放的接口，能拿到「正在播放什么」。
 *
 * ## 背景
 *
 * 现在元数据靠「通知使用权」（`NotificationListenerService`），那是系统里最容易被
 * 当成隐私风险的一项授权。而 `dumpsys package com.netease.cloudmusic` 显示它对外声明了：
 *
 * ```
 * android.media.browse.MediaBrowserService:
 *   com.netease.cloudmusic/.module.ucar.UCarService        ← 无 permission 字段
 * com.netease.cloudmusic.third.api.CMApiService:
 *   com.netease.cloudmusic/.third.api.CMApiService         ← 无 permission 字段
 * ```
 *
 * `ucar` = 车机（UCar）。**`MediaBrowserService` 是 Android 平台标准接口**，
 * 本来就是给车机/表盘这类第三方客户端连的：连上 → `getSessionToken()` →
 * `MediaController` → 元数据与播放位置。**全程不需要通知使用权。**
 *
 * ## 但 dumpsys 只能证明「服务对外可达」
 *
 * 它证明不了「服务愿意给数据」。`onGetRoot()` 是网易云自己实现的，
 * 完全可能校验调用方包名、只放行车机应用。所以必须真连一次。
 *
 * ## 同时探 `CMApiService`
 *
 * 名字看着就是「第三方接入 API」，但它是**私有 AIDL** —— 拿不到接口定义就没法调用。
 * 这里只 bind 一下、把 binder 的接口描述符打出来，确认它到底是什么，不指望能用。
 *
 * ## 客户端选型：用平台 API，不用 androidx
 *
 * `android.media.browse.MediaBrowser` 从 API 21 就有，且本项目 `minSdk = 29`，
 * 所以**零依赖**就能连。
 * （顺带一提：`androidx.media:media` 这个 AAR 里其实**没有** `androidx.media.MediaBrowserCompat`，
 * 只有 `MediaBrowserServiceCompat` 等 service 侧类，所以那条路也走不通。）
 */
object NeteaseApiProbe {

  private const val PKG = "com.netease.cloudmusic"
  private const val CAR_SERVICE = "$PKG.module.ucar.UCarService"
  private const val THIRD_API_SERVICE = "$PKG.third.api.CMApiService"

  private const val BROWSER_ACTION = "android.media.browse.MediaBrowserService"
  private const val THIRD_API_ACTION = "$PKG.third.api.CMApiService"

  private const val TIMEOUT_MS = 5_000L

  /**
   * 跑一遍探测。**必须在主线程调用** ——
   * `bindService` 与 `MediaBrowserCompat` 的回调都投递到主线程。
   */
  suspend fun run(context: Context): List<String> {
    val log = ArrayList<String>()
    log += "══ 网易云对外接口探测 ══"

    log += bindAndDescribe(
      context = context,
      action = BROWSER_ACTION,
      serviceClass = CAR_SERVICE,
      label = "UCarService（标准 MediaBrowserService）",
    )

    log += bindAndDescribe(
      context = context,
      action = THIRD_API_ACTION,
      serviceClass = THIRD_API_SERVICE,
      label = "CMApiService（私有 AIDL）",
    )

    log += browserProbe(context)
    return log
  }

  /**
   * 裸 bind 一次，只看 binder 的接口描述符。
   *
   * 这一步的价值：能区分服务实现的是**平台接口**（`android.media.browse.IMediaBrowserService`）
   * 还是 **androidx 兼容接口**（`...IMediaBrowserServiceCompat`）——
   * 两者需要不同的客户端，先看清楚再决定用哪个。
   */
  private suspend fun bindAndDescribe(
    context: Context,
    action: String,
    serviceClass: String,
    label: String,
  ): String = withTimeoutOrNull(TIMEOUT_MS) {
    suspendCancellableCoroutine<String> { cont ->
      var resumed = false
      fun finish(text: String) {
        if (resumed) return
        resumed = true
        cont.resume(text)
      }

      val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
          val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
          runCatching { context.unbindService(this) }
          finish("√ $label：bind 成功，binder 接口 = $descriptor")
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        override fun onNullBinding(name: ComponentName?) {
          finish("× $label：onNullBinding —— 服务拒绝被绑定")
        }

        override fun onBindingDied(name: ComponentName?) {
          finish("× $label：onBindingDied")
        }
      }

      val bound = runCatching {
        context.bindService(
          Intent(action).setComponent(ComponentName(PKG, serviceClass)),
          connection,
          Context.BIND_AUTO_CREATE,
        )
      }.getOrElse { t ->
        finish("× $label：bindService 抛异常 ${t.javaClass.simpleName}: ${t.message}")
        return@suspendCancellableCoroutine
      }

      if (!bound) finish("× $label：bindService 返回 false（服务不可见或被系统拒绝）")
    }
  } ?: "× $label：${TIMEOUT_MS}ms 内无任何回调（超时）"

  /** 用平台标准的 `android.media.browse.MediaBrowser` 真正连一次，看能不能拿到会话与元数据 */
  private suspend fun browserProbe(context: Context): String {
    val component = ComponentName(PKG, CAR_SERVICE)

    val lines = withTimeoutOrNull(TIMEOUT_MS) {
      suspendCancellableCoroutine<List<String>> { cont ->
        var resumed = false
        var browser: MediaBrowser? = null

        fun finish(result: List<String>) {
          if (resumed) return
          resumed = true
          runCatching { browser?.disconnect() }
          cont.resume(result)
        }

        val callback = object : MediaBrowser.ConnectionCallback() {
          override fun onConnected() {
            val out = ArrayList<String>()
            out += "√ MediaBrowser 已连接 UCarService"

            val token = runCatching { browser?.sessionToken }.getOrNull()
            if (token == null) {
              out += "× 连上了但 sessionToken 为 null —— 服务没给会话，这条路不通"
              finish(out)
              return
            }
            out += "√ 拿到 sessionToken：这条路可行"

            // MediaBrowser.getSessionToken() 返回的**已经是**平台 android.media.session.MediaSession.Token，
            // 不像 androidx 的 MediaSessionCompat.Token 还要再取一层 .sessionToken
            val controller = runCatching { MediaController(context, token) }
              .getOrElse { t ->
                out += "× 构造 MediaController 失败：${t.javaClass.simpleName}: ${t.message}"
                finish(out)
                return
              }
            out += "  会话属于 = ${controller.packageName}"

            val metadata = controller.metadata
            if (metadata == null) {
              out += "× metadata 为 null（当前可能没有在播放）"
            } else {
              out += "  标题 = ${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}"
              out += "  歌手 = ${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}"
              out += "  专辑 = ${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)}"
              out += "  时长 = ${metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)} ms"
              val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
              out += "  封面 = ${if (art != null) "${art.width}x${art.height}" else "无"}"
              // 歌词靠 mediaId 去 music.163.com 查，所以这个字段在不在很关键
              val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
              out += "  mediaId = $mediaId"
            }

            val playback = controller.playbackState
            if (playback == null) {
              out += "× playbackState 为 null"
            } else {
              out += "  播放状态 = ${playback.state}，位置 = ${playback.position} ms"
            }

            finish(out)
          }

          override fun onConnectionFailed() {
            finish(listOf("× MediaBrowser 连接失败（onConnectionFailed）—— 服务拒绝了本应用"))
          }

          override fun onConnectionSuspended() {
            finish(listOf("× 连接被挂起（onConnectionSuspended）"))
          }
        }

        browser = MediaBrowser(context, component, callback, null)

        runCatching { browser?.connect() }.onFailure { t ->
          finish(listOf("× connect() 抛异常 ${t.javaClass.simpleName}: ${t.message}"))
        }
      }
    }

    return lines?.joinToString("\n") ?: "× MediaBrowser：${TIMEOUT_MS}ms 超时"
  }
}
