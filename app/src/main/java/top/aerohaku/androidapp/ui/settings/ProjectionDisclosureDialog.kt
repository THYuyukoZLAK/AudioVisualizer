package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.update.REPO_URL

/**
 * 「关于屏幕录制授权」说明弹窗。
 *
 * 两个入口共用同一份文案：
 * 1. 装机后**第一次启动**自动弹一次（由 `VisualizerScreen` 触发，见 `ProjectionDisclosureStore`）；
 * 2. 「设置 → 播放 → 捕获行为」里的按钮，随时可以再看一遍。
 *
 * 写它的原因：这是本应用唯一一个会让用户产生「你为什么要看我屏幕」疑虑的授权。
 * 与其让用户自己猜，不如把「为什么需要 / 实际用在哪 / 数据去哪了 / 怎么自行核实」一次说清，
 * 并给出不想授权时的降级方案。
 *
 * ⚠️ 文案里的 `**星号**` **不会**变成粗体 —— Compose 的 `Text` 不解析 Markdown，
 * 会连星号一起显示出来，所以这里一律不用 Markdown 标记。
 */
@Composable
internal fun ProjectionDisclosureDialog(onClose: () -> Unit) {
  val context = LocalContext.current

  ScexDialog(title = "关于屏幕录制授权", onDismiss = onClose) {
    Section(
      "为什么需要这个授权",
      "从 Android 10 起，要获取「别的应用正在播放的声音」，只能申请录音权限，" +
        "再由你点一次系统弹出的「允许录制或投射您的屏幕」。",
      "详情请见官方文档（developer.android.com/media/platform/av-capture）",
    )

    Section(
      "这个授权实际被用在哪里",
      "只取音频轨。",
      "拿到的授权令牌只交给 AudioPlaybackCaptureConfiguration，用来建一条音频捕获通道。",
      "所有与画面相关的接口本应用从未调用。",
      "在 Android 14 及以上的系统授权页里，你还能选「仅共享单个应用」，把捕获范围收窄到目标应用。",
    )

    Section(
      "音频去了哪里",
      "音频只在内存里过一遍，算完就丢。",
      "本应用确实有网络权限，但它只用于抓取歌词与封面。",
    )

    Section(
      "自行审计应用",
      "本应用完全开源（MIT 许可），源码地址：",
      REPO_URL.removePrefix("https://"),
      "可自行审查源码，编译程序。",
    )

    Section(
      "不想给这个授权",
      "可以在「设置 → 播放 → 捕获行为」里把音频来源改成「全局混音」，" +
        "它只要一次录音权限，也没有系统弹窗。",
      "但部分音频可视化特性将失效，详见相关设置页面的说明。",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(text = "查看源码", onClick = { openUrl(context, REPO_URL) })
      ScexGhostButton(text = "我了解了", onClick = onClose)
    }
  }
}

/**
 * 小标题 + 若干段正文。
 *
 * 每段单独一个 `Text`，段落之间由 `ScexDialog` 那层 `Column` 的 8dp 间距隔开 ——
 * 这样比塞一个大字符串再用 `\n\n` 好调，也免得某段太长时整块挤在一起。
 */
@Composable
private fun Section(title: String, vararg paragraphs: String) {
  Text(
    text = title,
    color = ScexColors.Heading,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold,
  )
  paragraphs.forEach { Hint(it) }
}
