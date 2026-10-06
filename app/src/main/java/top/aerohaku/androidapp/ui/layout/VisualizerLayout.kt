package top.aerohaku.androidapp.ui.layout

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 可视化页面上可摆放的部件 */
enum class VisualizerWidget(val label: String) {
  ALBUM_ART("专辑封面"),
  TRACK_INFO("曲目信息"),
  LYRICS("歌词"),
  SPECTRUM("频谱图"),
  LEVEL_METERS("左右声道电平"),
  WAVEFORM("波形图"),
  PROGRESS("进度条"),
  ;

  /** 是否是有文字的部件 —— 只有这些部件显示「字号」设置项 */
  val isText: Boolean
    get() = this == TRACK_INFO || this == LYRICS
}

/** 部件默认填充色：纯白，与最初的版式稿一致 */
const val DEFAULT_WIDGET_COLOR: Int = 0xFFFFFFFF.toInt()

/**
 * 单个部件的配置。
 *
 * ## 几何
 *
 * - [width] / [height] 为 `null` 表示**撑满容器**
 * - [trailingInset] 只在 [width] 为 `null` 时生效：把内容右边缘往回缩，
 *   否则「整行 + 左偏移 64dp」的框会直接伸到屏幕外，省略号也落在屏幕外看不见
 *
 * ## 外观
 *
 * - [fontScale] 只对文字部件（见 [VisualizerWidget.isText]）有意义
 * - [color] 是**填充色**，会替掉组件里原本写死的 `VizStyle.Fill`；
 *   文字与图形共用同一个字段，因为它们本来就是同一套「白色图形」语言
 * - [alpha] 作用于整个部件（在 `SizedWidget` 那层统一套），
 *   所以它和组件内部那些「半透明白」（刻度线、翻译行等）是相乘关系
 * - [lockAspect] 只对专辑封面有意义：锁定 1:1，避免封面被拉扁
 * - [gain] 只对频谱图有意义：**显示增益倍率**，绘制时 `柱高 = 原始幅度 × gain`。
 *   它与「部件高度」是两件事 —— 高度负责几何缩放，增益负责把过饱和的柱子拉回可视范围。
 *   以前增益写死在 DSP 里且被裁到 1.0，调高部件只是在拉高一条平顶，
 *   所以手感像在调「截断高度」而不是缩放。
 */
data class WidgetConfig(
  // ------------------------------------------------------------------ 几何
  val anchor: AnchorPoint = AnchorPoint.TOP_LEFT,
  val offsetX: Dp = 0.dp,
  val offsetY: Dp = 0.dp,
  val width: Dp? = null,
  val height: Dp? = null,
  val trailingInset: Dp = 0.dp,
  val enabled: Boolean = true,
  // ------------------------------------------------------------------ 外观
  val fontScale: Float = DEFAULT_FONT_SCALE,
  val color: Int = DEFAULT_WIDGET_COLOR,
  val alpha: Float = DEFAULT_ALPHA,
  val lockAspect: Boolean = false,
  val gain: Float = DEFAULT_GAIN,
)

/** 部件的几何部分（不含 [WidgetConfig.enabled]），便于 `AnchoredLayout` 直接取用 */
fun WidgetConfig.placement(): AnchorPlacement = AnchorPlacement(anchor, offsetX, offsetY)

const val MIN_FONT_SCALE = 0.5f
const val MAX_FONT_SCALE = 3f
const val DEFAULT_FONT_SCALE = 1f

const val MIN_ALPHA = 0.05f
const val MAX_ALPHA = 1f
const val DEFAULT_ALPHA = 1f

/**
 * 频谱显示增益的取值区间。
 *
 * 上界给到 4 是留出「本来很安静、想让柱子显眼」的余地；
 * 下界 0.1 则足以把压得很重的音乐从满屏饱和拉回可视范围。
 */
const val MIN_GAIN = 0.1f
const val MAX_GAIN = 4f
const val DEFAULT_GAIN = 1f

