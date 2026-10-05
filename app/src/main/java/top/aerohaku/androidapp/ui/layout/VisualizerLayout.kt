package top.aerohaku.androidapp.ui.layout

import android.content.Context
import android.content.SharedPreferences
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
 *
 * ## 内置预设 vs 用户预设
 *
 * - 内置的就是 [LayoutPreset] 那两个枚举，**不可删除**；
 * - 用户预设是 [UserPreset]，由「保存当前布局」按钮生成，可增可删。
 *
 * 两者用同一个 [activeId] 标识：内置用枚举名，用户预设用 [UserPreset.id]。
 * 这样「当前选中的是哪一个」只有一个状态，UI 不用同时跟踪两个来源。
 *
 * ## 用户预设怎么存的
 *
 * 没有做 JSON 序列化：SharedPreferences 本身就是个 key-value，
 * 直接按 `__up_<下标>_<部件名>.<字段>` 平铺存下来即可 ——
 * 名称与备注里带任何字符（换行、引号、竖线）都不用转义，读回来也一定和写进去的一致。
 * 代价是键比较多（每个预设约 60 个），但 SharedPreferences 本来就会把全部键读进内存，
 * 这个量级完全无感。
 */
object VisualizerLayoutStore {

  private const val PREF_NAME = "visualizer_layout"

  /** 当前激活的预设：内置的存枚举名，用户预设存 [UserPreset.id] */
  private const val KEY_ACTIVE = "__preset"

  /** 用户预设的个数；每个预设的字段由 [userKey] 拼前缀逐个存 */
  private const val KEY_USER_COUNT = "__user_preset_count"

  private val _activeId = MutableStateFlow(LayoutPreset.default.name)
  val activeId: StateFlow<String> = _activeId.asStateFlow()

  private val _userPresets = MutableStateFlow<List<UserPreset>>(emptyList())
  val userPresets: StateFlow<List<UserPreset>> = _userPresets.asStateFlow()

  private val _configs = MutableStateFlow(LayoutPreset.default.configs)
  val configs: StateFlow<Map<VisualizerWidget, WidgetConfig>> = _configs.asStateFlow()

  @Volatile private var loaded = false

  // ------------------------------------------------------------------ 查询

  /** 某个预设 id 对应的**原始**配置；找不到（比如预设已被删掉）返回 null */
  private fun baseline(id: String): Map<VisualizerWidget, WidgetConfig>? =
    LayoutPreset.entries.firstOrNull { it.name == id }?.configs
      ?: _userPresets.value.firstOrNull { it.id == id }?.configs

  /** 当前激活预设的显示名；找不到返回 null（调用方自行兜底） */
  fun activeName(): String? =
    LayoutPreset.entries.firstOrNull { it.name == _activeId.value }?.label
      ?: _userPresets.value.firstOrNull { it.id == _activeId.value }?.name

  /**
   * 当前布局是否**仍然等于**所选预设。
   * 手动调过任何一个部件之后就会变成 false —— 设置页据此提示「已偏离预设」，
   * 比悄悄记住一个已经名不副实的预设名诚实。
   */
  fun isPresetIntact(): Boolean = _configs.value == baseline(_activeId.value)

  /** 下一个可用的自动名称，供新建对话框当占位符 */
  fun nextUserPresetName(): String =
    "$USER_PRESET_PREFIX - " + nextUserPresetIndex(_userPresets.value.map { it.name })

  /** 默认备注（= 当前时间），供新建对话框当占位符 */
  fun defaultUserPresetNote(): String = defaultPresetNote()

  // ------------------------------------------------------------------ 读

  /** 幂等：重复调用只有第一次生效 */
  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

      _userPresets.value = readUserPresets(prefs)

      // 激活的预设：内置名与用户 id 都认；两个都找不到（比如预设被删了）就退回默认
      val stored = prefs.getString(KEY_ACTIVE, null)
      _activeId.value = when {
        stored != null && LayoutPreset.entries.any { it.name == stored } -> stored
        stored != null && _userPresets.value.any { it.id == stored } -> stored
        else -> LayoutPreset.default.name
      }

