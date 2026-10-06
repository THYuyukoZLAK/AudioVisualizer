package top.aerohaku.androidapp.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object Permissions {

  /** 是否已授予「通知使用权」（读 MediaSession 元数据的前提） */
  fun isNotificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

  fun isPostNotificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }

  /**
   * 「全局混音」音频源需要它（见 `AudioSource`）。
   *
   * ⚠️ 这里**只要** `RECORD_AUDIO`（危险权限），不需要 `CAPTURE_AUDIO_OUTPUT` ——
   * 后者是 `signature|privileged`，第三方应用根本拿不到。`Visualizer` 挂在输出混音上时，
   * 框架强制要求 `RECORD_AUDIO`（保护语音留言之类的隐私音频）+ `MODIFY_AUDIO_SETTINGS`。
   *
   * 它不是「录音」：返回的只有 8 位、低精度的可视化数据，也没有暂停/存储一说 ——
   * 但它确实是危险权限，所以只在用户真的选了这条路时才申请。
   */
  fun isRecordAudioGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** 跳到通知使用权设置页。返回是否成功跳转。 */
  fun openNotificationListenerSettings(context: Context): Boolean {
    val candidates = listOf(
      Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
      Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
      val ok = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }.isSuccess
      if (ok) return true
    }
    return false
  }
}
