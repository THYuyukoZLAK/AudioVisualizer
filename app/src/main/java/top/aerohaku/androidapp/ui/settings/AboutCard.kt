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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.aerohaku.androidapp.R
import top.aerohaku.androidapp.theme.JetBrainsMono
import top.aerohaku.androidapp.theme.ScexCard
import top.aerohaku.androidapp.theme.ScexColors
import top.aerohaku.androidapp.theme.ScexGhostButton
import top.aerohaku.androidapp.theme.ScexLabel
import top.aerohaku.androidapp.ui.components.ExternalLinkIcon
import top.aerohaku.androidapp.update.RELEASES_URL
import top.aerohaku.androidapp.update.REPO_URL
import top.aerohaku.androidapp.update.ReleaseInfo
import top.aerohaku.androidapp.update.UpdateCheckResult
import top.aerohaku.androidapp.update.UpdateChecker

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

  // 名称/版本都从 PackageManager 读，而不是 BuildConfig —— 见 readAppInfo 的说明
  val appInfo = remember { readAppInfo(context) }

  // 「检查更新」的状态。只在用户点了按钮之后才有值 —— 不做自动检查（见下方注释）
  var updateState by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
  var showNotes by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  fun startCheck() {
    if (updateState is UpdateUi.Checking) return
    val version = appInfo?.versionName
    if (version == null) {
      updateState = UpdateUi.Failed("读不到当前应用的版本号，无法比较。")
      return
    }
    updateState = UpdateUi.Checking
    scope.launch {
      updateState = when (val result = UpdateChecker.check(version)) {
        is UpdateCheckResult.UpToDate -> UpdateUi.UpToDate(result.latest)
        is UpdateCheckResult.UpdateAvailable -> UpdateUi.Available(result.release)
        is UpdateCheckResult.Failed -> UpdateUi.Failed(
          result.reason.message + (result.detail?.let { "（$it）" } ?: ""),
        )
      }
    }
  }

  ScexCard(title = "关于", showLeftBar = true, modifier = modifier) {
    // ------------------------------------------------------------ 应用信息
    ScexLabel("应用信息")
    Text(
      text = appInfo?.label ?: "AudioVisualizer",
      color = ScexColors.Heading,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = if (appInfo == null) {
        "版本信息读取失败"
      } else {
        "${appInfo.versionName}（versionCode ${appInfo.versionCode}）"
      },
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    if (appInfo != null) {
      Text(
        text = "系统要求 Android ${androidVersionName(appInfo.minSdk)} 及以上" +
          "（minSdk ${appInfo.minSdk} / targetSdk ${appInfo.targetSdk}）",
        color = ScexColors.Body,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    Spacer(Modifier.height(4.dp))

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

    // ------------------------------------------------------------ 检查更新
    //
    // 只在用户点按钮时查，**不做自动检查**：目标用户大都在中国大陆，连 GitHub
    // 常常要等十几秒才失败 —— 一进「关于」页就自动去撞一次网络，既要等又没意义。
    //
    // ‼️ 查不到**不能影响任何其它功能**：所有失败都只是几行提示，并且始终留一条
    //    「用浏览器打开 Releases 页面」的退路（那条路不经过 api.github.com）。
    ScexLabel("检查更新")
    Text(
      text = when (val state = updateState) {
        is UpdateUi.Idle -> "需要手动查询 GitHub Releases，国内用户可能无法直连。"
        is UpdateUi.Checking -> "正在查询 GitHub Releases…"
        is UpdateUi.UpToDate -> "已是最新版本（远端为 ${state.latest}）。"
        is UpdateUi.Available ->
          "发现新版本 ${state.release.tag}（当前 ${appInfo?.versionName ?: "?"}）。"
        is UpdateUi.Failed -> state.message
      },
      color = when (updateState) {
        is UpdateUi.Failed -> ScexColors.Warning
        is UpdateUi.Available -> ScexColors.Success
        else -> ScexColors.Body
      },
      style = MaterialTheme.typography.bodySmall,
    )

    val release = (updateState as? UpdateUi.Available)?.release
    if (release != null && release.notes.isNotEmpty()) {
      ScexGhostButton(
        text = if (showNotes) "收起更新说明" else "展开更新说明",
        onClick = { showNotes = !showNotes },
      )
      if (showNotes) {
        // 和许可证全文一样：不套 verticalScroll，直接参与外层滚动
        Column(
          Modifier
            .fillMaxWidth()
            .background(ScexColors.CodeBackground)
            .padding(12.dp),
        ) {
          Text(
            text = release.notes,
            color = ScexColors.CodeText,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            lineHeight = 16.sp,
          )
        }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ScexGhostButton(
        text = if (updateState is UpdateUi.Checking) "正在查询…" else "检查更新",
        onClick = { startCheck() },
      )
      // 有结论（或失败）就给退路按钮。查询接口不通时这是唯一可行的路，所以两个分支共用
      if (updateState is UpdateUi.Available || updateState is UpdateUi.Failed) {
        ScexGhostButton(
          text = "打开 Releases 页面",
          onClick = { openUrl(context, release?.pageUrl ?: RELEASES_URL) },
        )
      }
    }

    Spacer(Modifier.height(4.dp))

    // ------------------------------------------------------------ 开源许可
    ScexLabel("开源许可")
    Text(
      text = "本应用自身代码以 MIT License 发布\nCopyright (c) 2026 THYuyukoZLAK。",
      color = ScexColors.Body,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = "频谱分析（dsp/SpectrumAnalyzer.kt）与背景粒子" +
        "（ui/particles/ParticleField.kt）两处实现，移植/改写自 LLin —— " +
        "osu! 插件 IGPlayer，即「mfosu」：\n" +
        "github.com/MATRIX-feather/LLin\n" +
        "MIT License, Copyright (c) 2025 MATRIX-feather",
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
        // 「会跳到应用外部」这件事得提前说出来，别让用户点下去才发现
        icon = { ExternalLinkIcon(color = ScexColors.Muted, modifier = Modifier.size(13.dp)) },
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
  }
}

/** logo 显示宽度（原图 560×154，按比例约 72dp 高） */
private val ABOUT_LOGO_WIDTH = 260.dp

/** 当前已安装这份包自身的元信息，见 [readAppInfo] */
private data class AppInfo(
  val label: String,
  val versionName: String,
  val versionCode: Long,
  val packageName: String,
  val minSdk: Int,
  val targetSdk: Int,
)

/**
 * 读当前**已安装**这一份的版本信息。
 *
 * 为什么不用 `BuildConfig`：AGP 8 起 `buildConfig` 默认关闭（本项目也没开），
 * 而 `PackageManager` 永远可用，读到的还是**实际安装的那一份**的版本，
 * 不会出现「装了 1.2 但界面显示 1.3」（如果构建时把常量写死就会）。
 *
 * 失败就返回 null：这种展示性信息不该让整个「关于」页挂掉。
 */
private fun readAppInfo(context: Context): AppInfo? = runCatching {
  val pm = context.packageManager
  val pkg = pm.getPackageInfo(context.packageName, 0)
  val app = pm.getApplicationInfo(context.packageName, 0)
  AppInfo(
    label = pm.getApplicationLabel(app).toString(),
    versionName = pkg.versionName ?: "?",
    versionCode = pkg.longVersionCode,
    packageName = context.packageName,
    minSdk = app.minSdkVersion,
    targetSdk = app.targetSdkVersion,
  )
}.getOrNull()

/**
 * API level → 面向用户的 Android 版本名。
 *
 * 只收录常见几个；没收录的**直接显示数字** —— 宁可显示「29」，
 * 也不要因为映射表过期而显示一个错的版本名。
 */
private fun androidVersionName(api: Int): String = when (api) {
  29 -> "10"
  30 -> "11"
  31, 32 -> "12"
  33 -> "13"
  34 -> "14"
  35 -> "15"
  36 -> "16"
  else -> api.toString()
}

/**
 * 「检查更新」的界面状态。
 *
 * [ReleaseInfo] / [UpdateCheckResult] 在 `update` 包里，不直接当界面状态用 ——
 * 失败信息在这一层就已经翻成了给用户看的一句话。
 */
private sealed interface UpdateUi {
  data object Idle : UpdateUi
  data object Checking : UpdateUi
  data class UpToDate(val latest: String) : UpdateUi
  data class Available(val release: ReleaseInfo) : UpdateUi
  data class Failed(val message: String) : UpdateUi
}

/**
 * 用外部应用打开链接。
 *
 * `FLAG_ACTIVITY_NEW_TASK` 不能省：即使当前是 Activity 上下文，
 * 目标可能是个独立任务里的浏览器，缺了它某些 ROM 会直接抛异常。
 * 整个调用包在 `runCatching` 里 —— 设备上没有任何浏览器时不能把 App 带崩。
 */
internal fun openUrl(context: Context, url: String) {
  runCatching {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }
}
