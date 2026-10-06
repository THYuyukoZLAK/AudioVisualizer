package top.aerohaku.androidapp.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.aerohaku.androidapp.R
import kotlin.math.abs

/**
 * **SCEX 深蓝青色「航空仪表」主题**（来自 `scex-webstyle` skill）。
 *
 * 关键纪律（照搬 skill 的「配色 / 字体 / 形态」三条）：
 * - 背景 `#022335`、面板 `#093750`、强调 `#1985a1`（表单/工具）或 `#03a4fb`（链接）
 * - 亮文本 `#d2f7fd`、正文 `#abc0ca`、弱化 `#828282`、边框 `#0f5377`、行分隔 `#063145`
 * - 不引入其他高饱和色
 * - 全站等宽字体；**优先直角**（`border-radius: 0`）
 * - 卡片**左侧 4px 竖条仅单独卡片使用**：并列时只留最左一张、正文容器不用、嵌套不用
 * - 尽量不要使用图标，用文字/配色表达
 */
object ScexColors {
  val Background = Color(0xFF022335)
  val Panel = Color(0xFF093750)
  val Accent = Color(0xFF03A4FB)
  val AccentForm = Color(0xFF1985A1)
  val AccentHover = Color(0xFF1A9BBE)
  val Heading = Color(0xFFD2F7FD)
  val Body = Color(0xFFABC0CA)
  val Muted = Color(0xFF828282)
  val Border = Color(0xFF0F5377)
  val RowSeparator = Color(0xFF063145)
  val RowHover = Color(0xFF0A3F5C)
  val Success = Color(0xFF00D68A)
  val Warning = Color(0xFFFFC107)
  val Danger = Color(0xFFDC3545)
  val Info = Color(0xFF0DCAF0)
  val CodeBackground = Color(0xFF000000)
  val CodeText = Color(0xFFFFFFFF)
  val Link = Color(0xFF68C8FD)

  /** 卡片左竖条宽度 */
  val LeftBar = 4.dp
}

/**
 * 全站等宽字体。
 *
 * 打包了 **JetBrains Mono**（Regular / Bold）而不是直接用 `FontFamily.Monospace` ——
 * skill 的「字体纪律」明确要求以 JetBrains Mono 打头。
 * 它没有中文字形，中文会自动回退到系统字体，这与网页端的回退栈行为一致。
 */
val JetBrainsMono = FontFamily(
  Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
  Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val ScexTypography = Typography(
  titleLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
  titleMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
  titleSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp),
  bodyLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
  bodyMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
  bodySmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
  labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
)

private val ScexColorScheme = darkColorScheme(
  primary = ScexColors.AccentForm,
  onPrimary = ScexColors.Background,
  secondary = ScexColors.Accent,
  onSecondary = ScexColors.Background,
  tertiary = ScexColors.Info,
  background = ScexColors.Background,
  onBackground = ScexColors.Body,
  surface = ScexColors.Panel,
  onSurface = ScexColors.Heading,
  surfaceVariant = ScexColors.Background,
  onSurfaceVariant = ScexColors.Muted,
  outline = ScexColors.Border,
  outlineVariant = ScexColors.RowSeparator,
  error = ScexColors.Danger,
  onError = ScexColors.Background,
)

@Composable
fun ScexTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = ScexColorScheme, typography = ScexTypography, content = content)
}

// ------------------------------------------------------------------ 组件

/**
 * 面板卡片：面板底色 + 左侧 4px 青色竖条 + 直角。
 *
 * ⚠️ 竖条按 skill 的规定**仅单独卡片使用**。设置页里所有卡片都是竖直排列的
 * （不存在「同一行并排」的情形），所以每张都带竖条是正确的。
 */
/**
 * 当前正在渲染的卡片 id。
 *
 * [ScexCard] 提供、[ScexSlider] 消费 —— 滑杆因此能自动知道自己在哪张卡片里，
 * 拖动时上报给 [SettingsDrag]，让**其余卡片**变透明。
 * 用 CompositionLocal 传就不必在每个调用点手写一遍键，少一处漏写就少一个「拖了没反应」的 bug。
 */
private val LocalCardId = staticCompositionLocalOf<String?> { null }

// 只在主线程的 composition 里用，不需要额外同步

