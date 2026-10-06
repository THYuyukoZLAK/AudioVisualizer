package top.aerohaku.androidapp.capture

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设备音量补偿里唯一能脱离真机测的那一段：**输出设备的挑选**。
 *
 * 音量曲线是**按输出设备**定义的（[android.media.AudioManager.getStreamVolumeDb] 的
 * `deviceType` 参数），所以挑错设备就等于刻度整体平移几个 dB。
 * `AudioDeviceInfo` 没有公开的「当前激活」标志，只能按优先级猜 —— 这里把这套顺序钉住。
 */
class DeviceVolumeTest {

  @Test
  fun `没有已连接输出时兜底用内置扬声器`() {
    assertEquals(
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
      pickOutputDeviceType(emptyList()),
    )
  }

  @Test
  fun `插了耳机就用耳机`() {
    assertEquals(
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      pickOutputDeviceType(
        listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
      ),
    )
  }

  @Test
  fun `蓝牙优先于有线和内置`() {
    assertEquals(
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      pickOutputDeviceType(
        listOf(
          AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
          AudioDeviceInfo.TYPE_WIRED_HEADSET,
          AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        ),
      ),
    )
  }

  @Test
  fun `不认识的设备类型不影响兜底`() {
    assertEquals(
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
      pickOutputDeviceType(listOf(AudioDeviceInfo.TYPE_UNKNOWN, TYPE_FAKE)),
    )
  }

  @Test
  fun `只有扬声器和听筒时选扬声器`() {
    assertEquals(
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
      pickOutputDeviceType(
        listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
      ),
    )
  }

  private companion object {
    /** 一个不存在于任何已知列表里的设备类型，用来验证「认不出来也不会崩」 */
    const val TYPE_FAKE = 9999
  }
}
