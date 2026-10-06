package top.aerohaku.androidapp.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频从哪来。
 *
 * [PLAYBACK_CAPTURE] 用 `MediaProjection` + `AudioPlaybackCapture`；[SYSTEM_MIX] 把
 * `Visualizer` 效果器挂在**全局输出混音**上。完整论证见项目笔记的「音频来源再评估」一节。
 *
 * ## 默认是 [PLAYBACK_CAPTURE]（2026-10-06 改）
 *
 * 早先默认是全局混音，理由只是「授权代价小」。但实测下来它付出的代价远大于收益：
 * 框架给的音频是**单声道 + 每块 AGC** 过的，于是响度信息整体丢失、电平表基本失效、
 * 频谱与波形图的振幅与实际响度无关（详见 [SYSTEM_MIX] 的文案）。
 * 所以现在把屏幕录制捕获重新定为默认，全局混音降为备选。
 *
 * ⚠️ 只改默认值，**不改用户已经显式选过的**：存过的值照旧生效。
 */
enum class AudioSource(val label: String, val note: String) {
  PLAYBACK_CAPTURE(
    "屏幕录制捕获（默认）",
    "开始捕获时，系统会弹一次「允许录制或投射您的屏幕」。\n" +
      "授权后可获得完整的可视化体验，包括能反映真实响度的电平表与频谱图，更高精度的波形图等。\n" +
      "我们只捕获目标软件（网易云音乐）的音频数据。\n" +
      "您可以选择只捕获单个应用",
  ),
  SYSTEM_MIX(
    "全局混音（备选）",
    "索要音频录制权限，这是一过性的操作，没有系统弹窗和常驻通知。\n" +
      "代价是应用将只能获取单声道且经过AGC处理的音频。\n" +
      "电平表将完全失效，基本只能反映当前音频平均响度。\n" +
      "频谱图和波形图的振幅将与音频实际响度无关。",
  ),
}

/** [AudioSource] 的持久化。做法与 `ScreenSettingsStore` 一致 */
object AudioSourceSettingsStore {

  private const val PREF_NAME = "audio_source"
  private const val KEY_SOURCE = "source"

  private val _source = MutableStateFlow(AudioSource.PLAYBACK_CAPTURE)
  val source: StateFlow<AudioSource> = _source.asStateFlow()

  @Volatile private var loaded = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      // 认不出的值（比如手动改过配置文件）退回默认，而不是崩掉
      val stored = prefsOf(context).getString(KEY_SOURCE, null)
      _source.value = AudioSource.entries.firstOrNull { it.name == stored }
        ?: AudioSource.PLAYBACK_CAPTURE
      loaded = true
    }
  }

  fun setSource(context: Context, value: AudioSource) {
    ensureLoaded(context)
    _source.value = value
    prefsOf(context).edit().putString(KEY_SOURCE, value.name).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