/**
 * 「按住不动」要等多久才确认「真的是要拖滑杆」。
 *
 * 不能一见按下就亮 —— 设置页可竖向滚动，手指从滑杆上起手滚页面时也会是个「按下」。
 * 这段时间里只要转成竖向滚动就会被放弃，所以用户感知不到延迟。
 */
private const val HOLD_TO_DRAG_MS = 140L
private var cardIdCounter = 0

/** 拖动滑杆时「其它卡片」降到这个不透明度 —— 要能一眼看见背后的可视化界面 */
private const val DIMMED_CARD_ALPHA = 0.10f

/**
 * 面板卡片：面板底色 + 直角，可选左侧 4px 青色竖条。
 *
 * 拖动本卡片内的滑杆时，**其它卡片会一起变透明**（自己保持不透明），
 * 方便边调边看背后可视化界面的变化。
 *
 * ⚠️ 透明度只能在这一层施加。**不能**改成「给整页加 alpha」—— alpha 是**相乘**的，
 * 那样被拖动的卡片自己也会跟着淡，再也没法比父级更不透明。
 *
 * @param showLeftBar 是否画左侧那条 4px 青色竖条。
 *   **默认不画**：skill 里竖条的规矩本来是「仅单独卡片使用」，
 *   但设置页有十来张卡片竖直排开，张张带竖条会把视线切得很碎。
 *   现在只有「关于」单独要它（见 `AboutCard`）。
 */
@Composable
fun ScexCard(
  title: String? = null,
  showLeftBar: Boolean = false,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val cardId = remember { "scex-card-${cardIdCounter++}" }
  val drag by SettingsDrag.active.collectAsStateWithLifecycle()
  // 拖任意滑杆时**所有**卡片一起淡，只留浮层 HUD 全不透明。
  // 为什么不把「被拖的那张」留亮：alpha 沿父链相乘，卡内的滑杆没法单独亮回来，
  // 做不到「只保留被拖的那一根」，详见 SettingsDrag 的注释。
  val cardAlpha = if (drag == null) 1f else DIMMED_CARD_ALPHA

  CompositionLocalProvider(LocalCardId provides cardId) {
    Column(
      modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = cardAlpha }
        .drawBehind {
          drawRect(ScexColors.Panel)
          if (showLeftBar) {
            drawRect(ScexColors.AccentForm, size = Size(ScexColors.LeftBar.toPx(), size.height))
          }
        }
        .padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (title != null) {
        Text(title, color = ScexColors.Heading, style = MaterialTheme.typography.titleSmall)
        // skill：卡片标题下划线 1px #0f5377
        Box(
          Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ScexColors.Border),
        )
      }
      content()
    }
  }
}

/** 主按钮：青色填充 + 深色文字 + 无圆角 */
@Composable
fun ScexPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .background(ScexColors.AccentForm)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 9.dp),
  ) {
    Text(text, color = ScexColors.Background, fontSize = 14.sp, fontWeight = FontWeight.Bold)
  }
}

/**
 * 幽灵按钮：深底 + 边框，hover/按下才提亮。
 *
 * [icon] 是可选的**前置小图标**（默认没有）。做成参数而不是另开一个 composable：
 * 所有按钮共用同一套内边距、边框与字号，拄一份出来迟早会走样。
 */
@Composable
fun ScexGhostButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: (@Composable () -> Unit)? = null,
) {
  Box(
    modifier
      .border(1.dp, ScexColors.Border)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 9.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      icon?.invoke()
      Text(text, color = ScexColors.Body, fontSize = 14.sp)
    }
  }
}

/** 文本按钮（返回、收起等次要操作） */
@Composable
fun ScexTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text, color = ScexColors.Link, fontSize = 14.sp)
  }
}

@Composable
fun ScexSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    colors = SwitchDefaults.colors(
      checkedThumbColor = ScexColors.Background,
      checkedTrackColor = ScexColors.AccentForm,
      checkedBorderColor = ScexColors.AccentForm,
      uncheckedThumbColor = ScexColors.Muted,
      uncheckedTrackColor = ScexColors.Panel,
      uncheckedBorderColor = ScexColors.Border,
    ),
  )
}