/**
 * 部件布局的持久化存储。
 *
 * 用 `SharedPreferences` 而不是 DataStore：本项目的既定原则是**零第三方依赖**，
 * 而这里的数据结构极其简单（7 个部件 × 12 个标量 + 若干预设）。
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
 * ## 用户预设与布局文本怎么存的
 *
 * 没有做 JSON 序列化：SharedPreferences 本身就是个 key-value，
 * 直接按 `__up_<下标>_<部件名>.<字段>` 平铺存下来即可 ——
 * 名称与备注里带任何字符（换行、引号、竖线）都不用转义，读回来也一定和写进去的一致。
 * 代价是键比较多（每个预设约 80 个），但 SharedPreferences 本来就会把全部键读进内存，
 * 这个量级完全无感。
 *
 * 导出给别的用户时同理：用的是逐行 `key=value` 的纯文本（见 [exportLayout]），
 * 人可读、可 diff，出问题一眼能看出是哪一行。
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
   * 手动调过任何一个部件（含颜色、字号这些外观项）之后就会变成 false ——
   * 设置页据此提示「已偏离预设」，比悄悄记住一个已经名不副实的预设名诚实。
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
        readConfig(prefs, prefix = widget.name, fallback = default)
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

  // ------------------------------------------------------------------ 导出 / 导入

  /** 导出当前布局为纯文本；格式与编解码见文件底部的 [formatLayoutText] */
  fun exportLayout(context: Context): String {
    VisualizerAppearanceStore.ensureLoaded(context)
    return formatLayoutText(VisualizerAppearanceStore.state.value.lyricsAlign, _configs.value)
  }

  /**
   * 从文本导入一套布局，**存成一个新的用户预设**。
   *
   * 刻意不动当前布局、也不切过去 —— 别人发来的布局是「先收下」，
   * 不是「立刻换掉我正在看的那一套」。想用的时候去上面的列表里点一下。
   *
   * 文本的校验与解析见 [parseLayoutText]。
   *
   * @param name 新预设的名字；留空则自动编号
   */
  fun importLayout(context: Context, text: String, name: String = ""): ImportOutcome {
    val parsed = when (val result = parseLayoutText(text)) {
      is LayoutParseResult.Failed -> return ImportOutcome.Failed(result.message)
      is LayoutParseResult.Ok -> result
    }

    VisualizerAppearanceStore.ensureLoaded(context)
    val preset = UserPreset(
      id = "user-" + System.currentTimeMillis(),
      name = name.ifBlank { nextUserPresetName() },
      note = "导入于 " + defaultPresetNote(),
      // 文本里没带对齐就用当前值兜底：对齐是全局的，不属于任何单个部件
      lyricsAlign = parsed.align ?: VisualizerAppearanceStore.state.value.lyricsAlign,
      configs = parsed.configs,
    )
    _userPresets.value = _userPresets.value + preset
    persist(context)
    return ImportOutcome.Ok(preset)
  }

  // ------------------------------------------------------------------ 持久化

  private fun persist(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val presets = _userPresets.value
    val oldCount = prefs.getInt(KEY_USER_COUNT, 0)

    prefs.edit().apply {
      putString(KEY_ACTIVE, _activeId.value)

      _configs.value.forEach { (widget, config) ->
        writeConfig(this, prefix = widget.name, config = config)
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
          writeConfig(this, prefix = userKey(index, widget.name), config = config)
        }
      }
    }.apply()
  }

  /** 把一个部件的全部字段写进编辑器 —— 键名见 [CONFIG_FIELDS] */
  private fun writeConfig(editor: SharedPreferences.Editor, prefix: String, config: WidgetConfig) {
    editor.putString("$prefix.anchor", config.anchor.name)
    editor.putFloat("$prefix.x", config.offsetX.value)
    editor.putFloat("$prefix.y", config.offsetY.value)
    editor.putFloat("$prefix.w", config.width?.value ?: FILL_SENTINEL)
    editor.putFloat("$prefix.h", config.height?.value ?: FILL_SENTINEL)
    editor.putFloat("$prefix.inset", config.trailingInset.value)
    editor.putBoolean("$prefix.on", config.enabled)
    editor.putInt("$prefix.color", config.color)
    editor.putFloat("$prefix.font", config.fontScale)
    editor.putFloat("$prefix.alpha", config.alpha)
    editor.putBoolean("$prefix.lock", config.lockAspect)
    editor.putFloat("$prefix.gain", config.gain)
  }

  /**
   * 读一个部件的配置。
   * [prefix] 是完整的键前缀（不含尾点），比如 `ALBUM_ART` 或 `__up_0_ALBUM_ART`。
   */
  private fun readConfig(
    prefs: SharedPreferences,
    prefix: String,
    fallback: WidgetConfig,
  ): WidgetConfig = WidgetConfig(
    anchor = runCatching {
      AnchorPoint.valueOf(prefs.getString("$prefix.anchor", null) ?: fallback.anchor.name)
    }.getOrDefault(fallback.anchor),
    offsetX = prefs.getFloat("$prefix.x", fallback.offsetX.value).dp,
    offsetY = prefs.getFloat("$prefix.y", fallback.offsetY.value).dp,
    width = prefs.getFloat("$prefix.w", fallback.width?.value ?: FILL_SENTINEL)
      .let { if (it == FILL_SENTINEL) null else it.dp },
    height = prefs.getFloat("$prefix.h", fallback.height?.value ?: FILL_SENTINEL)
      .let { if (it == FILL_SENTINEL) null else it.dp },
    trailingInset = prefs.getFloat("$prefix.inset", fallback.trailingInset.value).dp,
    enabled = prefs.getBoolean("$prefix.on", fallback.enabled),
    color = prefs.getInt("$prefix.color", fallback.color),
    fontScale = prefs.getFloat("$prefix.font", fallback.fontScale)
      .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
    alpha = prefs.getFloat("$prefix.alpha", fallback.alpha)
      .coerceIn(MIN_ALPHA, MAX_ALPHA),
    lockAspect = prefs.getBoolean("$prefix.lock", fallback.lockAspect),
    gain = prefs.getFloat("$prefix.gain", fallback.gain)
      .coerceIn(MIN_GAIN, MAX_GAIN),
  )

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
              readConfig(prefs, prefix = userKey(index, widget.name), fallback = WidgetConfig())
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

  private fun userKey(index: Int, field: String) = "__up_${index}_$field"

  /** 用一个不可能自然出现的宽度值表示「撑满」 */
  private const val FILL_SENTINEL = -1f

  private val CONFIG_FIELDS = listOf(
    "anchor", "x", "y", "w", "h", "inset", "on",
    "color", "font", "alpha", "lock", "gain",
  )
}