      // 以「激活预设的默认值」为基准，再叠加用户改过的字段。
      // 这样以后我调整某个预设的默认值，没被用户动过的部件会跟着更新。
      val fallback = baseline(_activeId.value) ?: LayoutPreset.default.configs
      _configs.value = fallback.mapValues { (widget, default) ->
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

      loaded = true
    }
  }

  // ------------------------------------------------------------------ 写

  /**
   * 整体切到某个**内置**预设。
   *
   * 顺带把歌词对齐也切了 —— 「歌词在右上」配左对齐会很难看，
   * 两者是同一个版式的两个组成部分，必须一起改。
   */
  fun applyPreset(context: Context, preset: LayoutPreset) {
    _activeId.value = preset.name
    _configs.value = preset.configs
    VisualizerAppearanceStore.setLyricsAlign(context, preset.lyricsAlign)
    persist(context)
  }

  /** 整体切到某个**用户**预设 */
  fun applyUserPreset(context: Context, id: String) {
    val preset = _userPresets.value.firstOrNull { it.id == id } ?: return
    _activeId.value = preset.id
    _configs.value = preset.configs
    VisualizerAppearanceStore.setLyricsAlign(context, preset.lyricsAlign)
    persist(context)
  }

  /**
   * 把**当前布局**存成一个新的用户预设，并立即切到它。
   *
   * [name] / [note] 传空串就用默认值（自动编号 / 当前时间）。
   * 歌词对齐取的是**当前**值而不是激活预设的 —— 用户手动改过对齐之后，
   * 存下来的应该是他正看着的那一套。
   */
  fun saveCurrentAsUserPreset(context: Context, name: String, note: String): UserPreset {
    VisualizerAppearanceStore.ensureLoaded(context)
    val preset = UserPreset(
      id = "user-" + System.currentTimeMillis(),
      name = name.ifBlank { nextUserPresetName() },
      note = note.ifBlank { defaultUserPresetNote() },
      lyricsAlign = VisualizerAppearanceStore.state.value.lyricsAlign,
      configs = _configs.value,
    )
    _userPresets.value = _userPresets.value + preset
    _activeId.value = preset.id
    persist(context)
    return preset
  }

  /**
   * 删除一个用户预设。
   * 删掉的正好是当前激活的那个时，退回默认预设（否则 activeId 会指向一个不存在的 id）。
   */
  fun deleteUserPreset(context: Context, id: String) {
    if (_userPresets.value.none { it.id == id }) return
    _userPresets.value = _userPresets.value.filterNot { it.id == id }
    if (_activeId.value == id) {
      applyPreset(context, LayoutPreset.default)
    } else {
      persist(context)
    }
  }

  fun update(context: Context, widget: VisualizerWidget, config: WidgetConfig) {
    _configs.value = _configs.value + (widget to config)
    persist(context)
  }

  /** 回到**当前预设**的初始状态（而不是回到某个固定默认值） */
  fun resetAll(context: Context) {
    when (val builtIn = LayoutPreset.entries.firstOrNull { it.name == _activeId.value }) {
      null -> applyUserPreset(context, _activeId.value)
      else -> applyPreset(context, builtIn)
    }
  }

  fun resetOne(context: Context, widget: VisualizerWidget) {
    baseline(_activeId.value)?.get(widget)?.let { update(context, widget, it) }
  }

  // ------------------------------------------------------------------ 持久化

  private fun persist(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val presets = _userPresets.value
    val oldCount = prefs.getInt(KEY_USER_COUNT, 0)

    prefs.edit().apply {
      putString(KEY_ACTIVE, _activeId.value)

      _configs.value.forEach { (widget, config) ->
        putString(key(widget, "anchor"), config.anchor.name)
        putFloat(key(widget, "x"), config.offsetX.value)
        putFloat(key(widget, "y"), config.offsetY.value)
        putFloat(key(widget, "w"), config.width?.value ?: FILL_SENTINEL)
        putFloat(key(widget, "h"), config.height?.value ?: FILL_SENTINEL)
        putFloat(key(widget, "inset"), config.trailingInset.value)
        putBoolean(key(widget, "on"), config.enabled)
      }

      // 用户预设：先把可能与新列表冲突的旧下标全部清掉，再整体写入。
      // 列表变短时若不清，多出来的旧下标会变成永远读不到的垃圾。
      for (index in 0 until maxOf(oldCount, presets.size)) removeUserPreset(index)
      putInt(KEY_USER_COUNT, presets.size)
      presets.forEachIndexed { index, preset ->
        putString(userKey(index, "id"), preset.id)
        putString(userKey(index, "name"), preset.name)
        putString(userKey(index, "note"), preset.note)
        putString(userKey(index, "align"), preset.lyricsAlign.name)
        preset.configs.forEach { (widget, config) ->
          putString(userKey(index, "${widget.name}.anchor"), config.anchor.name)
          putFloat(userKey(index, "${widget.name}.x"), config.offsetX.value)
          putFloat(userKey(index, "${widget.name}.y"), config.offsetY.value)
          putFloat(userKey(index, "${widget.name}.w"), config.width?.value ?: FILL_SENTINEL)
          putFloat(userKey(index, "${widget.name}.h"), config.height?.value ?: FILL_SENTINEL)
          putFloat(userKey(index, "${widget.name}.inset"), config.trailingInset.value)
          putBoolean(userKey(index, "${widget.name}.on"), config.enabled)
        }
      }
    }.apply()
  }

  private fun readUserPresets(prefs: SharedPreferences): List<UserPreset> {
    val count = prefs.getInt(KEY_USER_COUNT, 0)
    return buildList {
      for (index in 0 until count) {
        val id = prefs.getString(userKey(index, "id"), null) ?: continue
        add(
          UserPreset(
            id = id,
            name = prefs.getString(userKey(index, "name"), null) ?: id,
            note = prefs.getString(userKey(index, "note"), null).orEmpty(),
            lyricsAlign = runCatching {
              LyricsAlign.valueOf(
                prefs.getString(userKey(index, "align"), null) ?: LyricsAlign.START.name,
              )
            }.getOrDefault(LyricsAlign.START),
            configs = VisualizerWidget.entries.associateWith { widget ->
              val prefix = "${widget.name}."
              WidgetConfig(
                anchor = runCatching {
                  AnchorPoint.valueOf(
                    prefs.getString(userKey(index, prefix + "anchor"), null)
                      ?: AnchorPoint.TOP_LEFT.name,
                  )
                }.getOrDefault(AnchorPoint.TOP_LEFT),
                offsetX = prefs.getFloat(userKey(index, prefix + "x"), 0f).dp,
                offsetY = prefs.getFloat(userKey(index, prefix + "y"), 0f).dp,
                width = prefs.getFloat(userKey(index, prefix + "w"), FILL_SENTINEL)
                  .let { if (it == FILL_SENTINEL) null else it.dp },
                height = prefs.getFloat(userKey(index, prefix + "h"), FILL_SENTINEL)
                  .let { if (it == FILL_SENTINEL) null else it.dp },
                trailingInset = prefs.getFloat(userKey(index, prefix + "inset"), 0f).dp,
                enabled = prefs.getBoolean(userKey(index, prefix + "on"), true),
              )
            },
          ),
        )
      }
    }
  }

  /** 把一个下标下所有可能的键都删掉（字段清单见 [CONFIG_FIELDS]） */
  private fun SharedPreferences.Editor.removeUserPreset(index: Int) {
    remove(userKey(index, "id"))
    remove(userKey(index, "name"))
    remove(userKey(index, "note"))
    remove(userKey(index, "align"))
    VisualizerWidget.entries.forEach { widget ->
      CONFIG_FIELDS.forEach { field -> remove(userKey(index, "${widget.name}.$field")) }
    }
  }

  private fun key(widget: VisualizerWidget, field: String) = "${widget.name}.$field"

  private fun userKey(index: Int, field: String) = "__up_${index}_$field"

  /** 用一个不可能自然出现的宽度值表示「撑满」 */
  private const val FILL_SENTINEL = -1f

  private val CONFIG_FIELDS = listOf("anchor", "x", "y", "w", "h", "inset", "on")
}
