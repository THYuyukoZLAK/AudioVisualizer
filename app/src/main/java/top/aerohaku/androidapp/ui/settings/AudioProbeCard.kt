package top.aerohaku.androidapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.capture.NeteaseApiProbe
import top.aerohaku.androidapp.theme.JetBrainsMono
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton

/**
 * 「接口探测」—— 一个**诊断**卡片，排查元数据读不到时卡在哪一步。
 *
 * 平时看「元数据来源」那张卡就够了（它直接显示当前来源、连接状态与日志）。
 * 这里多探一步的是 **binder 的接口描述符**：`bindService` 是能成功、
 * 但服务实现的到底是平台接口还是别的私有 AIDL，只有这一步看得出来 ——
 * 「连不上」和「连上了但不给数据」是两种完全不同的故障。
 *
 * 全程不需要任何授权。
 */
@Composable
fun AudioProbeCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var running by remember { mutableStateOf(false) }
  var lines by remember { mutableStateOf<List<String>>(emptyList()) }

  fun probeNetease() {
    if (running) return
    running = true
    scope.launch {
      // ⚠️ 必须在**主线程**：bindService 与 MediaBrowser 的回调都投递回主线程
      lines = NeteaseApiProbe.run(context)
      running = false
    }
  }

  ScexCard(title = "接口探测（诊断）", modifier = modifier) {
    Hint("把网易云对外暴露的 binder 接口名打出来，用来区分「服务根本连不上」和「连上了但不给数据」。不需要任何授权，最多 5 秒。")

    ScexGhostButton(
      text = if (running) "探测中…" else "探测网易云对外接口",
      onClick = { probeNetease() },
    )

    if (lines.isNotEmpty()) {
      Column(
        Modifier
          .fillMaxWidth()
          .background(ScexColors.CodeBackground)
          .padding(12.dp),
      ) {
        lines.forEach { line ->
          Text(
            text = line,
            color = if (line.startsWith("×")) ScexColors.Danger else ScexColors.CodeText,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            lineHeight = 16.sp,
          )
        }
      }
    }
  }
}
