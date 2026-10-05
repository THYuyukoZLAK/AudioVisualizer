package top.aerohaku.androidapp.ui.visualizer

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.capture.CaptureController
import top.aerohaku.androidapp.lyrics.LrcParser
import top.aerohaku.androidapp.lyrics.LyricsRepository
import top.aerohaku.androidapp.lyrics.LyricsState
import top.aerohaku.androidapp.playback.NowPlayingState
import top.aerohaku.androidapp.playback.PositionEstimator

data class LyricUiState(
  val positionMs: Long = 0L,
  val currentIndex: Int = -1,
  val usingSessionPosition: Boolean = false,
)

class VisualizerViewModel : ViewModel() {

  val nowPlaying = NowPlayingState.nowPlaying
  val listenerConnected = NowPlayingState.listenerConnected

  /** 默认链路（MediaBrowser）是否已连上网易云。连上就不需要任何敏感授权 */
  val browserConnected = NowPlayingState.browserConnected
  val playbackDiagnostics = NowPlayingState.diagnostics

  val captureStatus = CaptureController.status
  val audioFrame = CaptureController.audioFrame
  val autoStartStop = CaptureController.autoStartStop

  val lyrics = LyricsRepository.state
  val lyricsDiagnostics = LyricsRepository.diagnostics

  /** 每 250ms 跳一次的时钟，只在 UI 订阅时运行 */
  private val clock: Flow<Long> = flow {
    while (true) {
      emit(SystemClock.elapsedRealtime())
      delay(CLOCK_INTERVAL_MS)
    }
  }

  private val positionEstimator = PositionEstimator()

  val lyricUi: StateFlow<LyricUiState> =
    combine(NowPlayingState.nowPlaying, LyricsRepository.state, clock) { nowPlaying, lyricsState, now ->
      val position = positionEstimator.estimate(nowPlaying, now)
      val lines = (lyricsState as? LyricsState.Ready)?.lines.orEmpty()
      LyricUiState(
        positionMs = position,
        currentIndex = LrcParser.indexAt(lines, position),
        usingSessionPosition = positionEstimator.usingSessionPosition,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LyricUiState())

  init {
    // 换歌就换歌词（⚠️ 用 songId 而不是 mediaId —— 两条链路给的 mediaId 格式不一样，
    // MediaBrowser 给的是 playlist_id_<歌单>_<歌曲>，直接查歌词会被拒）
    viewModelScope.launch {
      NowPlayingState.nowPlaying
        .map { it?.songId }
        .distinctUntilChanged()
        .collect { LyricsRepository.load(it) }
    }
  }

  fun reloadLyrics() {
    LyricsRepository.reload(NowPlayingState.nowPlaying.value?.songId)
  }

  private companion object {
    const val CLOCK_INTERVAL_MS = 250L
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