// -------------------------------------------------------------------- 文本编解码
//
// 抽成顶层纯函数（不碰 Context / SharedPreferences）是为了能直接单测：
// 这套文本要经手剪贴板、聊天软件，往返一次不能变形，而解析里的边界
// （缺字段、脏值、行号报错）恰恰是手点最难覆盖的地方。

/**
 * 把布局编成文本，与 [parseLayoutText] 严格互逆。
 *
 * 格式是逐行 `key=value`（抬头一行版本号）—— **故意不用 JSON**：
 * 它要经手剪贴板、聊天软件，出问题时人得能一眼看出哪一行不对；
 * 而且这个格式的解析逻辑简单到不需要引入任何库。
 */
internal fun formatLayoutText(
  align: LyricsAlign,
  configs: Map<VisualizerWidget, WidgetConfig>,
): String = buildString {
  appendLine(EXPORT_HEADER)
  appendLine("align=${align.name}")
  configs.forEach { (widget, c) ->
    appendLine("${widget.name}.anchor=${c.anchor.name}")
    appendLine("${widget.name}.x=${c.offsetX.value}")
    appendLine("${widget.name}.y=${c.offsetY.value}")
    // 撑满时**不写这一行** —— 缺失即空，比写一个魔法值干净
    c.width?.let { appendLine("${widget.name}.w=${it.value}") }
    c.height?.let { appendLine("${widget.name}.h=${it.value}") }
    appendLine("${widget.name}.inset=${c.trailingInset.value}")
    appendLine("${widget.name}.on=${c.enabled}")
    appendLine("${widget.name}.color=" + String.format(Locale.US, "#%08X", c.color))
    appendLine("${widget.name}.font=${c.fontScale}")
    appendLine("${widget.name}.alpha=${c.alpha}")
    appendLine("${widget.name}.lock=${c.lockAspect}")
    appendLine("${widget.name}.gain=${c.gain}")
  }
}

/** [parseLayoutText] 的结果。[align] 为 null 表示文本里没写对齐，由调用方兜底 */
internal sealed interface LayoutParseResult {
  data class Ok(
    val align: LyricsAlign?,
    val configs: Map<VisualizerWidget, WidgetConfig>,
  ) : LayoutParseResult

  data class Failed(val message: String) : LayoutParseResult
}

