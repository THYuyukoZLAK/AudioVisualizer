package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import top.aerohaku.androidapp.capture.AudioCaptureService
import top.aerohaku.androidapp.capture.AudioSource
import top.aerohaku.androidapp.capture.AudioSourceSettingsStore
import top.aerohaku.androidapp.capture.CaptureController
import top.aerohaku.androidapp.display.ScreenSettingsStore
import top.aerohaku.androidapp.lyrics.LyricsRepository
import top.aerohaku.androidapp.playback.TransportSettings
import top.aerohaku.androidapp.playback.TransportSettingsStore
import top.aerohaku.androidapp.theme.SettingsDrag
import top.aerohaku.androidapp.model.TargetApp
import top.aerohaku.androidapp.permission.Permissions
import top.aerohaku.androidapp.permission.ProjectionDisclosureStore
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
 *
 * 顺序上把「播放」放在最前：音频来源 / 捕获行为是首次使用就必须过的关卡，
 * 也是平时被打开最多的一页。
 */
private enum class SettingsTab(val label: String) {
  PLAYBACK("播放"),
  LAYOUT("版式"),
  APPEARANCE("外观"),
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

  AudioSourceSettingsStore.ensureLoaded(context)
  val audioSource by AudioSourceSettingsStore.source.collectAsStateWithLifecycle()

  // 有滑杆被按住 → 整页背景（连同所有卡片）一起透明，露出背后的可视化界面。
  // 正在调的那一项与数值由 SliderHud 全不透明地浮在最上层显示。
  val dragActive by SettingsDrag.active.collectAsStateWithLifecycle()

  // 设置页太长了，按功能分页；切页时滚动位置归零。
  // 默认落在「播放」—— 音频来源就在这一页，是首次使用必须过的关卡。
  var selectedTab by remember { mutableStateOf(SettingsTab.PLAYBACK) }
  val scrollState = rememberScrollState()

  // 「屏幕录制授权」说明：**只弹一次**，与首次启动那次共用同一个标志。
  //
  // ‼️ 判定必须落在持久化标志（ProjectionDisclosureStore）上，**不能用 remember**：
  // 设置页是浮层，关掉就从组合里移除，所有 remember 状态都会复位 ——
  // 用 remember 的话每次进设置都会再弹一次（已踩过这个坑）。
  var showDisclosure by remember { mutableStateOf(false) }
  LaunchedEffect(selectedTab) {
    scrollState.scrollTo(0)
    if (selectedTab == SettingsTab.PLAYBACK && ProjectionDisclosureStore.shouldAutoShow(context)) {
      showDisclosure = true
    }
  }

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
        // 音频来源：默认「屏幕录制捕获」—— 立体声 + 真实响度。
        // 「全局混音」授权更轻，但会丢掉响度、只剩单声道、弱音也被拉满（见 AudioSource）。
        // 两个选项的说明都**常显**：它们的取舍是这张卡片里最需要被看清的信息，
        // 只显示选中那一项的话用户根本无从比较。
        Text(
          text = "音频来源",
          color = ScexColors.Muted,
          style = MaterialTheme.typography.bodySmall,
        )
        AudioSource.entries.forEach { option ->
          AudioSourceRow(
            option = option,
            selected = audioSource == option,
            onClick = {
              AudioSourceSettingsStore.setSource(context, option)
              // 换了来源就得换引擎，把当前链路停掉让用户重新开始 ——
              // 否则会留着一条按**另一种**来源建起来的捕获通道
              AudioCaptureService.stop(context)
              CaptureController.setStatus(
                CaptureController.Status(
                  CaptureController.Phase.IDLE,
                  message = "已切换到「${option.label}」，请重新开始捕获",
                ),
              )
            },
          )
        }
        Hint("切换来源会停下当前捕获。\n屏幕录制授权是一次性的，重新启动应用需重新授权。")
        ScexGhostButton(
          text = "屏幕录制权限说明",
          onClick = { showDisclosure = true },
        )

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
        Hint("关闭自动启停后，程序运行时会持续捕获音频（更耗电）。")
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
        Hint("默认通过网易云对外开放的 MediaBrowserService 获取。")
        Row(verticalAlignment = Alignment.CenterVertically) {
          ScexSwitch(
            checked = metadataSettings.useNotificationFallback,
            onCheckedChange = { MetadataSettingsStore.setUseNotificationFallback(context, it) },
          )
          Spacer(Modifier.width(10.dp))
          Text(
            text = "读不到曲目信息时可改用系统级读取",
            color = ScexColors.Body,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Hint(
          "系统级读取需要通知使用权限。\n" +
            "这是敏感权限，一般不需要开启，作为默认元数据来源无法使用时的备份。\n" +
            "当然，本程序不会读取并存储/上传您的任何通知信息，" +
            "该功能仅用于读取正在播放的音乐元数据。\n" +
            "您可以通过审查本程序源码确认。",
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

      if (selectedTab == SettingsTab.DEBUG) {
        DiagnosticsCard("播放状态诊断（最近 ${playbackDiagnostics.size} 条）", playbackDiagnostics)
        DiagnosticsCard("歌词抓取诊断（最近 ${lyricsDiagnostics.size} 条）", lyricsDiagnostics)
      }

      if (selectedTab == SettingsTab.ABOUT) AboutCard()
    }

    // 权限说明弹窗。自成一层，所以放在 Column **外面** ——
    // 它不该跟着设置页一起滚，也不该在拖动滑杆时被兑得半透明（那是 SliderHud 的待遇）。
    // 关掉时写「已读」标志，以后不再自动弹（卡片里那个按钮还能随时重看）。
    if (showDisclosure) {
      ProjectionDisclosureDialog(
        onClose = {
          ProjectionDisclosureStore.markShown(context)
          showDisclosure = false
        },
      )
    }
  }
}

@Composable
private fun AudioSourceRow(option: AudioSource, selected: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .border(1.dp, if (selected) ScexColors.AccentForm else ScexColors.Border)
      .background(
        if (selected) ScexColors.AccentForm.copy(alpha = 0.15f) else ScexColors.Background,
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = if (selected) "▸ ${option.label}" else option.label,
      color = if (selected) ScexColors.Heading else ScexColors.Body,
      style = MaterialTheme.typography.bodyMedium,
    )
    // 说明常显（含未选中的那一项），方便直接把两者对比着看
    Hint(option.note)
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
