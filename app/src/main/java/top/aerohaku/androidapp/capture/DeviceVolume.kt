package top.aerohaku.androidapp.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 读「设备音量把信号衰减了多少 dB」（≤ 0），用来把框架测到的绝对电平换算成
 * **内容本身的电平** —— 也就是那个**与设备音量无关**的值。
 *
 * ## 为什么需要它
 *
 * 混音路径上 `Visualizer` 挂在输出混音的末端，**它看到的是已经乘过媒体音量的信号**。
 * AOSP 的证据很直接：`EffectVisualizer::process()` 里写入捕获缓冲的是
 * `inBuffer` 本身（`smp * fscale`，`smp` 就是效果器的输入），而效果器位于输出线程
 * 效果链的末尾 —— 音量在混音阶段就已经乘进去了。
 *
 * ⇒ `getMeasurementPeakRms()` 给的「绝对 RMS」其实是**输出**的绝对 RMS：用户把音量调小，
 * 它就跟着小。[MixLevelAnchor] 拿它当基准，三件可视化组件自然就全跟着设备音量缩放 ——
 * 那是不对的。
 *
 * 而屏幕录制那条路（`AudioPlaybackCapture`）抓的是**应用流本身**，实测不受输出音量影响。
 * 两条路的差别**恰好**只是这一级音量衰减，减掉它，读数就回到「内容自己的刻度」上。
 *
 * ## 取值来源与可信度
 *
 * [AudioManager.getStreamVolumeDb]（API 28+）返回「该流在该音量档、该输出设备上的增益」。
 * 音量曲线是**按输出设备**定义的，所以还得挑一个设备，见 [pickOutputDeviceType]。
 *
 * 它是**估计值**：蓝牙绝对音量、厂商自定义曲线都可能让它与实际衰减差几个 dB。但那只
 * 相当于给整条刻度一个**恒定的 dB 平移**，既不破坏起伏，也不破坏「与音量无关」这一点。
 *
 * 真读不到时返回 0（= 不补偿），退化成 1.4 版之前的行为（读数跟着音量走），
 * 这只是降级，不会把显示弄坏。
 */
internal fun musicOutputVolumeDb(context: Context): Float {
  val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return NO_ATTENUATION_DB
  val index = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
  // 静音档：抓到的本来就是静音，补偿没有意义，也避免拿到 -inf
  if (index <= 0) return NO_ATTENUATION_DB

  val deviceType = runCatching { pickOutputDeviceType(am) }.getOrDefault(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
  val db = runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType) }.getOrNull()
    ?: return NO_ATTENUATION_DB
  if (db.isNaN() || db.isInfinite()) return NO_ATTENUATION_DB

  // 衰减量只可能是「压小」，个别设备可能返回正值，夹住以免变成「放大」
  return db.coerceIn(MIN_ATTENUATION_DB, 0f)
}

/** 从系统里取出「已连接的输出设备类型」，再交给纯函数挑 ———— 真正的选择逻辑见 [pickOutputDeviceType] */
internal fun pickOutputDeviceType(am: AudioManager): Int {
  val connected = runCatching {
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
  }.getOrDefault(emptyList())
  return pickOutputDeviceType(connected)
}

/**
 * 从「已连接的输出设备类型」里挑一个**最可能正在出声**的。
 *
 * `AudioDeviceInfo` 没有公开的「当前激活」标志，只能在已连接的输出里按优先级猜：
 * 蓝牙 > USB > 有线 > 助听器 > 内置扬声器 > 听筒。
 * 猜错的后果只是刻度整体平移几个 dB（见 [musicOutputVolumeDb]），不影响可用性。
 */
internal fun pickOutputDeviceType(connected: List<Int>): Int {
  for (candidate in OUTPUT_PRIORITY) {
    if (connected.contains(candidate)) return candidate
  }
  return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

/**
 * 挑设备的优先级。
 *
 * 只用到 API 26 及以下的常量 —— 全部低于 minSdk 29，不会触发 `InlinedApi`。
 * 蓝牙 LE 音频（31+）在实践中会以 `TYPE_HEARING_AID` 或 A2DP 的形式出现，已被覆盖。
 */
private val OUTPUT_PRIORITY = intArrayOf(
  AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
  AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
  AudioDeviceInfo.TYPE_USB_HEADSET,
  AudioDeviceInfo.TYPE_USB_DEVICE,
  AudioDeviceInfo.TYPE_WIRED_HEADSET,
  AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
  AudioDeviceInfo.TYPE_HEARING_AID,
  AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
  AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
  AudioDeviceInfo.TYPE_AUX_LINE,
)

/** 读不到时的兜底：不补偿 */
internal const val NO_ATTENUATION_DB = 0f

/** 衰减量下限（防御性夹取，正常最差也就 -70dB 上下） */
internal const val MIN_ATTENUATION_DB = -96f
