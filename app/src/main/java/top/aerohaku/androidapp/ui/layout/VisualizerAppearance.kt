package top.aerohaku.androidapp.ui.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 歌词文本的水平对齐方式 */
enum class LyricsAlign(val label: String) {
  START("左对齐"),
  END("右对齐"),
}

/** 与「摆哪儿」无关的纯外观参数 */
data class VisualizerAppearance(
  /** 背景暗化强度 0..1 */
  val backgroundDarken: Float = VisualizerAppearanceStore.DEFAULT_DARKEN,
  /** 专辑封面是否画白框（宽度固定为 1 **物理像素**的发丝线） */
  val albumArtBorder: Boolean = true,
  /** 歌词对齐方式：与位置一起由版式预设决定（见 [LayoutPreset]） */
  val lyricsAlign: LyricsAlign = LyricsAlign.START,
  /**
   * 小屏自动缩放。见 [LayoutScale]：把「按开发机尺寸调的绝对 dp 版式」
   * 等比缩进当前设备的可用区域，否则小屏上各部件会挤到一起。
   *
   * 为 true 时开发机上的观感逐像素不变（缩放倍率恰好为 1）。
   */
  val autoScale: Boolean = true,
  /**
   * 频谱图是否显示标尺与数值（**相对满度的百分比**）。
   *
   * 默认**关**：版式稿要求「无边框、无背景」，标尺本身也是线条，
   * 开着会破坏那种干净的观感；但需要读数时又确实有用，所以做成可选。
   */
  val showSpectrumScale: Boolean = false,
  /**
   * 电平表是否显示 dB 刻度与数值。
   *
   * 与 [showSpectrumScale] **分开配置**：两者一个标相对百分比、一个标真 dB，
   * 看着同一个界面却是两套量纲；用途也不同 —— 频谱看相对起伏，电平看有没有过载。
   * 绑在一个开关上没道理。
   */
  val showLevelScale: Boolean = false,
)

/**
 * 外观参数的持久化存储。与 [VisualizerLayoutStore] 同一套做法：
 * `SharedPreferences` 存盘 + `StateFlow` 进程内广播。
 */
object VisualizerAppearanceStore {

  private const val PREF_NAME = "visualizer_appearance"

  /** 默认比最初写死的 0.30 更深 —— 白色图形压在浅色封面上对比度不够 */
  const val DEFAULT_DARKEN = 0.45f
  const val MIN_DARKEN = 0f
  const val MAX_DARKEN = 0.85f

  private val _state = MutableStateFlow(VisualizerAppearance())
  val state: StateFlow<VisualizerAppearance> = _state.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
      _state.value = VisualizerAppearance(
        backgroundDarken = prefs
          .getFloat(KEY_DARKEN, DEFAULT_DARKEN)
          .coerceIn(MIN_DARKEN, MAX_DARKEN),
        albumArtBorder = prefs.getBoolean(KEY_BORDER, true),
        lyricsAlign = runCatching {
          LyricsAlign.valueOf(prefs.getString(KEY_LYRICS_ALIGN, null) ?: LyricsAlign.START.name)
        }.getOrDefault(LyricsAlign.START),
        autoScale = prefs.getBoolean(KEY_AUTO_SCALE, true),
        // 这两个开关早先是共用同一个键的，老装机上没有新键时沿用旧值
        showSpectrumScale = prefs.getBoolean(
          KEY_SPECTRUM_SCALE,
          prefs.getBoolean(KEY_SCALES_LEGACY, false),
        ),
        showLevelScale = prefs.getBoolean(
          KEY_LEVEL_SCALE,
          prefs.getBoolean(KEY_SCALES_LEGACY, false),
        ),
      )
      loaded = true
    }
  }

  fun setBackgroundDarken(context: Context, value: Float) {
    _state.value = _state.value.copy(backgroundDarken = value.coerceIn(MIN_DARKEN, MAX_DARKEN))
    persist(context)
  }

  fun setAlbumArtBorder(context: Context, enabled: Boolean) {
    _state.value = _state.value.copy(albumArtBorder = enabled)
    persist(context)
  }

  fun setLyricsAlign(context: Context, align: LyricsAlign) {
    _state.value = _state.value.copy(lyricsAlign = align)
    persist(context)
  }

  fun setAutoScale(context: Context, enabled: Boolean) {
    _state.value = _state.value.copy(autoScale = enabled)
    persist(context)
  }

  fun setShowSpectrumScale(context: Context, enabled: Boolean) {
    _state.value = _state.value.copy(showSpectrumScale = enabled)
    persist(context)
  }

  fun setShowLevelScale(context: Context, enabled: Boolean) {
    _state.value = _state.value.copy(showLevelScale = enabled)
    persist(context)
  }

  fun reset(context: Context) {
    _state.value = VisualizerAppearance()
    persist(context)
  }

  private fun persist(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    prefs.edit().apply {
      putFloat(KEY_DARKEN, _state.value.backgroundDarken)
      putBoolean(KEY_BORDER, _state.value.albumArtBorder)
      putString(KEY_LYRICS_ALIGN, _state.value.lyricsAlign.name)
      putBoolean(KEY_AUTO_SCALE, _state.value.autoScale)
      putBoolean(KEY_SPECTRUM_SCALE, _state.value.showSpectrumScale)
      putBoolean(KEY_LEVEL_SCALE, _state.value.showLevelScale)
      remove(KEY_SCALES_LEGACY)
    }.apply()
  }

  private const val KEY_DARKEN = "backgroundDarken"
  private const val KEY_BORDER = "albumArtBorder"
  private const val KEY_LYRICS_ALIGN = "lyricsAlign"
  private const val KEY_AUTO_SCALE = "autoScale"
  private const val KEY_SPECTRUM_SCALE = "showSpectrumScale"
  private const val KEY_LEVEL_SCALE = "showLevelScale"

  /** 已废弃：两个开关早先共用一个键，读完一次就删 */
  private const val KEY_SCALES_LEGACY = "showScales"

  /** 供 UI 显示：暗化强度 → 百分比文本 */
  fun formatDarken(value: Float): String = "${(value * 100).toInt()}%"
}
