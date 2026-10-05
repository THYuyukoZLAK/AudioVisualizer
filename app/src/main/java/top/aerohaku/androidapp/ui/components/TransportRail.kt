package top.aerohaku.androidapp.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.aerohaku.androidapp.playback.NowPlayingState
import top.aerohaku.androidapp.playback.QueueEntry
import top.aerohaku.androidapp.playback.TransportController
import top.aerohaku.androidapp.playback.TransportSettings
import top.aerohaku.androidapp.playback.TransportSettingsStore
import top.aerohaku.androidapp.theme.JetBrainsMono
import top.aerohaku.androidapp.theme.ScexColors

/**
 * 右侧播放控制栏：**播放列表 / 上一首 / 播放·暂停 / 下一首**，自上到下。
 *
 * ## 这些控制从哪来
 *
 * 全部走网易云自己对外开放的 `MediaBrowserService`（`ucar.UCarService`）——
 * 连上之后拿到的 `MediaController` 上就有 `TransportControls`。
 * 2026-10-05 真机实测它声明了 `actions=0x336`（PLAY / PAUSE / PLAY_PAUSE / SKIP_NEXT /
 * SKIP_PREV / SEEK），播放队列 `getQueue()` 能直接读出 13 项，四项控制逐个按过都生效。
 *
 * （曾经还有一个「循环模式」按钮，已删 —— 平台没有 `setRepeatMode`，
 * 只能靠网易云的私有 action 盲切，读不到当前模式，摆在那儿只会让人困惑。）
 *
 * ## 图标为什么带一圈黑晕
 *
 * 按要求去掉了按钮的圆形背景，但背景是**用户自己的专辑封面**，亮度完全不可控 ——
 * 纯白细线压在浅色封面上会直接看不见。所以每个图标画两遍：
 * 先描一层加粗的黑色半透明，再压白色细线。
 * 和文字的 [top.aerohaku.androidapp.ui.components.VizStyle.TextShadow] 是同一个理由 ——
 * 这不是「加背景」，只是留一点对比度余量。
 *
 * ## 自动隐藏
 *
 * 页面上超过 [TransportSettings.autoHideSeconds] 秒没人碰，就把控制栏淡出去；
 * 之后任何一次触摸都会让它回来。时长在设置页可改，拖到 0 就是不自动隐藏。
 */
@Composable
fun TransportRail(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val available by TransportController.available.collectAsStateWithLifecycle()
  val queue by TransportController.queue.collectAsStateWithLifecycle()
  val nowPlaying by NowPlayingState.nowPlaying.collectAsStateWithLifecycle()

  TransportSettingsStore.ensureLoaded(context)
  val settings by TransportSettingsStore.state.collectAsStateWithLifecycle()

  var showQueue by remember { mutableStateOf(false) }
  var railVisible by remember { mutableStateOf(true) }
  var lastInteraction by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

  val autoHideMs = settings.autoHideSeconds * 1_000L

  // 看门狗：隔一会儿检查一次「上次有人碰屏幕是多久前」
  LaunchedEffect(autoHideMs, showQueue) {
    while (true) {
      delay(IDLE_CHECK_INTERVAL_MS)
      val idle = SystemClock.elapsedRealtime() - lastInteraction
      // 列表开着的时候不隐藏 —— 那会把用户正在看的东西抽走
      if (autoHideMs > 0L && !showQueue && idle >= autoHideMs) railVisible = false
    }
  }

  // 会话没连上就什么都不摆 —— 一排按不出反应的按钮比不摆更糟
  if (!available) return

  Box(
    modifier
      .fillMaxSize()
      // 旁听整页的触摸：pointerInput 不 consume，所以下面那些部件的点击照常生效，
      // 这里只是知道「有人在用」。
      //
      // ⚠️ 用 PointerEventPass.Initial 是要在子节点之前拿到事件；
      // **一定不能调 consume()**，否则右下角齿轮和所有部件都点不动了。
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            lastInteraction = SystemClock.elapsedRealtime()
            railVisible = true
          }
        }
      },
  ) {
    AnimatedVisibility(
      visible = railVisible,
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = RAIL_EDGE_PADDING),
      enter = fadeIn(tween(RAIL_ANIM_MS)) + slideInHorizontally(tween(RAIL_ANIM_MS)) { it / 2 },
      exit = fadeOut(tween(RAIL_ANIM_MS)) + slideOutHorizontally(tween(RAIL_ANIM_MS)) { it / 2 },
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(RAIL_SPACING)) {
        RailButton(
          description = "播放列表",
          onClick = { showQueue = !showQueue },
        ) { RailIcon(TransportIcon.Queue) }

        RailButton(description = "上一首", onClick = { TransportController.previous() }) {
          RailIcon(TransportIcon.Previous)
        }

        val playing = nowPlaying?.isPlaying == true
        RailButton(
          description = if (playing) "暂停" else "播放",
          onClick = {
            if (playing) TransportController.pause() else TransportController.play()
          },
        ) { RailIcon(if (playing) TransportIcon.Pause else TransportIcon.Play) }

        RailButton(description = "下一首", onClick = { TransportController.next() }) {
          RailIcon(TransportIcon.Next)
        }
      }
    }

    AnimatedVisibility(
      visible = showQueue,
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = RAIL_EDGE_PADDING + RAIL_BUTTON_SIZE + CARD_GAP),
      enter = fadeIn(tween(CARD_ANIM_MS)) + slideInHorizontally(tween(CARD_ANIM_MS)) { it / 3 },
      exit = fadeOut(tween(CARD_ANIM_MS)) + slideOutHorizontally(tween(CARD_ANIM_MS)) { it / 3 },
    ) {
      QueueCard(
        queue = queue,
        onSelect = { entry ->
          TransportController.jumpTo(entry.queueId)
          showQueue = false
        },
        onClose = { showQueue = false },
      )
    }
  }
}

