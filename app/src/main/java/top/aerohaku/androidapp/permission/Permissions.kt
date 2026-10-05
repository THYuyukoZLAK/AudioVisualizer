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