@Composable
fun ScexSlider(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  onValueChangeFinished: (() -> Unit)? = null,
  dragLabel: String? = null,
) {
  // 所在卡片的 id（由 ScexCard 通过 CompositionLocal 提供）
  val cardId = LocalCardId.current
  val latestValue by rememberUpdatedState(value)

  // 「按下还没定性」的状态：用来延迟确认「按住不动」，避开滚动起手
  var pendingPress by remember { mutableStateOf(false) }

  // ⚠️ 判断「按下」**不能**靠 Material3 Slider 的 interactionSource ——
  // 真机日志实测：这个版本按住滑杆时**一个 interaction 都不发**
  //（连 DragInteraction 都没有），于是「按住不动」完全没反应。
  // 只能自己旁听指针事件：在 Initial 阶段拿、**绝不 consume**，
  // 所以滑块自己的手势照常工作。
  //
  // ⚠️ 但**不能一见按下就亮**：设置页是可竖向滚动的，手指恰好从滑杆上起手准备滚页面时
  // 也会是一个「按下」（用户实测反馈过）。所以要先判方向：
  //   竖向超过 slop → 判定为滚页面，放弃
  //   横向超过 slop → 真的在拖滑杆，立刻亮
  //   一直不动       → 等 [HOLD_TO_DRAG_MS] 确认不是滚动起手，再亮
  val dragObserver = Modifier.pointerInput(cardId, dragLabel, range) {
    awaitPointerEventScope {
      // 「本次手势」的状态机。⚠️ 不能用 downAt 是否为 null 来表示「没在按」——
      // 一旦判定为滚动就把 downAt 清空的话，后续的 MOVE 会被当成**新的一次按下**，
      // 于是滚页面滚到一半又亮起来（实测踩过）。
      var pressed = false
      var abandoned = false
      var downAt: Offset? = null

      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull() ?: continue

        if (!change.pressed) {
          // 抬起：收尾
          if (pressed) {
            pressed = false
            abandoned = false
            downAt = null
            pendingPress = false
            SettingsDrag.end()
          }
          continue
        }

        if (!pressed) {
          // 新的一次按下
          pressed = true
          abandoned = false
          downAt = change.position
          pendingPress = true
          continue
        }

        // 已经按着了：判方向。已放弃的手势不再理会
        if (abandoned) continue
        val start = downAt ?: continue
        val dx = change.position.x - start.x
        val dy = change.position.y - start.y
        val slop = viewConfiguration.touchSlop

        if (abs(dy) > slop && abs(dy) > abs(dx)) {
          // 竖向 → 用户在滚页面，别插手，本次手势彻底放弃
          abandoned = true
          pendingPress = false
          SettingsDrag.end()
        } else if (abs(dx) > slop) {
          // 横向 → 真的在拖滑杆
          pendingPress = false
          if (cardId != null && SettingsDrag.active.value == null) {
            SettingsDrag.begin(cardId, dragLabel, latestValue, range)
          }
        }
      }
    }
  }

  // 「按住不动」的确认：过了这个时间还没转成滚动，就亮起来
  LaunchedEffect(pendingPress) {
    if (!pendingPress) return@LaunchedEffect
    delay(HOLD_TO_DRAG_MS)
    if (pendingPress && cardId != null) {
      pendingPress = false
      SettingsDrag.begin(cardId, dragLabel, latestValue, range)
    }
  }

  Slider(
    value = value.coerceIn(range.start, range.endInclusive),
    onValueChange = { newValue ->
      if (cardId != null) {
        // 数值都变了，那肯定是滑杆接管了手势，不用再等 HOLD 确认
        pendingPress = false
        if (SettingsDrag.active.value == null) {
          SettingsDrag.begin(cardId, dragLabel, newValue, range)
        }
        SettingsDrag.updateValue(newValue)
      }
      onValueChange(newValue)
    },
    valueRange = range,
    modifier = modifier.then(dragObserver),
    onValueChangeFinished = {
      SettingsDrag.end()
      onValueChangeFinished?.invoke()
    },
    colors = SliderDefaults.colors(
      thumbColor = ScexColors.Accent,
      activeTrackColor = ScexColors.AccentForm,
      inactiveTrackColor = ScexColors.Background,
    ),
  )
}

/** 「标签 —— 值」一行，带行分隔线（用于诊断信息表） */
@Composable
fun ScexStatRow(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = label,
        color = ScexColors.Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      Text(text = value, color = ScexColors.Body, style = MaterialTheme.typography.bodySmall)
    }
    Box(
      Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(ScexColors.RowSeparator),
    )
  }
}

/** 节内小标题（不带竖条、不带下划线） */
@Composable
fun ScexLabel(text: String, modifier: Modifier = Modifier, color: Color = ScexColors.Muted) {
  Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}
