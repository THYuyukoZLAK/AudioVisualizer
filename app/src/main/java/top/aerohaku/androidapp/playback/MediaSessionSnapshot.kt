package top.aerohaku.androidapp.playback

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import top.aerohaku.androidapp.model.NowPlaying
import top.aerohaku.androidapp.model.PlaybackStatus

/**
 * 把 `MediaController` 的当前状态映射成 [NowPlaying]。
 *
 * 两条元数据链路（[MediaBrowserNowPlayingSource] / [NowPlayingListenerService]）用的是
 * 同一个 `MediaController` API，字段映射完全一致 —— 抽出来共用，免得以后只改一条、
 * 另一条悄悄跑偏。
 *
 * ⚠️ 字段含义与坑见 [NowPlaying] 的注释：`METADATA_KEY_MEDIA_ID` 就是网易云的歌曲 ID
 * （可直接拿去查歌词）、封面三个 key 实测是同一张 Bitmap（不用解析 Uri）、
 * 「红心」在 `USER_RATING` 里是 `RATING_HEART`。
 */
internal fun snapshotOf(controller: MediaController): NowPlaying {
  val metadata = controller.metadata
  val playbackState = controller.playbackState

  val artwork: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

  val rating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
  // 网易云用 RATING_HEART（Dumpsys 实测 style=1 rating=1.0）表示「红心」
  val liked = rating?.hasHeart() ?: false

  return NowPlaying(
    packageName = controller.packageName,
    mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
    title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
      ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
    artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
    album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
    artwork = artwork,
    durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
    status = PlaybackStatus.fromMediaSession(playbackState?.state ?: PlaybackState.STATE_NONE),
    positionMs = playbackState?.position ?: 0L,
    positionUpdatedElapsedRealtime = playbackState?.lastPositionUpdateTime ?: 0L,
    speed = playbackState?.playbackSpeed ?: 1f,
    liked = liked,
  )
}

/** 一行诊断文本。两条链路共用同一种格式，方便对着日志比较谁的数据更准 */
internal fun NowPlaying.diagnosticLine(tag: String): String =
  "[$tag] state=$status position=${positionMs}ms updated=$positionUpdatedElapsedRealtime " +
    "speed=$speed art=${artwork?.let { "${it.width}x${it.height}" } ?: "null"} mediaId=$mediaId"

/**
 * 本次 metadata 里到底有没有「红心」这一项。
 *
 * 不能只看 `liked` 的布尔值 —— 它是 `false` 时既可能是「没红心」也可能是「这次没推这一项」，
 * 补齐逻辑需要区分这两种情况。
 */
internal fun hasUserRating(controller: MediaController): Boolean =
  controller.metadata?.containsKey(MediaMetadata.METADATA_KEY_USER_RATING) == true

/**
 * metadata 推送的「记忆」：同一首歌内，用上一次拿到的值把这次缺的字段补上。
 *
 * ## 为什么必须要有这个东西
 *
 * **网易云推的是残缺包。** 真机 logcat 实测（同一首歌内连续推送）：
 *
 * ```
 * [onConnected]        keys=16 | ALBUM_ART=无此键
 * [onMetadataChanged]  keys=17 | ALBUM_ART=Bitmap(512x512)   ← 完整包
 * [onMetadataChanged]  keys=14 | ALBUM_ART=无此键            ← 位置更新包，不带封面
 * [onMetadataChanged]  keys=16 | ALBUM_ART=无此键
 * [onMetadataChanged]  keys=19 | ALBUM_ART=Bitmap(512x512)   ← 又完整了
 * ```
 *
 * 键数在 **14~20** 之间跳，说明它按需只塞一部分字段。缺的偏偏都是「大」字段：
 *
 * - 封面 Bitmap（几百 KB）→ 缺了就是背景变纯黑、封面小图消失
 * - `media_id`（歌词查询的 key）→ 缺了 `VisualizerViewModel` 会 `load(null)`，
 *   **歌词会被整块清掉**
 *
 * 这不是本项目的 bug，是对方的行为；但我们必须自己扛住 —— 否则界面上会
 * 「封面和歌词不定时消失又回来」。
 *
 * ## 判定「同一首歌」
 *
 * 用 **专辑|标题|歌手**，**不能**用 `mediaId` —— 因为它本身就是可能缺的那个字段。
 */
internal class MetadataCarryOver {

  private var songKey: String? = null
  private var last: NowPlaying? = null

  fun merge(fresh: NowPlaying, ratingPresent: Boolean): NowPlaying {
    val key = fresh.songKey
    val prev = last?.takeIf { key == songKey }

    val merged = if (prev == null) {
      fresh
    } else {
      fresh.copy(
        mediaId = fresh.mediaId ?: prev.mediaId,
        artwork = fresh.artwork ?: prev.artwork,
        title = fresh.title ?: prev.title,
        artist = fresh.artist ?: prev.artist,
        album = fresh.album ?: prev.album,
        durationMs = if (fresh.durationMs > 0L) fresh.durationMs else prev.durationMs,
        liked = if (ratingPresent) fresh.liked else prev.liked,
      )
    }

    songKey = key
    last = merged
    return merged
  }

  fun reset() {
    songKey = null
    last = null
  }

  private val NowPlaying.songKey: String
    get() = listOfNotNull(album, title, artist).joinToString("|")
}
