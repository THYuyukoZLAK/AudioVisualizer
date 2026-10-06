package top.aerohaku.androidapp.permission

import android.content.Context

/**
 * 「关于屏幕录制授权」这份说明**有没有给用户看过**。
 *
 * **只弹一次**，两个入口共用同一个标志：
 * 1. 装机后第一次启动时由主界面自动弹（`VisualizerScreen`）；
 * 2. 第一次进「设置 → 播放」时自动弹（`SettingsScreen`）。
 * 两边任意一次被关掉，标志就写上了，另一个入口也不会再自动弹。
 *
 * 之后想再看，卡片里的「屏幕录制权限说明」按钮随时能打开。
 *
 * ⚠️ 用它而不是用 `remember`：**设置页是浮层，关掉就从组合里移除**，
 * 所有 `remember` 状态都会复位 —— 拿本地状态当「已经弹过了」的话，
 * 每次进设置都会再弹一次（实测踩过）。
 *
 * ⚠️ 键名带 `_v1`：**说明内容有实质改动时把版本号 +1**，老用户就会重新看到一次新版说明。
 */
object ProjectionDisclosureStore {

  private const val PREF_NAME = "projection_disclosure"
  private const val KEY_SHOWN = "shown_v1"

  /** 还没给用户看过这份说明时返回 true（此时应该自动弹一次），看过之后一直 false */
  fun shouldAutoShow(context: Context): Boolean =
    !prefsOf(context).getBoolean(KEY_SHOWN, false)

  /** 用户关掉弹窗时调用。只写一次，之后不再自动弹 */
  fun markShown(context: Context) {
    prefsOf(context).edit().putBoolean(KEY_SHOWN, true).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
