package top.aerohaku.androidapp.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.aerohaku.androidapp.R
import top.aerohaku.androidapp.theme.JetBrainsMono
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexLabel

/**
 * 「关于」卡片。
 *
 * 文字内容与工作区根目录的 `about.txt` 保持一致（那个文件是**源**，这里是它的呈现）。
 * `about.txt` 里那句「（在此放置一张图片，路径为 …HAIlogo.png）」是给作者的**占位说明**，
 * 所以直接把那张图渲染出来（已打进 `res/drawable/hai_logo.png`），
 * 而不是把说明文字本身显示给用户 —— 那个 Windows 路径 App 也读不到。
 *
 * ## 许可证为什么要放在这里
 *
 * 频谱分析与背景粒子移植/改写了 LLin（mfosu），它是 **MIT**；
 * MIT 要求「版权声明与许可声明必须随软件的所有副本一起提供」——
 * **APK 就是一份副本**，所以许可证全文必须随包内置，不能只放在源码仓库里。
 * 打包的 JetBrains Mono 字体是 OFL-1.1，同样有这个要求。
 * 全文由 `tools/make-licenses.ps1` 拼进 `res/raw/third_party_licenses.txt`。
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var showLicenses by remember { mutableStateOf(false) }

  // 约 20KB 纯文本，读一次就够；读失败也不能让整张卡片挂掉
  val licenseText = remember {
    runCatching {
      context.resources.openRawResource(R.raw.third_party_licenses)
        .bufferedReader()
        .use { it.readText() }
    }.getOrDefault("（许可证文本读取失败）")
  }

  ScexCard(title = "关于", modifier = modifier) {
    Text(
      text = "Dev. by 西行寺夜和 / THYuyukoZLAK",
      color = ScexColors.Heading,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = "with assistance of Deepseek V4.1 flash & Github Copilot",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )

    Image(
      painter = painterResource(R.drawable.hai_logo),
      contentDescription = "HAI logo",
      contentScale = ContentScale.Fit,
      // 固定宽度而不是撑满：卡片在横屏下有 1100dp 宽，铺满会把 logo 拉得过大
      modifier = Modifier.width(ABOUT_LOGO_WIDTH),
    )

    Spacer(Modifier.height(4.dp))

    // ------------------------------------------------------------ 开源许可
    ScexLabel("开源许可")
    Text(
      text = "本应用自身代码以 MIT License 发布，Copyright (c) 2026 THYuyukoZLAK。",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = "频谱分析（dsp/SpectrumAnalyzer.kt）与背景粒子" +
        "（ui/particles/ParticleField.kt）两处实现，移植/改写自 LLin —— " +
        "osu! 插件 IGPlayer，即「mfosu」：\n" +
        "github.com/MATRIX-feather/LLin\n" +
        "MIT License, Copyright (c) 2025 MATRIX-feather\n" +
        "两者许可证相同（均为 MIT），无冲突；已完整保留其版权声明与许可声明。" +
        "未使用该项目的任何美术资源、贴图或二进制文件。",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = "界面所用的 JetBrains Mono 字体为 SIL Open Font License 1.1" +
        "（Copyright 2020 The JetBrains Mono Project Authors），打包的是未修改的副本。\n" +
        "其余依赖（AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines）" +
        "为 Apache License 2.0。",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )

    // 两个按钮并排：许可证全文是「就地展开」，仓库地址是「跳出去」
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(
        text = if (showLicenses) "收起许可证全文" else "查看许可证全文",
        onClick = { showLicenses = !showLicenses },
      )
      ScexGhostButton(
        text = "GitHub 仓库",
        onClick = { openUrl(context, REPO_URL) },
      )
    }

    if (showLicenses) {
      // 不套 verticalScroll：外层设置页已经是 verticalScroll，
      // 嵌套同向滚动会互相抢手势。让它直接参与外层滚动即可。
      Column(
        Modifier
          .fillMaxWidth()
          .background(ScexColors.CodeBackground)
          .padding(12.dp),
      ) {
        Text(
          text = licenseText,
          color = ScexColors.CodeText,
          fontFamily = JetBrainsMono,
          fontSize = 11.sp,
          lineHeight = 16.sp,
        )
      }
    }

    Spacer(Modifier.height(4.dp))

    // ------------------------------------------------------------ 联系开发者
    ScexLabel("联系开发者")
    Text(
      text = "Email  xiazihe051130@hotmail.com",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = "QQ  1161254733",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** logo 显示宽度（原图 560×154，按比例约 72dp 高） */
private val ABOUT_LOGO_WIDTH = 260.dp

/**
 * ⚠️ **占位待填** —— 仓库地址定下来后改这一行即可。
 *
 * 用 `const val` 而不是塞进 `strings.xml`：它只在这个按钮上用一次，
 * 而且跟旁边的许可证文字一样属于「写死的元信息」，改的时候在一个文件里就能看到。
 */
private const val REPO_URL = "https://github.com/THYuyukoZLAK/AudioVisualizer"

/**
 * 用外部应用打开链接。
 *
 * `FLAG_ACTIVITY_NEW_TASK` 不能省：即使当前是 Activity 上下文，
 * 目标可能是个独立任务里的浏览器，缺了它某些 ROM 会直接抛异常。
 * 整个调用包在 `runCatching` 里 —— 设备上没有任何浏览器时不能把 App 带崩。
 */
private fun openUrl(context: Context, url: String) {
  runCatching {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }
}
