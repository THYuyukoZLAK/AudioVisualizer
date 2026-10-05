package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aerohaku.androidapp.capture.CaptureController
import top.aerohaku.androidapp.display.ScreenSettingsStore
import top.aerohaku.androidapp.lyrics.LyricsRepository
import top.aerohaku.androidapp.playback.TransportSettings
import top.aerohaku.androidapp.playback.TransportSettingsStore
import top.aerohaku.androidapp.theme.SettingsDrag
import top.aerohaku.androidapp.model.TargetApp
import top.aerohaku.androidapp.permission.Permissions
import top.aerohaku.androidapp.playback.MetadataSettingsStore
import top.aerohaku.androidapp.playback.NowPlayingState
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexStatRow
import top.aerohaku.androidapp.theme.ScexSwitch
import top.aerohaku.androidapp.theme.ScexTextButton
import top.aerohaku.androidapp.theme.ScexTheme

/**
 * 设置与诊断页。
 *
 * 样式套的是 **SCEX 深蓝青色「航空仪表」主题**（`scex-webstyle` skill）：
 * 深蓝黑底 + 青色强调 + 全站 JetBrains Mono 等宽 + 直角 + 卡片左竖条。
 * 具体色值与纪律见 `theme/ScexTheme.kt`。
 */
/**
 * 设置页的分页。
 *
 * 分页不只是「好看」：设置项多了以后一页得滑很久，
 * 而且拖动滑杆时想边调边看背后的可视化，页面一长就耍不开。
 *
 * 分组按**功能**而不是「控件类型」——比如「接口」放的全是跟网易云/系统接口打交道的项。
 */
private enum class SettingsTab(val label: String) {
  LAYOUT("版式"),
  APPEARANCE("外观"),
  PLAYBACK("播放"),
  INTERFACE("接口"),
  DEBUG("调试"),
  ABOUT("关于"),
}

/** 顶部分页标签栏。直角 + 青色高亮，跟 SCEX 主题一致 */
@Composable
private fun SettingsTabs(
  tabs: List<SettingsTab>,
  selected: SettingsTab,
  onSelect: (SettingsTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    tabs.forEach { tab ->
      val active = tab == selected
      Text(
        text = tab.label,
        color = if (active) ScexColors.Background else ScexColors.Body,
        fontSize = 14.sp,
        modifier = Modifier
          .clickable { onSelect(tab) }
          .background(if (active) ScexColors.AccentForm else ScexColors.Panel)
          .padding(horizontal = 18.dp, vertical = 8.dp),
      )
    }
  }
}

