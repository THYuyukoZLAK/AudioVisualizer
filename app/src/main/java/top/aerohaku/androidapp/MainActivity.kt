package top.aerohaku.androidapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.display.DisplayRefresh
import top.aerohaku.androidapp.display.ScreenSettingsStore
import top.aerohaku.androidapp.playback.MediaBrowserNowPlayingSource
import top.aerohaku.androidapp.playback.MetadataSettingsStore
import top.aerohaku.androidapp.theme.AndroidAppTheme
import top.aerohaku.androidapp.theme.ScexColors

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 整个应用是横屏版式（进度条横跨全屏，频谱/波形按宽高比排布），锁定横屏。
    // 清单里也写了 android:screenOrientation="landscape"，这里再设一次是因为
    // 部分 ROM 上清单项会被忽略，而代码设置更即时。
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

    enableEdgeToEdge()

    // 请求以显示支持的最高刷新率运行（本机平板 165Hz）。
    // 必须放在 enableEdgeToEdge 之后：那个调用会重写窗口的 LayoutParams，
    // 先设 preferredRefreshRate 会被它覆盖掉。
    DisplayRefresh.requestHighest(this)

    // 元数据来源设置（是否启用备用通道）要在任何地方读它之前先加载
    MetadataSettingsStore.ensureLoaded(this)
    // 屏幕设置（保持常亮）同理
    ScreenSettingsStore.ensureLoaded(this)

    setContent {
      AndroidAppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Box(Modifier.fillMaxSize()) {
            MainNavigation()
            PortraitGuard()
            KeepScreenOnEffect()
            ImmersiveEffect()
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // 界面在前台时保持元数据连接（默认链路：直接读网易云对外接口，零权限）。
    // 退到后台就断开 —— 这条链路走 bindService(BIND_AUTO_CREATE)，
    // 而 ucar.UCarService 跑在网易云主进程里，一直挂着会把没在播放的网易云拉起来。
    // （捕获服务在跑时它自己会另外 retain，所以后台自动启停不受影响）
    MediaBrowserNowPlayingSource.retain(this, MediaBrowserNowPlayingSource.REASON_UI)
  }

  override fun onStop() {
    MediaBrowserNowPlayingSource.release(MediaBrowserNowPlayingSource.REASON_UI)
    super.onStop()
  }
}

/**
 * 使用期间阻止熄屏。
 *
 * 用 `View.setKeepScreenOn`（它内部就是给所在窗口加 `FLAG_KEEP_SCREEN_ON`）
 * 而不是直接 `window.addFlags`：Compose 的 `DisposableEffect` 能在设置改变或
 * 界面退场时干净地撤销，不用额外配 Activity 生命周期。
 *
 * 这个标志只对**可见窗口**生效，所以应用一旦退到后台自然就不再阻止熄屏 ——
 * 正好就是「应用启用时」的语义。
 */
@Composable
private fun KeepScreenOnEffect() {
  val view = LocalView.current
  val settings by ScreenSettingsStore.state.collectAsStateWithLifecycle()
  DisposableEffect(settings.keepScreenOn) {
    view.keepScreenOn = settings.keepScreenOn
    onDispose { view.keepScreenOn = false }
  }
}

/**
 * 全屏：隐藏状态栏与任务栏。
 *
 * ## 为什么不用清单里的全屏主题
 *
 * `android:theme` 那套 `windowFullscreen` 只能盖住**状态栏**；导航栏/任务栏要靠已废弃的
 * `SYSTEM_UI_FLAG_HIDE_NAVIGATION`，而那种「硬隐藏」在 Android 11+ 会被系统限制，
 * 还会丢掉「划一下临时唤出」的能力。
 *
 * 这里用 [WindowInsetsControllerCompat] 的 immersive 模式：
 * `hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`。
 *
 * ## 本机的任务栏确实能被收掉
 *
 * 联想 TB321FU 是 Android 15 / `ro.build.characteristics=tablet`，
 * `dumpsys activity service SystemUIService` 里有
 * `com.android.systemui.navigationbar.TaskbarDelegate` —— 平板那条常驻任务栏。
 * 它挂在 `navigationBars()` 的 insets 上，所以会跟着一起收掉。
 *
 * ⚠️ 用 compat 类而不是平台 `WindowInsetsController`（API 30+）：本应用 minSdk 29，
 * compat 会在 Android 10 上退回 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 那条路。
 *
 * ⚠️ **每次回到前台都要重新施加一次**：切出去再回来（尤其是经过分屏/多窗口）时
 * 系统会把系统栏还回来。所以挂在 ON_RESUME 上，而不是只在设置变化时做一次。
 */
@Composable
private fun ImmersiveEffect() {
  val view = LocalView.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val settings by ScreenSettingsStore.state.collectAsStateWithLifecycle()

  DisposableEffect(view, lifecycleOwner, settings.hideSystemBars) {
    val window = view.context.findActivity()?.window
    if (window == null) return@DisposableEffect onDispose { }

    fun apply() {
      val controller = WindowInsetsControllerCompat(window, view)
      if (settings.hideSystemBars) {
        controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
      } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
    }

    apply()
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) apply()
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // 界面退场时把系统栏还回去：否则从本界面跳到系统设置时，
      // 那边也会是「没有状态栏」的观感，用户会莫名其妙
      runCatching {
        WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }
}

/**
 * 从 Context 里找 Activity。
 *
 * 不能直接 `view.context as? Activity`：Compose 拿到的 context 通常包了一层
 * `ContextWrapper`，直接强转在部分 ROM 上会得到 null（系统栏就会静默地不生效）。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/**
 * 竖屏兜底遮罩。
 *
 * `<activity android:screenOrientation="landscape">` 在 **Android 12+ 的大屏设备
 * （`sw >= 600dp`）上可能被系统直接忽略** —— 平台有个 `config_ignoreOrientationRequest`
 * 开关，平板普遍开着；本机联想 TB321FU 的 `sw` ≈ 753dp，正好落在范围内。
 *
 * 系统不理会时，横屏专属的版式会挤成一团，比直接挡住更难理解，
 * 所以这里加一层遮罩：只要检测到竖屏就盖住全部内容。
 */
@Composable
private fun PortraitGuard() {
  if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT) return

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ScexColors.Background),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(32.dp),
    ) {
      Text(
        text = "请将设备横过来使用",
        color = ScexColors.Heading,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
      Text(
        text = "本应用的版式按横屏设计，竖屏下无法正确显示。",
        color = ScexColors.Body,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
    }
  }
}