/**
 * 播放列表卡片。
 *
 * 刻意**不占满屏**：宽度固定，高度取「内容高度」与「屏幕高度的 [QUEUE_CARD_MAX_HEIGHT_RATIO]」
 * 的较小值，超出就在卡片内部滚动（`LazyColumn`）。
 * 不铺遮罩 —— 卡片是浮在画面上的，别把整个界面挡住。
 */
@Composable
private fun QueueCard(
  queue: List<QueueEntry>,
  onSelect: (QueueEntry) -> Unit,
  onClose: () -> Unit,
) {
  val maxHeight = (LocalConfiguration.current.screenHeightDp * QUEUE_CARD_MAX_HEIGHT_RATIO).dp

  Column(
    modifier = Modifier
      .width(QUEUE_CARD_WIDTH)
      .heightIn(max = maxHeight)
      .background(Color.Black.copy(alpha = 0.82f))
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "播放列表",
        color = ScexColors.Heading,
        fontFamily = JetBrainsMono,
        fontSize = 13.sp,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${queue.size} 项",
        color = ScexColors.Muted,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
      )
      Spacer(Modifier.width(10.dp))
      Text(
        text = "✕",
        color = ScexColors.Body,
        fontSize = 15.sp,
        modifier = Modifier
          .clickable(onClick = onClose)
          .padding(horizontal = 6.dp)
          .semantics { contentDescription = "关闭播放列表" },
      )
    }

    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(ScexColors.AccentForm))
    Spacer(Modifier.height(4.dp))

    if (queue.isEmpty()) {
      Text(
        text = "读不到播放队列（需要 Android 11+，且网易云得把队列推给会话）",
        color = ScexColors.Body,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp),
      )
    } else {
      LazyColumn(
        // ⚠️ weight(1f, fill = false)：内容少时按内容高，内容多时被 heightIn 卡住并内部滚动
        modifier = Modifier.weight(1f, fill = false),
      ) {
        itemsIndexed(queue) { index, entry ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(entry) }
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "${index + 1}",
              color = if (entry.isCurrent) VizStyle.Fill else ScexColors.Muted,
              fontFamily = JetBrainsMono,
              fontSize = 12.sp,
              modifier = Modifier.width(26.dp),
            )
            Column(Modifier.weight(1f)) {
              Text(
                text = entry.title.ifBlank { "（无标题）" },
                color = if (entry.isCurrent) VizStyle.Fill else ScexColors.Body,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              val artist = entry.artist?.takeIf { it.isNotBlank() }
              if (artist != null) {
                Text(
                  text = artist,
                  color = ScexColors.Muted,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            // 正在播的那一项标一下（平台 API 不告诉咱当前是第几项，只能按标题猜）
            if (entry.isCurrent) {
              Text("▶", color = VizStyle.Fill, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

/**
 * 一个按钮位。**没有背景** —— 只有图标本身，触摸区域仍是 [RAIL_BUTTON_SIZE]。
 */
@Composable
private fun RailButton(
  description: String,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(RAIL_BUTTON_SIZE)
      .clickable(onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun RailIcon(icon: TransportIcon) {
  Canvas(Modifier.size(RAIL_ICON_SIZE)) {
    // 先描一层加粗黑晕，再压白色细线（理由见文件头注释）
    val base = size.minDimension * STROKE_RATIO
    drawIcon(icon, Color.Black.copy(alpha = HALO_ALPHA), base + size.minDimension * HALO_RATIO)
    drawIcon(icon, VizStyle.Fill, base)
  }
}

private enum class TransportIcon { Queue, Previous, Play, Pause, Next }

// ------------------------------------------------------------------ 空心图标
//
// 全部描边、不填充，风格与 GearIcon 一致。项目刻意不引 material-icons-extended
// （十几 MB，而 core 包里只有实心图标）。

private fun DrawScope.drawIcon(icon: TransportIcon, color: Color, strokeWidth: Float) {
  when (icon) {
    TransportIcon.Queue -> drawQueueIcon(color, strokeWidth)
    TransportIcon.Previous -> drawSkipIcon(color, strokeWidth, toLeft = true)
    TransportIcon.Play -> drawPlayIcon(color, strokeWidth)
    TransportIcon.Pause -> drawPauseIcon(color, strokeWidth)
    TransportIcon.Next -> drawSkipIcon(color, strokeWidth, toLeft = false)
  }
}

private fun DrawScope.strokeOf(width: Float) =
  Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 播放列表：左侧三个空心小圆点 + 三条横线 */
private fun DrawScope.drawQueueIcon(color: Color, strokeWidth: Float) {
  val w = size.width
  val h = size.height
  val dotRadius = h * 0.055f
  listOf(0.24f, 0.5f, 0.76f).forEach { fraction ->
    val y = h * fraction
    drawCircle(
      color = color,
      radius = dotRadius,
      center = Offset(w * 0.14f, y),
      style = strokeOf(strokeWidth),
    )
    drawLine(
      color = color,
      start = Offset(w * 0.36f, y),
      end = Offset(w * 0.90f, y),
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round,
    )
  }
}

/** 上一首 / 下一首：一个空心三角 + 一条空心竖杠 */
private fun DrawScope.drawSkipIcon(color: Color, strokeWidth: Float, toLeft: Boolean) {
  val w = size.width
  val h = size.height
  val barWidth = w * 0.13f
  val barLeft = if (toLeft) w * 0.12f else w * 0.88f - barWidth
  drawRect(
    color = color,
    topLeft = Offset(barLeft, h * 0.20f),
    size = Size(barWidth, h * 0.60f),
    style = strokeOf(strokeWidth),
  )

  val tipX = if (toLeft) w * 0.30f else w * 0.70f
  val baseX = if (toLeft) w * 0.84f else w * 0.16f
  drawPath(
    path = Path().apply {
      moveTo(tipX, h * 0.5f)
      lineTo(baseX, h * 0.20f)
      lineTo(baseX, h * 0.80f)
      close()
    },
    color = color,
    style = strokeOf(strokeWidth),
  )
}

/** 播放：空心右三角 */
private fun DrawScope.drawPlayIcon(color: Color, strokeWidth: Float) {
  val w = size.width
  val h = size.height
  drawPath(
    path = Path().apply {
      moveTo(w * 0.28f, h * 0.18f)
      lineTo(w * 0.82f, h * 0.5f)
      lineTo(w * 0.28f, h * 0.82f)
      close()
    },
    color = color,
    style = strokeOf(strokeWidth),
  )
}

/** 暂停：两条空心竖杠 */
private fun DrawScope.drawPauseIcon(color: Color, strokeWidth: Float) {
  val w = size.width
  val h = size.height
  val barWidth = w * 0.16f
  drawRect(
    color = color,
    topLeft = Offset(w * 0.26f, h * 0.18f),
    size = Size(barWidth, h * 0.64f),
    style = strokeOf(strokeWidth),
  )
  drawRect(
    color = color,
    topLeft = Offset(w * 0.58f, h * 0.18f),
    size = Size(barWidth, h * 0.64f),
    style = strokeOf(strokeWidth),
  )
}

private val RAIL_BUTTON_SIZE = 48.dp
private val RAIL_ICON_SIZE = 26.dp
private val RAIL_SPACING = 4.dp
private val RAIL_EDGE_PADDING = 10.dp
private val CARD_GAP = 10.dp
private val QUEUE_CARD_WIDTH = 300.dp

/** 卡片最高占屏幕高度的比例 —— 「不要占满全屏」 */
private const val QUEUE_CARD_MAX_HEIGHT_RATIO = 0.62f

private const val STROKE_RATIO = 0.085f
private const val HALO_RATIO = 0.075f
private const val HALO_ALPHA = 0.45f

private const val RAIL_ANIM_MS = 240
private const val CARD_ANIM_MS = 220

/** 看门狗轮询间隔。不必太勤，反正只是个超时判断 */
private const val IDLE_CHECK_INTERVAL_MS = 400L