/** 拖滑杆时整页背景的不透明度 —— 要能看清背后的可视化，又不能完全失去层次 */
private const val DRAGGING_PAGE_ALPHA = 0.10f

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val autoStartStop by CaptureController.autoStartStop.collectAsStateWithLifecycle()
  val captureStatus by CaptureController.status.collectAsStateWithLifecycle()
  val nowPlaying by NowPlayingState.nowPlaying.collectAsStateWithLifecycle()
  val listenerConnected by NowPlayingState.listenerConnected.collectAsStateWithLifecycle()
  val browserConnected by NowPlayingState.browserConnected.collectAsStateWithLifecycle()
  val metadataSource by NowPlayingState.source.collectAsStateWithLifecycle()
  val playbackDiagnostics by NowPlayingState.diagnostics.collectAsStateWithLifecycle()
  val lyricsDiagnostics by LyricsRepository.diagnostics.collectAsStateWithLifecycle()

  MetadataSettingsStore.ensureLoaded(context)
  val metadataSettings by MetadataSettingsStore.state.collectAsStateWithLifecycle()

  ScreenSettingsStore.ensureLoaded(context)
  val screenSettings by ScreenSettingsStore.state.collectAsStateWithLifecycle()

  TransportSettingsStore.ensureLoaded(context)
  val transportSettings by TransportSettingsStore.state.collectAsStateWithLifecycle()

  // 有滑杆被按住 → 整页背景（连同所有卡片）一起透明，露出背后的可视化界面。
  // 正在调的那一项与数值由 SliderHud 全不透明地浮在最上层显示。
  val dragActive by SettingsDrag.active.collectAsStateWithLifecycle()

  // 设置页太长了，按功能分页；切页时滚动位置归零
  var selectedTab by remember { mutableStateOf(SettingsTab.LAYOUT) }
  val scrollState = rememberScrollState()
  LaunchedEffect(selectedTab) { scrollState.scrollTo(0) }

  var listenerEnabled by remember { mutableStateOf(Permissions.isNotificationListenerEnabled(context)) }
  var postNotifications by remember { mutableStateOf(Permissions.isPostNotificationsGranted(context)) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        listenerEnabled = Permissions.isNotificationListenerEnabled(context)
        postNotifications = Permissions.isPostNotificationsGranted(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  ScexTheme {
    Column(
      modifier
        .fillMaxSize()
        .background(
          ScexColors.Background.copy(
            alpha = if (dragActive != null) DRAGGING_PAGE_ALPHA else 1f,
          ),
        )
        .safeDrawingPadding()
        .verticalScroll(scrollState)
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          // 拖滑杆时标题栏也一起淡，和卡片保持一致
          .graphicsLayer { alpha = if (dragActive != null) DRAGGING_PAGE_ALPHA else 1f },
      ) {
        ScexTextButton("← 返回", onClick = onBack)
        Spacer(Modifier.width(8.dp))
        Text(
          text = "设置与诊断",
          color = ScexColors.Heading,
          style = MaterialTheme.typography.titleMedium,
        )
      }

      SettingsTabs(
        tabs = SettingsTab.entries,
        selected = selectedTab,
        onSelect = { selectedTab = it },
        modifier = Modifier.graphicsLayer {
          alpha = if (dragActive != null) DRAGGING_PAGE_ALPHA else 1f
        },
      )

      if (selectedTab == SettingsTab.LAYOUT) PresetCard()
      if (selectedTab == SettingsTab.APPEARANCE) AppearanceCard()
      if (selectedTab == SettingsTab.APPEARANCE) ParticlesCard()

      if (selectedTab == SettingsTab.PLAYBACK) ScexCard(title = "捕获行为") {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ScexSwitch(
            checked = autoStartStop,
            onCheckedChange = { CaptureController.setAutoStartStop(it) },
          )
          Spacer(Modifier.width(10.dp))
          Text(
            text = if (autoStartStop) "跟随播放自动启停（推荐）" else "手动模式（启用后持续捕获）",
            color = ScexColors.Body,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Hint("关闭自动启停后，只要服务在运行就会一直读音频（更耗电）。")
      }

      if (selectedTab == SettingsTab.APPEARANCE) ScexCard(title = "屏幕") {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ScexSwitch(
            checked = screenSettings.keepScreenOn,
            onCheckedChange = { ScreenSettingsStore.setKeepScreenOn(context, it) },
          )
          Spacer(Modifier.width(10.dp))
          Text(
            text = if (screenSettings.keepScreenOn) "使用期间保持屏幕常亮" else "跟随系统煈屏时间",
            color = ScexColors.Body,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Hint("开着可视化却让屏幕自己煈掉没意义。关掉后按系统设定的煈屏时间来。")
      }

      if (selectedTab == SettingsTab.PLAYBACK) ScexCard(title = "播放控制") {
        FloatSlider(
          label = "无操作后自动隐藏控制栏",
          value = transportSettings.autoHideSeconds.toFloat(),
          range = TransportSettings.MIN_AUTO_HIDE_SECONDS.toFloat()..
            TransportSettings.MAX_AUTO_HIDE_SECONDS.toFloat(),
          display = TransportSettings.formatAutoHide(transportSettings.autoHideSeconds),
          onChange = { value ->
            val step = TransportSettings.AUTO_HIDE_STEP
            val stepped = (Math.round(value) / step) * step
            TransportSettingsStore.setAutoHideSeconds(context, stepped)
          },
        )
        Hint(
          "右侧那排（播放列表 / 上一首 / 播放暂停 / 下一首）太久不动就淡出去，" +
            "碰一下屏幕再回来。拖到最左边（0s）就是不自动隐藏。",
        )
      }

      if (selectedTab == SettingsTab.LAYOUT) WidgetLayoutCard()

      if (selectedTab == SettingsTab.INTERFACE) ScexCard(title = "元数据来源") {
        ScexStatRow("当前来源", metadataSource.label)
        ScexStatRow("MediaBrowser 已连接", browserConnected.toString())
        Hint(
          "默认直接连网易云对外开放的 MediaBrowserService（车机互联接口），" +
            "标题 / 歌手 / 专辑 / 封面 / 进度都能拿到，不需要任何敏感授权。",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          ScexSwitch(
            checked = metadataSettings.useNotificationFallback,
            onCheckedChange = { MetadataSettingsStore.setUseNotificationFallback(context, it) },
          )
          Spacer(Modifier.width(10.dp))
          Text(
            text = "读不到曲目信息时改用系统级读取",
            color = ScexColors.Body,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Hint(
          "系统级读取靠「通知使用权」，它能读所有应用的通知，系统会一直弹安全警告。" +
            "只有网易云版本偏老、没开放上面的接口时才需要打开。",
        )
        if (metadataSettings.useNotificationFallback) {
          ScexGhostButton(
            text = "打开通知使用权设置",
            onClick = { Permissions.openNotificationListenerSettings(context) },
          )
        }
      }

      if (selectedTab == SettingsTab.INTERFACE) ScexCard(title = "权限") {
        ScexStatRow("通知使用权（备用通道）", listenerEnabled.toString())
        ScexStatRow("备用通道已绑定", listenerConnected.toString())
        ScexStatRow("通知权限", postNotifications.toString())
        ScexStatRow("捕获状态", "${captureStatus.phase} ${captureStatus.format}")
      }

      if (selectedTab == SettingsTab.INTERFACE) ScexCard(title = "目标应用") {
        ScexStatRow("包名", TargetApp.PACKAGE)
        ScexStatRow("当前会话", nowPlaying?.let { "${it.title} / ${it.artist}" } ?: "无")
        ScexStatRow("songId（查歌词用）", nowPlaying?.songId ?: "无")
        ScexStatRow("mediaId 原始值", nowPlaying?.mediaId ?: "无")
        ScexStatRow("MediaSession 进度", "${nowPlaying?.positionMs ?: 0} ms")
      }

      // 实验性：判断能否用 Visualizer 取代 MediaProjection，从而免掉屏幕捕获授权
      if (selectedTab == SettingsTab.INTERFACE) AudioProbeCard()

      if (selectedTab == SettingsTab.DEBUG) {
        DiagnosticsCard("播放状态诊断（最近 ${playbackDiagnostics.size} 条）", playbackDiagnostics)
        DiagnosticsCard("歌词抓取诊断（最近 ${lyricsDiagnostics.size} 条）", lyricsDiagnostics)
      }

      if (selectedTab == SettingsTab.ABOUT) AboutCard()
    }
  }
}

@Composable
private fun DiagnosticsCard(title: String, lines: List<String>) {
  ScexCard(title = title) {
    if (lines.isEmpty()) {
      Hint("（暂无）")
    } else {
      // 最新的在上面；等宽小字，读起来像终端输出
      lines.asReversed().forEach { line ->
        Text(
          text = line,
          color = ScexColors.Body,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}
