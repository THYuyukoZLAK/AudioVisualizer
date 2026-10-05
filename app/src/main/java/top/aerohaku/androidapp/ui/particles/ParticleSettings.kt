package top.aerohaku.androidapp.ui.particles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背景粒子的配置。字段与 mfosu 的 `ParticleSettings` 面板一一对应，
 * 只多了一个「能量倍率」（对应它那个不可配置的 `Σamplitudes` 标度）。
 */
data class ParticleSettings(
  val enabled: Boolean = true,
  /** 粒子数。mfosu `ParticleCount`，但见 [ParticleField.DEFAULT_COUNT] 的说明 */
  val count: Int = ParticleField.DEFAULT_COUNT,
  /**
   * 运动方向。默认值与 mfosu 一致（`Random`）。
   *
   * `Random` 是缓慢的随机游走 + 尺寸差异，音频耦合表现为「漂移快慢」；
   * `Forward` 是整场从中心向外扩散，鼓点冲劲最直观。两种都值得试。
   */
  val direction: ParticleDirection = ParticleDirection.RANDOM,
  /** mfosu `GlobalSpeed`，默认 100 / 范围 1..200。相对倍率，100 = 1× */
  val globalSpeed: Int = ParticleField.DEFAULT_GLOBAL_SPEED,
  /** 粒子尺寸倍率（1 = mfosu 的原始相对尺寸）。基准见 [ParticleField.SIZE_REFERENCE_WIDTH] */
  val sizeScale: Float = DEFAULT_SIZE_SCALE,
  /**
   * 粒子整体不透明度（0..1）。
   *
   * mfosu 没有这一项 —— 它的粒子层是顶在最前面的主体；
   * 而本 app 里粒子是**背景**，得能压淡以不抢前景部件的注意力。
   */
  val opacity: Float = DEFAULT_OPACITY,
  /**
   * 音频能量 → 粒子时间流速的倍率。
   *
   * mfosu 是把 `ΣFrequencyAmplitudes` 直接当 Rate 用（即倍率 1），
   * 但两边的 Σ 绝对标度不可能一致，所以做成可调，配合设置页的实时读数调一次即可。
   * 设为 0 = 关掉音频耦合，粒子变成匀速背景。
   */
  val energyGain: Float = ParticleField.DEFAULT_ENERGY_GAIN,
) {
  companion object {
    /**
     * mfosu 的原始相对尺寸在 2560px 宽的平板上偏大（大颗粒会阴到歌词与频谱），
     * 所以默认压到一半。
     */
    const val DEFAULT_SIZE_SCALE = 0.5f
    const val MIN_SIZE_SCALE = 0.2f
    const val MAX_SIZE_SCALE = 3f

    /** 默认七成不透明：既看得见层次，又不会和前景的纯白部件抢眼 */
    const val DEFAULT_OPACITY = 0.7f
    const val MIN_OPACITY = 0.05f
    const val MAX_OPACITY = 1f
  }
}

/** 粒子配置的持久化。做法与 [top.aerohaku.androidapp.ui.layout.VisualizerAppearanceStore] 一致 */
object ParticleSettingsStore {

  private const val PREF_NAME = "visualizer_particles"

  private val _state = MutableStateFlow(ParticleSettings())
  val state: StateFlow<ParticleSettings> = _state.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
      val defaults = ParticleSettings()
      _state.value = ParticleSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
        count = prefs.getInt(KEY_COUNT, defaults.count)
          .coerceIn(ParticleField.MIN_COUNT, ParticleField.MAX_COUNT),
        direction = runCatching {
          ParticleDirection.valueOf(
            prefs.getString(KEY_DIRECTION, null) ?: defaults.direction.name,
          )
        }.getOrDefault(defaults.direction),
        globalSpeed = prefs.getInt(KEY_SPEED, defaults.globalSpeed)
          .coerceIn(ParticleField.MIN_GLOBAL_SPEED, ParticleField.MAX_GLOBAL_SPEED),
        sizeScale = prefs.getFloat(KEY_SIZE, defaults.sizeScale)
          .coerceIn(ParticleSettings.MIN_SIZE_SCALE, ParticleSettings.MAX_SIZE_SCALE),
        opacity = prefs.getFloat(KEY_OPACITY, defaults.opacity)
          .coerceIn(ParticleSettings.MIN_OPACITY, ParticleSettings.MAX_OPACITY),
        energyGain = prefs.getFloat(KEY_GAIN, defaults.energyGain)
          .coerceIn(ParticleField.MIN_ENERGY_GAIN, ParticleField.MAX_ENERGY_GAIN),
      )
      loaded = true
    }
  }

  fun setEnabled(context: Context, value: Boolean) = update(context) { it.copy(enabled = value) }

  fun setCount(context: Context, value: Int) = update(context) {
    it.copy(count = value.coerceIn(ParticleField.MIN_COUNT, ParticleField.MAX_COUNT))
  }

  fun setDirection(context: Context, value: ParticleDirection) =
    update(context) { it.copy(direction = value) }

  fun setGlobalSpeed(context: Context, value: Int) = update(context) {
    it.copy(
      globalSpeed = value.coerceIn(
        ParticleField.MIN_GLOBAL_SPEED,
        ParticleField.MAX_GLOBAL_SPEED,
      ),
    )
  }

  fun setSizeScale(context: Context, value: Float) = update(context) {
    it.copy(
      sizeScale = value.coerceIn(ParticleSettings.MIN_SIZE_SCALE, ParticleSettings.MAX_SIZE_SCALE),
    )
  }

  fun setOpacity(context: Context, value: Float) = update(context) {
    it.copy(opacity = value.coerceIn(ParticleSettings.MIN_OPACITY, ParticleSettings.MAX_OPACITY))
  }

  fun setEnergyGain(context: Context, value: Float) = update(context) {
    it.copy(
      energyGain = value.coerceIn(
        ParticleField.MIN_ENERGY_GAIN,
        ParticleField.MAX_ENERGY_GAIN,
      ),
    )
  }

  fun reset(context: Context) {
    _state.value = ParticleSettings()
    persist(context)
  }

  private inline fun update(context: Context, block: (ParticleSettings) -> ParticleSettings) {
    _state.value = block(_state.value)
    persist(context)
  }

  private fun persist(context: Context) {
    val s = _state.value
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_ENABLED, s.enabled)
      .putInt(KEY_COUNT, s.count)
      .putString(KEY_DIRECTION, s.direction.name)
      .putInt(KEY_SPEED, s.globalSpeed)
      .putFloat(KEY_SIZE, s.sizeScale)
      .putFloat(KEY_OPACITY, s.opacity)
      .putFloat(KEY_GAIN, s.energyGain)
      .apply()
  }

  fun formatSizeScale(value: Float): String = String.format(java.util.Locale.US, "%.2f×", value)
  fun formatGain(value: Float): String = String.format(java.util.Locale.US, "%.2f×", value)
  fun formatOpacity(value: Float): String = "${(value * 100).toInt()}%"

  private const val KEY_ENABLED = "enabled"
  private const val KEY_COUNT = "count"
  private const val KEY_DIRECTION = "direction"
  private const val KEY_SPEED = "globalSpeed"
  private const val KEY_SIZE = "sizeScale"
  private const val KEY_OPACITY = "opacity"
  private const val KEY_GAIN = "energyGain"
}