/**
 * 解析布局文本。
 *
 * 宽容策略：不认识的行、认不出的取值一律跳过（只要求至少认出一个字段）；
 * 每个部件都从**出厂默认**起算，所以文本里没写的字段拿到默认值，
 * 不会沾上调用方现有布局的残留
 * （也就自然满足了「缺 `w` 行 = 撑满」这个格式约定）。
 */
internal fun parseLayoutText(text: String): LayoutParseResult {
  val lines = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toList()

  if (lines.isEmpty()) return LayoutParseResult.Failed("内容是空的")
  if (lines.first() != EXPORT_HEADER) {
    return LayoutParseResult.Failed("这不是本应用导出的布局文本（开头应当是 $EXPORT_HEADER）")
  }

  val fields = HashMap<String, String>()
  lines.drop(1).forEachIndexed { index, line ->
    val at = line.indexOf('=')
    if (at <= 0) {
      // 行号按原文算：抬头占 1 行，所以 +2
      return LayoutParseResult.Failed("第 ${index + 2} 行格式不对，应当是 `键=值`：$line")
    }
    fields[line.substring(0, at).trim()] = line.substring(at + 1).trim()
  }
  if (fields.isEmpty()) return LayoutParseResult.Failed("文本里没有任何可用的配置项")

  var applied = 0
  val configs = VisualizerWidget.entries.associateWith { widget ->
    val p = widget.name + "."
    var next = WidgetConfig()

    fields[p + "anchor"]?.let { raw ->
      enumOrNull<AnchorPoint>(raw)?.let { next = next.copy(anchor = it); applied++ }
    }
    fields[p + "x"]?.toFloatOrNull()?.let { next = next.copy(offsetX = it.dp); applied++ }
    fields[p + "y"]?.toFloatOrNull()?.let { next = next.copy(offsetY = it.dp); applied++ }
    fields[p + "w"]?.toFloatOrNull()?.let { next = next.copy(width = it.dp); applied++ }
    fields[p + "h"]?.toFloatOrNull()?.let { next = next.copy(height = it.dp); applied++ }
    fields[p + "inset"]?.toFloatOrNull()?.let { next = next.copy(trailingInset = it.dp); applied++ }
    fields[p + "on"]?.toBooleanStrictOrNull()?.let { next = next.copy(enabled = it); applied++ }
    fields[p + "color"]?.let { raw ->
      parseColor(raw)?.let { next = next.copy(color = it); applied++ }
    }
    fields[p + "font"]?.toFloatOrNull()
      ?.let { next = next.copy(fontScale = it.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)); applied++ }
    fields[p + "alpha"]?.toFloatOrNull()
      ?.let { next = next.copy(alpha = it.coerceIn(MIN_ALPHA, MAX_ALPHA)); applied++ }
    fields[p + "lock"]?.toBooleanStrictOrNull()?.let { next = next.copy(lockAspect = it); applied++ }
    fields[p + "gain"]?.toFloatOrNull()
      ?.let { next = next.copy(gain = it.coerceIn(MIN_GAIN, MAX_GAIN)); applied++ }

    next
  }

  if (applied == 0) return LayoutParseResult.Failed("文本里没有任何认识的配置项")

  return LayoutParseResult.Ok(
    align = fields["align"]?.let { enumOrNull<LyricsAlign>(it) },
    configs = configs,
  )
}

/** 解析 `#AARRGGBB` */
private fun parseColor(raw: String): Int? {
  val hex = raw.removePrefix("#")
  if (hex.length != 8) return null
  return hex.toLongOrNull(16)?.toInt()
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
  runCatching { enumValueOf<T>(name) }.getOrNull()

/**
 * 导入的结果：要么得到一个新存下来的预设，要么得到一条给用户看的失败原因。
 *
 * 用不着 `Result` —— 这里只有「成功」和「文本有问题」两种可能，
 * 而后者需要一句人话解释，不是异常。
 */
sealed interface ImportOutcome {
  data class Ok(val preset: UserPreset) : ImportOutcome
  data class Failed(val message: String) : ImportOutcome
}

/**
 * 供导入/导出识别用的抬头。
 *
 * 带版本号是为了将来格式变了还能认出来 —— 解析时只认这一行完全相等，
 * 所以旧版本应用导入新格式文本会明确报错，而不是解析出一堆乱七八糟的东西。
 *
 * `internal` 而非 `private`：单测要拿它来断言「抬头不对时的提示里有没有它」。
 */
internal const val EXPORT_HEADER = "AVL-LAYOUT-1"
