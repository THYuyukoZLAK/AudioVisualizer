package top.aerohaku.androidapp.ui.visualizer

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import top.aerohaku.androidapp.capture.AudioCaptureService
import top.aerohaku.androidapp.capture.CaptureController
import top.aerohaku.androidapp.lyrics.LyricsState
import top.aerohaku.androidapp.model.NowPlaying
import top.aerohaku.androidapp.model.TargetApp
import top.aerohaku.androidapp.permission.Permissions
import top.aerohaku.androidapp.playback.MetadataSettingsStore
import top.aerohaku.androidapp.ui.components.AlbumArtBackground
import top.aerohaku.androidapp.ui.components.GearIcon
import top.aerohaku.androidapp.ui.components.TransportRail
import top.aerohaku.androidapp.ui.settings.SettingsScreen
import top.aerohaku.androidapp.ui.settings.SliderHud
import top.aerohaku.androidapp.ui.components.LevelMeterView
import top.aerohaku.androidapp.ui.components.LyricsView
import top.aerohaku.androidapp.ui.components.ParticleEnergySource
import top.aerohaku.androidapp.ui.components.ParticleFieldView
import top.aerohaku.androidapp.ui.components.ProgressBarView
import top.aerohaku.androidapp.ui.components.SpectrumView
import top.aerohaku.androidapp.ui.components.VizStyle
import top.aerohaku.androidapp.ui.components.WaveformView
import top.aerohaku.androidapp.ui.layout.AnchoredLayout
import top.aerohaku.androidapp.ui.layout.LayoutScale
import top.aerohaku.androidapp.ui.layout.VisualizerAppearanceStore
import top.aerohaku.androidapp.ui.layout.VisualizerLayoutStore
import top.aerohaku.androidapp.ui.layout.VisualizerWidget
import top.aerohaku.androidapp.ui.layout.WidgetConfig
import top.aerohaku.androidapp.ui.layout.placement
import top.aerohaku.androidapp.ui.particles.ParticleSettingsStore
import java.util.Locale

/**
 * 可视化主页面 —— 「无边框架」版式。
 *
 * 结构（从下到上）：
 * 1. **背景**：专辑封面 → 高斯模糊 → 30% 暗化，铺满全屏
 * 2. **锚点布局**：所有部件按各自的「九锚点 + 偏移」摆放，位置可在设置页调整并持久化
 * 3. **顶部进度条**：横跨整个屏幕
 * 4. **右下角设置按钮**：固定不动，保证任何布局下都能进设置
 * 5. **权限/捕获提示条**：只在需要时出现，浮在最上层
 *
 * ⚠️ 这里**不再有任何 Card / 背景 / 边框 / 圆角** —— 全部靠白色图形与封面背景的对比。
 */
