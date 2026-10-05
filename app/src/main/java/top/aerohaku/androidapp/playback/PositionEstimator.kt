package top.aerohaku.androidapp.playback

import top.aerohaku.androidapp.model.NowPlaying

/**
 * 播放进度估算。
 *
 * 真机实测发现：网易云在 MediaSession 里上报的 `position` 可能一直是 0。
 * 所以策略是「能用就用，不能用就本地累计」——
 * 本地累计在暂停时自然冻结，恢复播放时继续，切歌时清零。
 * 误差只影响歌词高亮，可以接受。
 */
class PositionEstimator {

  private var identity: String? = null
  private var value = 0L
  private var lastNow = 0L

  /** 是否在用 MediaSession 上报的进度（用于诊断显示） */
  var usingSessionPosition: Boolean = false
    private set

  fun estimate(nowPlaying: NowPlaying?, now: Long): Long {
    if (nowPlaying == null) {
      reset()
      return 0L
    }

    if (nowPlaying.identity != identity) {
      identity = nowPlaying.identity
      value = 0L
      lastNow = now
    }
    val delta = (now - lastNow).coerceAtLeast(0L)
    lastNow = now

    usingSessionPosition = nowPlaying.positionMs > 0L

    value = if (usingSessionPosition) {
      val sinceUpdate = (now - nowPlaying.positionUpdatedElapsedRealtime).coerceAtLeast(0L)
      nowPlaying.positionMs + if (nowPlaying.isPlaying) (sinceUpdate * nowPlaying.speed).toLong() else 0L
    } else if (nowPlaying.isPlaying) {
      value + delta
    } else {
      value
    }

    if (nowPlaying.durationMs > 0L) {
      value = value.coerceIn(0L, nowPlaying.durationMs)
    }
    return value
  }

  fun reset() {
    identity = null
    value = 0L
    lastNow = 0L
    usingSessionPosition = false
  }
}
