package top.aerohaku.androidapp.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import top.aerohaku.androidapp.model.NowPlaying
import top.aerohaku.androidapp.model.PlaybackStatus

/**
 * 播放进度估算。
 *
 * 重点是这次新加的**「刚发出的 seek」**：`seekTo()` 是异步的，会话把新位置推回来之前，
 * 进度条会先回弹到旧位置。这几条测试守住「seek 立刻生效」这个观感。
 *
 * [PositionEstimator] 不碰任何 Android 类型，所以能直接跑在 JVM 上。
 */
class PositionEstimatorTest {

  private fun playing(
    positionMs: Long = 0L,
    updatedAt: Long = 0L,
    durationMs: Long = 180_000L,
    status: PlaybackStatus = PlaybackStatus.PLAYING,
    speed: Float = 1f,
  ) = NowPlaying(
    mediaId = "1",
    durationMs = durationMs,
    positionMs = positionMs,
    positionUpdatedElapsedRealtime = updatedAt,
    status = status,
    speed = speed,
  )

  @Test
  fun `会话给了位置就按它加上流逝的时间`() {
    val estimator = PositionEstimator()
    // 10s 处上报（基准时刻 1000），2s 之后来问
    val value = estimator.estimate(playing(positionMs = 10_000, updatedAt = 1_000), now = 3_000)
    assertEquals(12_000L, value)
  }

  @Test
  fun `刚发出的 seek 盖住会话里的旧位置`() {
    val estimator = PositionEstimator()
    // 会话还停在 10s 处（旧位置），但用户刚刚拖到了 60s
    val value = estimator.estimate(
      nowPlaying = playing(positionMs = 10_000, updatedAt = 1_000),
      now = 3_000,
      pendingSeek = PendingSeek(positionMs = 60_000, atElapsedMs = 2_900),
    )
    // 目标位置 + 这 100ms 的播放进度，而不是回弹到 10s
    assertEquals(60_100L, value)
  }

  @Test
  fun `暂停时 seek 之后不自己往前走`() {
    val estimator = PositionEstimator()
    val value = estimator.estimate(
      nowPlaying = playing(status = PlaybackStatus.PAUSED),
      now = 5_000,
      pendingSeek = PendingSeek(positionMs = 60_000, atElapsedMs = 1_000),
    )
    assertEquals(60_000L, value)
  }

  @Test
  fun `会话干脆不报位置时，本地累计从 seek 目标接着走`() {
    val estimator = PositionEstimator()
    // 网易云有时上报的 position 一直是 0（见 PositionEstimator 的类注释），
    // 这时走的是本地累计那条分支 —— seek 之后必须从目标位置继续，而不是跳回旧值
    val atSeek = estimator.estimate(
      nowPlaying = playing(positionMs = 0L),
      now = 1_000,
      pendingSeek = PendingSeek(positionMs = 60_000, atElapsedMs = 1_000),
    )
    assertEquals(60_000L, atSeek)

    // 「本地预期」窗口过期（pendingSeek 变回 null），本地累计应当接着走
    val later = estimator.estimate(playing(positionMs = 0L), now = 2_000)
    assertEquals(61_000L, later)
  }

  @Test
  fun `seek 目标超出时长会被夹在时长内`() {
    val estimator = PositionEstimator()
    val value = estimator.estimate(
      nowPlaying = playing(durationMs = 180_000L),
      now = 1_000,
      pendingSeek = PendingSeek(positionMs = 999_999, atElapsedMs = 1_000),
    )
    assertEquals(180_000L, value)
  }

  @Test
  fun `没有曲目时归零`() {
    val estimator = PositionEstimator()
    estimator.estimate(playing(positionMs = 30_000, updatedAt = 0L), now = 1_000)
    assertEquals(0L, estimator.estimate(null, now = 2_000))
  }
}