@Composable
fun VisualizerScreen(
  modifier: Modifier = Modifier,
  viewModel: VisualizerViewModel = viewModel { VisualizerViewModel() },
) {
  val context = LocalContext.current

  // 设置页是**浮在本界面之上**的浮层，不是另一个导航目的地 ——
  // 拖动滑块时它要变透明，让用户实时看见背后可视化的变化。
  var showSettings by remember { mutableStateOf(false) }
  val lifecycleOwner = LocalLifecycleOwner.current

  val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
  val listenerConnected by viewModel.listenerConnected.collectAsStateWithLifecycle()
  val browserConnected by viewModel.browserConnected.collectAsStateWithLifecycle()
  val captureStatus by viewModel.captureStatus.collectAsStateWithLifecycle()
  val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
  val lyricUi by viewModel.lyricUi.collectAsStateWithLifecycle()

  var listenerEnabled by remember { mutableStateOf(Permissions.isNotificationListenerEnabled(context)) }
  var postNotificationsGranted by remember { mutableStateOf(Permissions.isPostNotificationsGranted(context)) }

  MetadataSettingsStore.ensureLoaded(context)
  val metadataSettings by MetadataSettingsStore.state.collectAsStateWithLifecycle()

  // 权限「看着已授予但系统还没绑定」的检测。
  // ⚠️ key 必须含 listenerConnected：应用进程被回收后重启时，NowPlayingState 从 false 开始，
  // 系统重新绑定监听服务需要时间；只以 listenerEnabled 为 key 就会把「正在绑定」
  // 误判成「永远不绑定」，且因为 effect 不再重跑，提示会一直挂着（实测踩过）。
  var listenerNotBoundLong by remember { mutableStateOf(false) }
  LaunchedEffect(listenerEnabled, listenerConnected) {
    listenerNotBoundLong = false
    if (listenerEnabled && !listenerConnected) {
      delay(BIND_WAIT_DELAY_MS)
      listenerNotBoundLong = true
    }
  }

  // 默认链路（MediaBrowser）连网易云是要时间的（服务没跑的话还要等它起来）。
  // 同样给一个宽限期，过了还没连上才提示用户 —— 否则一进界面就报「连不上」很吓人。
  var browserStalled by remember { mutableStateOf(false) }
  LaunchedEffect(browserConnected) {
    browserStalled = false
    if (!browserConnected) {
      delay(BIND_WAIT_DELAY_MS)
      browserStalled = true
    }
  }

  // 从系统设置页回来时权限可能变了，每次回到前台重新检查
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        listenerEnabled = Permissions.isNotificationListenerEnabled(context)
        postNotificationsGranted = Permissions.isPostNotificationsGranted(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      postNotificationsGranted = granted
    }

  val projectionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null) {
        AudioCaptureService.start(context, result.resultCode, data)
      } else {
        CaptureController.setStatus(
          CaptureController.Status(CaptureController.Phase.IDLE, message = "已取消捕获授权"),
        )
      }
    }

  val startCapture: () -> Unit = {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    if (manager == null) {
      CaptureController.setStatus(
        CaptureController.Status(CaptureController.Phase.ERROR, message = "系统没有 MediaProjectionManager"),
      )
    } else {
      // API 34+ 用 createConfigForUserChoice()：授权页里会多出「仅共享单个应用」的选项。
      //
      // ⚠️ 说清楚它**并不能消掉警告**：官方文档明确写了「弹给用户的对话框与
      // 直接调 createScreenCaptureIntent() 相同」。它的价值在于用户可以把实际捕获
      // 范围收窄到单个应用（而不是整块屏幕），Scope 更小、心里更踏实。
      // 我们其实只要音频，画面一帧都不读。
      val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
      } else {
        manager.createScreenCaptureIntent()
      }
      projectionLauncher.launch(intent)
    }
  }

  // 部件位置 / 外观配置：首次进入时从 SharedPreferences 读出来
  VisualizerLayoutStore.ensureLoaded(context)
  val configs by VisualizerLayoutStore.configs.collectAsStateWithLifecycle()

  VisualizerAppearanceStore.ensureLoaded(context)
  val appearance by VisualizerAppearanceStore.state.collectAsStateWithLifecycle()

  ParticleSettingsStore.ensureLoaded(context)
  val particles by ParticleSettingsStore.state.collectAsStateWithLifecycle()

  // 粒子层每帧都要读一次最新能量，走 StateFlow 会让每个音频帧都触发重组，
  // 所以转存到一个普通对象里（见 ParticleEnergySource 的注释）。
  val particleEnergy = remember { ParticleEnergySource() }
  LaunchedEffect(viewModel) {
    viewModel.audioFrame.collect { particleEnergy.energy = it.energy }
  }

  // 1 **物理像素**的发丝白框在缩放后的密度里算（见下面部件层），这里不再预先算

  val lyricsLines = (lyricsState as? LyricsState.Ready)?.lines.orEmpty()
  val currentLine = lyricsLines.getOrNull(lyricUi.currentIndex)

  // NowPlaying.artwork 是 android.graphics.Bitmap（直接来自 MediaMetadata），展示前转一次
  val artwork: ImageBitmap? = remember(nowPlaying?.artwork) { nowPlaying?.artwork?.asImageBitmap() }

  val hint = resolveHint(
    browserConnected = browserConnected,
    browserStalled = browserStalled,
    fallbackEnabled = metadataSettings.useNotificationFallback,
    listenerEnabled = listenerEnabled,
    listenerNotBoundLong = listenerNotBoundLong,
    listenerConnected = listenerConnected,
    postNotificationsGranted = postNotificationsGranted,
    capturePhase = captureStatus.phase,
  )

  Box(modifier.fillMaxSize()) {
    // 1) 背景
    AlbumArtBackground(
      artwork = artwork,
      darken = appearance.backgroundDarken,
      modifier = Modifier.fillMaxSize(),
    )

    // 2) 背景粒子。放在封面之上、所有部件之下 —— 它是背景层，不是可锚点摆放的部件。
    if (particles.enabled) {
      ParticleFieldView(
        settings = particles,
        energy = particleEnergy,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // 3) 部件层。这一层会按屏幕尺寸**等比缩放**，见 LayoutScale。
    //
    //    两层 Box 的分工很关键：
    //    - 外层 BoxWithConstraints 量的是**不含提示条预留**的可用区域，缩放倍率只由它决定 ——
    //      否则提示条一出一收，整版会跟着跳一下。
    //    - 内层 Box 才把提示条要占的高度让出来（自然密度下的 56dp，与提示条本身同尺度）。
    BoxWithConstraints(
      Modifier
        .fillMaxSize()
        // 只避让**系统栏**（状态栏 / 导航栏），**不避让屏幕挖孔**。
        //
        // 之前用的是 safeDrawingPadding()，它 = systemBars ∪ displayCutout ∪ ime。
        // 横屏时挖孔通常落在屏幕左侧，于是「横跨整个屏幕」的进度条左边会空出一截。
        // 本版式的左右边距最小也有 64dp，大于常见挖孔的宽度，本来就不会被遮住，
        // 所以避让挖孔只亏不赚。（开发机上没有挖孔，这里改与不改一模一样。）
        .windowInsetsPadding(WindowInsets.systemBars),
    ) {
      val naturalDensity = LocalDensity.current
      val layoutDensity = if (appearance.autoScale) {
        LayoutScale.densityFor(
          availableWidthPx = constraints.maxWidth,
          availableHeightPx = constraints.maxHeight,
          naturalDensity = naturalDensity.density,
        )
      } else {
        naturalDensity.density
      }

      Box(
        Modifier
          .fillMaxSize()
          .then(if (hint != null) Modifier.padding(bottom = HINT_RESERVED_HEIGHT) else Modifier),
      ) {
        CompositionLocalProvider(
          LocalDensity provides Density(layoutDensity, naturalDensity.fontScale),
        ) {
          // 1 **物理像素**的发丝白框：必须在**缩放后的密度**里换算。
          // 若沿用外层密度，小屏上会算出不到 1px 的线宽而直接消失。
          val albumArtBorder = if (appearance.albumArtBorder) (1f / layoutDensity).dp else null

          val visible = LAYOUT_ORDER.filter { configs[it]?.enabled == true }
          val placements = visible.map { widget -> configs.getValue(widget).placement() }

          AnchoredLayout(placements = placements, modifier = Modifier.fillMaxSize()) {
            visible.forEach { widget ->
              SizedWidget(configs.getValue(widget)) {
                when (widget) {
                  VisualizerWidget.PROGRESS ->
                    ProgressBarView(lyricUi.positionMs, nowPlaying?.durationMs ?: 0L, Modifier.fillMaxSize())

                  VisualizerWidget.ALBUM_ART -> AlbumArtImage(artwork, albumArtBorder)

                  VisualizerWidget.TRACK_INFO -> TrackInfo(nowPlaying)

                  VisualizerWidget.LYRICS -> LyricsView(line = currentLine, align = appearance.lyricsAlign)

                  VisualizerWidget.SPECTRUM -> SpectrumView(
                    frames = viewModel.audioFrame,
                    showScale = appearance.showScales,
                  )

                  VisualizerWidget.LEVEL_METERS -> LevelMeterView(
                    frames = viewModel.audioFrame,
                    showScale = appearance.showScales,
                  )

                  VisualizerWidget.WAVEFORM -> WaveformView(viewModel.audioFrame)
                }
              }
            }
          }
        }
      }

      // 4) 右侧播放控制栏：播放列表 / 上一首 / 播放·暂停 / 下一首，自上到下。
      //    数据全部来自网易云对外开放的 MediaBrowserService（见 TransportRail 的注释）。
      //
      //    ⚠️ 它自己铺满整页 —— 为了旁听「整页任何一次触摸」来实现超时自动隐藏。
      //    里面的 pointerInput 不 consume 事件，所以不会挡住下面的部件和设置按钮。
      TransportRail()

      // 4b) 右下角设置按钮（固定位置，不参与锚点配置，保证任何布局下都进得去）
      //    用的是自画的空心齿轮（见 GearIcon）—— material-icons 里
      //    Outlined 变体在十几 MB 的 extended 包里，core 包只有实心的。
      //    contentDescription 不只是无障碍需要：adb 的 uiautomator 也靠它定位这个按钮。
      //
      //    ⚠️  它**不参与缩放**：缩到 0.5 倍就只剩 24dp，手指点不中了。
      IconButton(
        onClick = { showSettings = true },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 8.dp, bottom = 8.dp)
          .semantics { contentDescription = SETTINGS_BUTTON_DESC },
      ) {
        GearIcon(modifier = Modifier.size(SETTINGS_GEAR_SIZE), color = VizStyle.Fill)
      }

      // 5) 权限 / 捕获提示（只在需要时出现）。同样**不缩放**，保证读得清。
      //    外层的 safeDrawingPadding 已经做过了，这里不再重复加。
      if (hint != null) {
        HintBar(
          hint = hint,
          modifier = Modifier.align(Alignment.BottomCenter),
          onAction = {
            when (hint.action) {
              HintAction.OPEN_LISTENER_SETTINGS -> Permissions.openNotificationListenerSettings(context)
              HintAction.REQUEST_NOTIFICATIONS ->
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)

              HintAction.START_CAPTURE -> startCapture()
              HintAction.OPEN_APP_SETTINGS -> showSettings = true
            }
          },
        )
      }
    }

    // 6) 设置页浮层。放在**最上层**、不参与缩放。
    //
    //    它盖在整个可视化之上（包括下面的部件层），Compose 的命中也就会停在它这里，
    //    不用额外加遮罩。拖动滑杆时的透明化在设置页内部处理（见 SettingsDrag）。
    if (showSettings) {
      BackHandler { showSettings = false }
      SettingsScreen(
        onBack = { showSettings = false },
        modifier = Modifier.fillMaxSize(),
      )
      // 拖动滑杆时的读数浮层。放在设置浮层**之上**，并且刻意不挂任何手势，
      // 免得抢掉用户正在进行的拖动（见 SliderHud 的注释）。
      SliderHud(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .safeDrawingPadding()
          .padding(top = 8.dp),
      )
    }
  }
}

