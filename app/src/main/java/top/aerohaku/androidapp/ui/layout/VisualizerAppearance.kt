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

/**
 * 背景虚化算法。
 *
 * 两个降采样相关的选项也列在这里，因为它们的**观感差别是真实存在的**：
 * 逐级缩小再放大会留下轻微的块感，而卷积能把它抹平。
 *
 * 全部在**缩小后的缩略图**上做（见 `ui/components/BackgroundBlur.kt`），
 * 所以切换算法不影响绘制路径、也与屏幕分辨率无关。
 */
enum class BlurAlgorithm(val label: String, val note: String) {
  GAUSSIAN(
    "高斯",
    "可分离高斯核，最平滑，代价是半径大时慢一些。",
  ),
  BOX3(
    "均值 ×3",
    "三遍盒式均值：按中心极限定理已经非常接近高斯，而滑动窗口让它快到与半径无关。",
  ),
  BOX1(
    "均值 ×1",
    "只做一遍，最便宜，但会留下一点方形结构。",
  ),
  DOWNSCALE(
    "仅缩放",
    "完全不做卷积，只靠逐级缩小再放大铺满；放大痕迹最明显。",
  ),
}

/**
 * 背景虚化配置。
 *
 * 三个值绑定在一起传：它们必须同时决定同一张缩略图。
 * 分开传容易出现「选了均值、用的却是高斯的半径」这种不一致。
 */
data class BackgroundBlur(
  val enabled: Boolean = true,
  val algorithm: BlurAlgorithm = BlurAlgorithm.GAUSSIAN,
  /** 0..100。0 = 不卷积，只缩小（等同 [BlurAlgorithm.DOWNSCALE]） */
  val amount: Int = VisualizerAppearanceStore.DEFAULT_BLUR_AMOUNT,
)

/** 与「摆哪儿」无关的纯外观参数 */
data class VisualizerAppearance(
  /** 背景暗化强度 0..1 */
  val backgroundDarken: Float = VisualizerAppearanceStore.DEFAULT_DARKEN,
  /** 背景虚化：开关 / 算法 / 程度，见 [BackgroundBlur] */
  val backgroundBlur: BackgroundBlur = BackgroundBlur(),
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

  /** 背景虚化程度 0..100。默认 60：观感上已经是一片柔和过渡，又不会糊成色块 */
  const val DEFAULT_BLUR_AMOUNT = 60
  const val MIN_BLUR_AMOUNT = 0
  const val MAX_BLUR_AMOUNT = 100

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
        backgroundBlur = BackgroundBlur(
          enabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
          // 认不出的值（手动改过配置文件）退回默认，而不是崩掉
          algorithm = runCatching {
            BlurAlgorithm.valueOf(
              prefs.getString(KEY_BLUR_ALGORITHM, null) ?: BlurAlgorithm.GAUSSIAN.name,
            )
          }.getOrDefault(BlurAlgorithm.GAUSSIAN),
          amount = prefs.getInt(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)
            .coerceIn(MIN_BLUR_AMOUNT, MAX_BLUR_AMOUNT),
        ),
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

  fun setBackgroundBlurEnabled(context: Context, enabled: Boolean) {
    _state.value = _state.value.copy(
      backgroundBlur = _state.value.backgroundBlur.copy(enabled = enabled),
    )
    persist(context)
  }

  fun setBackgroundBlurAlgorithm(context: Context, algorithm: BlurAlgorithm) {
    _state.value = _state.value.copy(
      backgroundBlur = _state.value.backgroundBlur.copy(algorithm = algorithm),
    )
    persist(context)
  }

  fun setBackgroundBlurAmount(context: Context, amount: Int) {
    _state.value = _state.value.copy(
      backgroundBlur = _state.value.backgroundBlur.copy(
        amount = amount.coerceIn(MIN_BLUR_AMOUNT, MAX_BLUR_AMOUNT),
      ),
    )
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
      putBoolean(KEY_BLUR_ENABLED, _state.value.backgroundBlur.enabled)
      putString(KEY_BLUR_ALGORITHM, _state.value.backgroundBlur.algorithm.name)
      putInt(KEY_BLUR_AMOUNT, _state.value.backgroundBlur.amount)
      putBoolean(KEY_BORDER, _state.value.albumArtBorder)
      putString(KEY_LYRICS_ALIGN, _state.value.lyricsAlign.name)
      putBoolean(KEY_AUTO_SCALE, _state.value.autoScale)
      putBoolean(KEY_SPECTRUM_SCALE, _state.value.showSpectrumScale)
      putBoolean(KEY_LEVEL_SCALE, _state.value.showLevelScale)
      remove(KEY_SCALES_LEGACY)
    }.apply()
  }

  private const val KEY_DARKEN = "backgroundDarken"
  private const val KEY_BLUR_ENABLED = "backgroundBlurEnabled"
  private const val KEY_BLUR_ALGORITHM = "backgroundBlurAlgorithm"
  private const val KEY_BLUR_AMOUNT = "backgroundBlurAmount"
  private const val KEY_BORDER = "albumArtBorder"
  private const val KEY_LYRICS_ALIGN = "lyricsAlign"
  private const val KEY_AUTO_SCALE = "autoScale"
  private const val KEY_SPECTRUM_SCALE = "showSpectrumScale"
  private const val KEY_LEVEL_SCALE = "showLevelScale"

  /** 已废弃：两个开关早先共用一个键，读完一次就删 */
  private const val KEY_SCALES_LEGACY = "showScales"

  /** 供 UI 显示：暗化强度 → 百分比文本 */
  fun formatDarken(value: Float): String = "${(value * 100).toInt()}%"

  /** 供 UI 显示：模糊程度 → 百分比文本 */
  fun formatBlurAmount(value: Int): String = "$value%"
}
