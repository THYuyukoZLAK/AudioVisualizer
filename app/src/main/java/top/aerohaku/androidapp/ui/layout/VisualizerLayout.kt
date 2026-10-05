package top.aerohaku.androidapp.ui.layout

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 可视化页面上可摆放的部件 */
enum class VisualizerWidget(val label: String) {
  ALBUM_ART("专辑封面"),
  TRACK_INFO("曲目信息"),
  LYRICS("歌词"),
  SPECTRUM("频谱图"),
  LEVEL_METERS("左右声道电平"),
  WAVEFORM("波形图"),
  PROGRESS("进度条"),
}

/**
 * 单个部件的配置。
 *
 * - [width] / [height] 为 `null` 表示**撑满容器**
 * - [trailingInset] 只在 [width] 为 `null` 时生效：把内容右边缘往回缩，
 *   否则「整行 + 左偏移 64dp」的框会直接伸到屏幕外，省略号也落在屏幕外看不见
 */
data class WidgetConfig(
  val anchor: AnchorPoint = AnchorPoint.TOP_LEFT,
  val offsetX: Dp = 0.dp,
  val offsetY: Dp = 0.dp,
  val width: Dp? = null,
  val height: Dp? = null,
  val trailingInset: Dp = 0.dp,
  val enabled: Boolean = true,
)

/** 部件的几何部分（不含 [WidgetConfig.enabled]），便于 `AnchoredLayout` 直接取用 */
fun WidgetConfig.placement(): AnchorPlacement = AnchorPlacement(anchor, offsetX, offsetY)

/**
 * 部件布局的持久化存储。
 *
 * 用 `SharedPreferences` 而不是 DataStore：本项目的既定原则是**零第三方依赖**，
 * 而这里的数据结构极其简单（7 个部件 × 7 个标量 + 一个预设名）。
 * 进程内用 [StateFlow] 广播，页面组合即可实时响应设置页的修改。
 */
object VisualizerLayoutStore {

  private const val PREF_NAME = "visualizer_layout"
  private const val KEY_PRESET = "__preset"

  private val _preset = MutableStateFlow(LayoutPreset.default)
  val preset: StateFlow<LayoutPreset> = _preset.asStateFlow()

  private val _configs = MutableStateFlow(LayoutPreset.default.configs)
  val configs: StateFlow<Map<VisualizerWidget, WidgetConfig>> = _configs.asStateFlow()

  @Volatile private var loaded = false

  /**
   * 当前布局是否**仍然等于**所选预设。
   * 手动调过任何一个部件之后就会变成 false —— 设置页据此提示「已偏离预设」，
   * 比悄悄记住一个已经名不副实的预设名诚实。
   */
  fun isPresetIntact(): Boolean = _configs.value == _preset.value.configs

  /** 幂等：重复调用只有第一次生效 */
  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

      val restoredPreset = runCatching {
        LayoutPreset.valueOf(prefs.getString(KEY_PRESET, null) ?: LayoutPreset.default.name)
      }.getOrDefault(LayoutPreset.default)

      // 以「预设 + 该预设的默认值」为基准，再叠加用户改过的字段。
      // 这样以后我调整某个预设的默认值，没被用户动过的部件会跟着更新。
      val fallback = restoredPreset.configs
      val restored = fallback.mapValues { (widget, default) ->
        WidgetConfig(
          anchor = runCatching {
            AnchorPoint.valueOf(prefs.getString(key(widget, "anchor"), null) ?: default.anchor.name)
          }.getOrDefault(default.anchor),
          offsetX = prefs.getFloat(key(widget, "x"), default.offsetX.value).dp,
          offsetY = prefs.getFloat(key(widget, "y"), default.offsetY.value).dp,
          width = prefs.getFloat(key(widget, "w"), default.width?.value ?: FILL_SENTINEL)
            .let { if (it == FILL_SENTINEL) null else it.dp },
          height = prefs.getFloat(key(widget, "h"), default.height?.value ?: FILL_SENTINEL)
            .let { if (it == FILL_SENTINEL) null else it.dp },
          trailingInset = prefs.getFloat(key(widget, "inset"), default.trailingInset.value).dp,
          enabled = prefs.getBoolean(key(widget, "on"), default.enabled),
        )
      }

      _preset.value = restoredPreset
      _configs.value = restored
      loaded = true
    }
  }

  /**
   * 整体切到某个预设。
   *
   * 顺带把歌词对齐也切了 —— 「歌词在右上」配左对齐会很难看，
   * 两者是同一个版式的两个组成部分，必须一起改。
   */
  fun applyPreset(context: Context, preset: LayoutPreset) {
    _preset.value = preset
    _configs.value = preset.configs
    VisualizerAppearanceStore.setLyricsAlign(context, preset.lyricsAlign)
    persist(context)
  }

  fun update(context: Context, widget: VisualizerWidget, config: WidgetConfig) {
    _configs.value = _configs.value + (widget to config)
    persist(context)
  }

  /** 回到**当前预设**的初始状态（而不是回到某个固定默认值） */
  fun resetAll(context: Context) {
    applyPreset(context, _preset.value)
  }

  fun resetOne(context: Context, widget: VisualizerWidget) {
    _preset.value.configs[widget]?.let { update(context, widget, it) }
  }

  private fun persist(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    prefs.edit().apply {
      putString(KEY_PRESET, _preset.value.name)
      _configs.value.forEach { (widget, config) ->
        putString(key(widget, "anchor"), config.anchor.name)
        putFloat(key(widget, "x"), config.offsetX.value)
        putFloat(key(widget, "y"), config.offsetY.value)
        putFloat(key(widget, "w"), config.width?.value ?: FILL_SENTINEL)
        putFloat(key(widget, "h"), config.height?.value ?: FILL_SENTINEL)
        putFloat(key(widget, "inset"), config.trailingInset.value)
        putBoolean(key(widget, "on"), config.enabled)
      }
    }.apply()
  }

  private fun key(widget: VisualizerWidget, field: String) = "${widget.name}.$field"

  /** 用一个不可能自然出现的宽度值表示「撑满」 */
  private const val FILL_SENTINEL = -1f
}