/** 绘制顺序 = 这个顺序；同时它也是锚点布局里子部件的顺序依据 */
private val LAYOUT_ORDER = listOf(
  VisualizerWidget.PROGRESS,
  VisualizerWidget.ALBUM_ART,
  VisualizerWidget.TRACK_INFO,
  VisualizerWidget.LYRICS,
  VisualizerWidget.SPECTRUM,
  VisualizerWidget.LEVEL_METERS,
  VisualizerWidget.WAVEFORM,
)

/** 右下角设置按钮的齿轮尺寸。触摸目标由 [IconButton] 保证 ≥ 48dp，不受这个值影响 */
private val SETTINGS_GEAR_SIZE = 28.dp

/** 设置按钮的无障碍名称。也是 adb `uiautomator` 定位它时用的锚点 */
private const val SETTINGS_BUTTON_DESC = "设置"

/**
 * 按 [WidgetConfig] 给部件套上尺寸；`width == null` 表示撑满容器宽度。
 *
 * [WidgetConfig.trailingInset] 只在「撑满」时生效：把内容右边缘往回缩。
 * 没有它的话，「整行 + 左偏移 64dp」的框会直接伸到屏幕外，
 * 长文本的省略号也会落在屏幕外 —— 看起来像没做截断。
 *
 * `clipToBounds()` 是保险：文字类部件已经用 `maxLines + Ellipsis` 自我约束了，
 * 但那依赖「测量约束确实传下去了」。裁一刀之后，即使将来某个部件算错尺寸，
 * 也只会被切掉而不会**叠到隔壁部件上**。
 */
