package top.aerohaku.androidapp.playback

import top.aerohaku.androidapp.model.NowPlaying

/**
 * 播放进度估算。
 *
 * 真机实测发现：网易云在 MediaSession 里上报的 `position` 可能一直是 0。
 * 所以策略是「能用就用，不能用就本地累计」——
 * 本地累计在暂停时自然冻结，恢复播放时继续，切歌时清零。
 * 误差只影响歌词高亮，可以接受。
 *
 * 另有一条**优先级最高**的来源：用户刚拖完进度条发出的 seek（[PendingSeek]）。
 * 它必须盖住会话回报之前的那一瞬间，否则进度条会先回弹到旧位置。
 */
class PositionEstimator {

  private var identity: String? = null
  private var value = 0L
  private var lastNow = 0L

  /** 是否在用 MediaSession 上报的进度（用于诊断显示） */
  var usingSessionPosition: Boolean = false
    private set

  fun estimate(nowPlaying: NowPlaying?, now: Long, pendingSeek: PendingSeek? = null): Long {
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

    // 刚发出的 seek 优先：目标位置 + 越过的时间。
    // ‼️ 同时写进 value：万一这个会话压根不推 position（见类注释，网易云有时上报的就是 0），
    // 窗口过期后本地累计会**从这里接着走**，而不是跳回旧位置。
    if (pendingSeek != null) {
      val advanced = if (nowPlaying.isPlaying) {
        ((now - pendingSeek.atElapsedMs).coerceAtLeast(0L) * nowPlaying.speed).toLong()
      } else {
        0L
      }
      value = pendingSeek.positionMs + advanced
      return clampToDuration(value, nowPlaying)
    }

    value = if (usingSessionPosition) {
      val sinceUpdate = (now - nowPlaying.positionUpdatedElapsedRealtime).coerceAtLeast(0L)
      nowPlaying.positionMs + if (nowPlaying.isPlaying) (sinceUpdate * nowPlaying.speed).toLong() else 0L
    } else if (nowPlaying.isPlaying) {
      value + delta
    } else {
      value
    }

    return clampToDuration(value, nowPlaying)
  }

  private fun clampToDuration(value: Long, nowPlaying: NowPlaying): Long =
    if (nowPlaying.durationMs > 0L) value.coerceIn(0L, nowPlaying.durationMs) else value

  fun reset() {
    identity = null
    value = 0L
    lastNow = 0L
    usingSessionPosition = false
  }
}
