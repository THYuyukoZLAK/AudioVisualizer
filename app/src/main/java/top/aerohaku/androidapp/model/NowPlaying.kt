package top.aerohaku.androidapp.model

import android.graphics.Bitmap

/** 播放状态（对齐 MediaSession 的 PlaybackState，只保留用得到的） */
enum class PlaybackStatus {
  PLAYING,
  PAUSED,
  STOPPED,
  BUFFERING,
  ERROR,
  NONE,
  ;

  val isPlaying: Boolean get() = this == PLAYING

  companion object {
    fun fromMediaSession(state: Int): PlaybackStatus =
      when (state) {
        android.media.session.PlaybackState.STATE_PLAYING -> PLAYING
        android.media.session.PlaybackState.STATE_PAUSED -> PAUSED
        android.media.session.PlaybackState.STATE_STOPPED -> STOPPED
        android.media.session.PlaybackState.STATE_BUFFERING -> BUFFERING
        android.media.session.PlaybackState.STATE_ERROR -> ERROR
        else -> NONE
      }
  }
}

/**
 * 从 MediaSession 读出来的「当前播放」。
 *
 * 字段来源（2026-10-05 真机实测确认）：
 * - `mediaId`  = `METADATA_KEY_MEDIA_ID`，网易云就是它的歌曲 ID，可直接拿去查歌词
 * - `artwork`  = `ALBUM_ART` / `ART` / `DISPLAY_ICON`，三者实测是同一张 Bitmap（无需解析 Uri）
 * - `liked`    = `USER_RATING`（style=HEART）
 */
data class NowPlaying(
  val packageName: String = "",
  val mediaId: String? = null,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val artwork: Bitmap? = null,
  val durationMs: Long = 0L,
  val status: PlaybackStatus = PlaybackStatus.NONE,
  /** MediaSession 报的位置。实测网易云可能一直报 0，不可靠时由 UI 层自行计时兜底 */
  val positionMs: Long = 0L,
  /** PlaybackState.getLastPositionUpdateTime()，基准是 SystemClock.elapsedRealtime() */
  val positionUpdatedElapsedRealtime: Long = 0L,
  val speed: Float = 1f,
  val liked: Boolean = false,
) {
  val isPlaying: Boolean get() = status.isPlaying

  /** 用于判断「是不是换歌了」的键 */
  val identity: String get() = mediaId ?: listOfNotNull(title, artist).joinToString(" - ")

  /**
   * 真正能拿去查歌词的歌曲 ID。
   *
   * ⚠️ 网易云在不同链路上往 `METADATA_KEY_MEDIA_ID` 里塞的东西**不一样**（真机实测）：
   *
   * | 链路 | `mediaId` 实际值 | 拿去查歌词 |
   * |---|---|---|
   * | 通知使用权 | `411500348` | ✅ |
   * | MediaBrowser | `playlist_id_4866956204_3340114786` | ❌ 接口返回 `code=400` |
   *
   * 后者是 `playlist_id_<歌单ID>_<歌曲ID>`，**最后一段才是歌曲 ID**。
   * 实测：拿 `3340114786` 能查到 2171 字节带翻译的歌词；
   * 拿中间那段歌单 ID 只会得到「暂无歌词」。
   *
   * 所以这里统一归一化：纯数字直接用，否则取最后一段（且必须是纯数字），
   * 都不满足就返回 null（宁可不显示歌词，也不要拿错 ID 去查出一首不相干的歌词）。
   */
  val songId: String?
    get() {
      val raw = mediaId?.trim().orEmpty()
      if (raw.isEmpty()) return null
      if (raw.all { it.isDigit() }) return raw
      return raw.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

  /** 歌手字段里网易云常把专辑名也串进来（实测 `NEEDY GIRL OVERDOSE/KOTOKO/Aiobahn +81`） */
  val artistParts: List<String>
    get() = artist?.split('/', '、', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