@Composable
private fun SizedWidget(config: WidgetConfig, content: @Composable () -> Unit) {
  val fillWidth = config.width == null
  Box(
    Modifier
      .clipToBounds()
      .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(config.width!!))
      .then(if (config.height != null) Modifier.height(config.height) else Modifier)
      .then(
        if (fillWidth && config.trailingInset > 0.dp) {
          Modifier.padding(end = config.trailingInset)
        } else {
          Modifier
        },
      ),
  ) {
    content()
  }
}

@Composable
private fun AlbumArtImage(artwork: ImageBitmap?, borderWidth: Dp?) {
  Box(Modifier.fillMaxSize()) {
    if (artwork == null) {
      Box(Modifier.fillMaxSize().background(VizStyle.Fill.copy(alpha = 0.12f)))
    } else {
      Image(
        bitmap = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // 白框作为最后一个子元素画，保证压在封面之上（`Modifier.border` 的绘制顺序
    // 在不同版本里不一定晚于 background，叠一层最稳）
    if (borderWidth != null) {
      Box(Modifier.fillMaxSize().border(borderWidth, VizStyle.Fill))
    }
  }
}

@Composable
private fun TrackInfo(nowPlaying: NowPlaying?) {
  // ⚠️ 只 fillMaxWidth：高度要自适应内容。
  // 用 fillMaxSize 的话在「高度自适应」的容器里会被拉满，文字被挤成立式居中。
  // 宽度由外层的 WidgetConfig 控制（默认 340dp），这里不再另设上限。
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
    Text(
      text = nowPlaying?.title ?: "暂无播放",
      color = VizStyle.Fill,
      fontSize = 26.sp,
      fontWeight = FontWeight.Bold,
      style = TextStyle(shadow = VizStyle.TextShadow),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    // 作者单独占一行（它是最常看的一项，不跟其他元数据挤在一起）
    Text(
      text = nowPlaying?.artist ?: "请在 ${TargetApp.LABEL} 中播放一首歌",
      color = VizStyle.Fill.copy(alpha = 0.92f),
      fontSize = 15.sp,
      style = TextStyle(shadow = VizStyle.TextShadow),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(2.dp))
    // 其余元数据（专辑 / 时长 / 喜欢）再起一行
    Text(
      text = buildString {
        append(nowPlaying?.album ?: "—")
        val duration = nowPlaying?.durationMs ?: 0L
        if (duration > 0L) {
          append(" · ")
          append(formatDuration(duration))
        }
        if (nowPlaying?.liked == true) append(" · ♥")
      },
      color = VizStyle.Fill.copy(alpha = 0.72f),
      fontSize = 13.sp,
      style = TextStyle(shadow = VizStyle.TextShadow),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun formatDuration(ms: Long): String {
  val totalSeconds = ms / 1000
  return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

// ------------------------------------------------------------------ 提示条

private enum class HintAction {
  OPEN_LISTENER_SETTINGS,
  OPEN_APP_SETTINGS,
  REQUEST_NOTIFICATIONS,
  START_CAPTURE,
}

private data class Hint(val text: String, val actionText: String, val action: HintAction)

/**
 * 只显示**优先级最高的那一条**提示。
 *
 * 集中成一个纯函数而不是散开的 if-else 链：这些条件互斥且有序，单独一个函数便于推理，
 * 也便于以后加条件。
 *
 * 优先级（从高到低）：
 *  1. 备用通道（通知使用权）开了但还没就绪 → 引导权限
 *  2. 默认链路（MediaBrowser）连上了 → 只提示通知权限 / 启动捕获
 *  3. 默认链路迟迟连不上 → 告知在等网易云，并指出备用通道在哪
 *  4. 剩下的通用提示
 */
private fun resolveHint(
  browserConnected: Boolean,
  browserStalled: Boolean,
  fallbackEnabled: Boolean,
  listenerEnabled: Boolean,
  listenerNotBoundLong: Boolean,
  listenerConnected: Boolean,
  postNotificationsGranted: Boolean,
  capturePhase: CaptureController.Phase,
): Hint? = when {
  fallbackEnabled && !listenerEnabled -> Hint(
    "备用通道已开启，需要「通知使用权」才能读系统曲目信息",
    "去开启",
    HintAction.OPEN_LISTENER_SETTINGS,
  )

  fallbackEnabled && listenerNotBoundLong -> Hint(
    "通知使用权看着已开启，但系统一直没绑定监听服务。可以到设置里把开关关掉、再打开一次。",
    "去设置",
    HintAction.OPEN_LISTENER_SETTINGS,
  )

  fallbackEnabled && !listenerConnected -> Hint(
    "通知使用权已开启，正在等待系统绑定监听服务…",
    "去设置",
    HintAction.OPEN_LISTENER_SETTINGS,
  )

  browserConnected -> when {
    !postNotificationsGranted -> Hint(
      "建议开启通知权限，否则捕获时看不到前台服务通知",
      "去开启",
      HintAction.REQUEST_NOTIFICATIONS,
    )

    capturePhase == CaptureController.Phase.IDLE ||
      capturePhase == CaptureController.Phase.STOPPED ||
      capturePhase == CaptureController.Phase.ERROR -> Hint(
      "尚未启用音频捕获",
      "启用音频捕获",
      HintAction.START_CAPTURE,
    )

    else -> null
  }

  browserStalled -> Hint(
    "正在等待「${TargetApp.LABEL}」的曲目信息。一直没反应的话，可到设置里打开备用通道。",
    "去设置",
    HintAction.OPEN_APP_SETTINGS,
  )

  !postNotificationsGranted -> Hint(
    "建议开启通知权限，否则捕获时看不到前台服务通知",
    "去开启",
    HintAction.REQUEST_NOTIFICATIONS,
  )

  capturePhase == CaptureController.Phase.IDLE ||
    capturePhase == CaptureController.Phase.STOPPED ||
    capturePhase == CaptureController.Phase.ERROR -> Hint(
    "尚未启用音频捕获",
    "启用音频捕获",
    HintAction.START_CAPTURE,
  )

  else -> null
}

@Composable
private fun HintBar(hint: Hint, modifier: Modifier, onAction: () -> Unit) {
  Row(
    modifier = modifier
      .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
      // 不铺满整行：右侧要留给右下角的设置按钮
      .widthIn(max = 900.dp)
      .background(Color.Black.copy(alpha = 0.55f))
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = hint.text,
      color = VizStyle.Fill,
      fontSize = 13.sp,
      modifier = Modifier.weight(1f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = onAction) {
      Text(hint.actionText, color = VizStyle.Fill, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** 「通知使用权已开但还没绑定」要等多久才提示用户手动干预 */
private const val BIND_WAIT_DELAY_MS = 5_000L

/** 底部提示条需要从布局里让出的高度（条本身 34dp + 间隔） */
private val HINT_RESERVED_HEIGHT = 56.dp
