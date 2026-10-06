package top.aerohaku.androidapp.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 仓库标识。三个地址都由它拼出来，改仓库只改这一行 */
internal const val REPO_SLUG = "THYuyukoZLAK/AudioVisualizer"

/** 仓库主页（「关于」页的 GitHub 按钮、「权限说明」弹窗的「查看源码」都用它） */
internal const val REPO_URL = "https://github.com/$REPO_SLUG"

/** Releases 页面。**连不上 GitHub 接口时的退路**：让用户自己用浏览器打开 */
internal const val RELEASES_URL = "$REPO_URL/releases"

/** 接口地址。取「最新一个正式 Release」 */
private const val API_URL = "https://api.github.com/repos/$REPO_SLUG/releases/latest"

/**
 * 连接超时。
 *
 * ⚠️ 这两个超时是**唯一能真正打断阻塞 socket 调用**的机制。
 * `withTimeout()` 做不到 —— 它只能取消**可挂起**的点，而 `HttpURLConnection`
 * 的读写在 `Dispatchers.IO` 上是纯阻塞的：协程取消了，线程还卡在 `read()` 里。
 * 所以超时**必须**设在连接上，不能指望外面的协程。
 */
private const val CONNECT_TIMEOUT_MS = 6000
private const val READ_TIMEOUT_MS = 8000

/** 远端 Release 的必要信息 */
internal data class ReleaseInfo(
  val tag: String,
  val name: String,
  val notes: String,
  val pageUrl: String,
)

internal sealed interface UpdateCheckResult {
  /** 已是最新（[latest] 是远端 tag，可能比本地「更大但相等」，用来给用户看） */
  data class UpToDate(val latest: String) : UpdateCheckResult

  data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult

  data class Failed(val reason: FailureReason, val detail: String? = null) : UpdateCheckResult
}

/**
 * 失败原因。
 *
 * 分开列而不是统一给一句「网络错误」，是因为本应用的目标用户**大都在中国大陆**
 * —— 连不上 GitHub 是**常态**而不是异常。提示必须让用户能区分
 * 「是我这边网络不通」「是 GitHub 限流」「是仓库那边没有 Release」，
 * 并且**每一种都要给出同一句退路**：直接用浏览器打开 Releases 页面看看。
 */
internal enum class FailureReason(val message: String) {
  TIMEOUT("连接 GitHub 超时。"),
  NO_NETWORK("域名解析失败，请检查网络连接。"),
  RATE_LIMITED("GitHub 拒绝了这次查询：未登录的接口调用次数用完了。"),
  NOT_FOUND("当前没有可用的 Release，或者仓库地址已经变了。"),
  HTTP_ERROR("GitHub 返回了异常状态。"),
  PARSE_ERROR("GitHub 返回的内容看不懂（接口可能改了）。"),
}

/**
 * 检查有没有新版本。
 *
 * 只用平台自带的 `HttpURLConnection` + `org.json`，**不引入任何依赖**（本项目的一贯原则）。
 * 失败一律转成 [UpdateCheckResult.Failed]，绝不抛异常给界面 ——
 * 查更新这种事失败了也不该影响任何别的功能。
 */
internal object UpdateChecker {

  suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    try {
      decideUpdate(currentVersion, fetchLatestRelease())
    } catch (e: SocketTimeoutException) {
      UpdateCheckResult.Failed(FailureReason.TIMEOUT)
    } catch (e: UnknownHostException) {
      UpdateCheckResult.Failed(FailureReason.NO_NETWORK)
    } catch (e: NotFound) {
      UpdateCheckResult.Failed(FailureReason.NOT_FOUND)
    } catch (e: RateLimited) {
      UpdateCheckResult.Failed(FailureReason.RATE_LIMITED)
    } catch (e: UnexpectedStatus) {
      UpdateCheckResult.Failed(FailureReason.HTTP_ERROR, "HTTP ${e.code}")
    } catch (e: MalformedReleaseException) {
      UpdateCheckResult.Failed(FailureReason.PARSE_ERROR)
    } catch (e: JSONException) {
      // 兜底：万一还有别的地方让 org.json 抛出来，也算「看不懂」而不是网络错误
      UpdateCheckResult.Failed(FailureReason.PARSE_ERROR)
    } catch (e: IOException) {
      // SSL 握手失败、连接被重置等等都落这里。带上异常名，用户报错时给得出线索
      UpdateCheckResult.Failed(FailureReason.HTTP_ERROR, e.javaClass.simpleName)
    }
  }

  /** 阻塞式取一次最新 Release。只在 [Dispatchers.IO] 上调用 */
  private fun fetchLatestRelease(): ReleaseInfo {
    val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      // ⚠️ 超时设在连接上，见 CONNECT_TIMEOUT_MS 的注释
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      // GitHub 对缺 UA 的请求会直接 403
      setRequestProperty("User-Agent", "AudioVisualizer-Android")
      setRequestProperty("Accept", "application/vnd.github+json")
    }
    try {
      when (val code = conn.responseCode) {
        404 -> throw NotFound()
        403, 429 -> throw RateLimited()
        !in 200..299 -> throw UnexpectedStatus(code)
      }
      return parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
      // 必须断：不断开的话连接会一直挂在池子里，Android 上还会占着 socket
      runCatching { conn.disconnect() }
    }
  }

  /** 内部信号，只为把 HTTP 状态码转成 [FailureReason]；都在 [check] 之前被接住 */
  private class NotFound : IOException()
  private class RateLimited : IOException()
  private class UnexpectedStatus(val code: Int) : IOException()
}

/**
 * 响应不是我们认得的形状（缺 `tag_name`、或者压根不是 JSON）。
 *
 * 自定义类型而不是直接用 `org.json.JSONException`：后者的构造器在**本地 JVM 单测里是桩**（抛 `Stub!`），
 * 用它就没法把「缺 tag 时算解析失败」这条逻辑测出来。
 */
internal class MalformedReleaseException(message: String) : Exception(message)

/**
 * 解析接口返回的 JSON。
 *
 * ⚠️ 这一层只有 `org.json` 的取值调用，**故意不做别的判断** ——
 * `org.json` 属于 android.jar，本地 JVM 单测里是桩，这里写的逻辑测不到；
 * 所以真正的判断都挤在 [releaseFrom] / [normalizeJsonField] 两个纯函数里。
 */
internal fun parseRelease(json: String): ReleaseInfo {
  val obj = try {
    JSONObject(json)
  } catch (e: JSONException) {
    throw MalformedReleaseException("响应不是 JSON")
  }
  return releaseFrom(
    tag = normalizeJsonField(obj.optString("tag_name")),
    name = normalizeJsonField(obj.optString("name")),
    notes = normalizeJsonField(obj.optString("body")),
    pageUrl = normalizeJsonField(obj.optString("html_url")),
  )
}

/**
 * 把一个原始字符串字段洗成可用的值（纯函数，可单测）。
 *
 * ‼️ 必须把字符串 `"null"` 也当成空：GitHub 允许「只打 tag、不写说明」，
 * 那时 `body` 是 **JSON 的 null**，而 `optString()` 会把它变成**字符串 `"null"`**
 * （`JSONObject.NULL.toString()` 就是 `"null"`）——
 * 不挡掉的话，界面上会出现一条写着「null」的更新说明。
 */
internal fun normalizeJsonField(raw: String?): String {
  val trimmed = raw?.trim().orEmpty()
  return if (trimmed == NULL_LITERAL) "" else trimmed
}

/**
 * 把四个原始字段拼成 [ReleaseInfo]（纯函数，可单测）。
 *
 * 兜底策略：
 * - `tag` 是唯一**必需**的字段，没有就当成解析失败；
 * - `name` 空就退回 tag；
 * - `pageUrl` 空就退回 Releases 页 —— 保证「打开 Releases 页面」那个退路按钮永远有地址可开。
 */
internal fun releaseFrom(tag: String, name: String, notes: String, pageUrl: String): ReleaseInfo {
  if (tag.isEmpty()) throw MalformedReleaseException("响应里没有 tag_name")
  return ReleaseInfo(
    tag = tag,
    name = name.ifEmpty { tag },
    notes = notes,
    pageUrl = pageUrl.ifEmpty { RELEASES_URL },
  )
}

/** `optString()` 对 JSON 的 null 会回这个字符串，见 [normalizeJsonField] */
private const val NULL_LITERAL = "null"

/**
 * 按版本号决定结果。
 *
 * 远端 tag 解析不出数字段时（比如有人把 tag 取成 `nightly`）**不报错**，
 * 退化成「字符串不一样就算有新版」—— 宁可多提示一次，也别把真有的新版本吞掉。
 */
internal fun decideUpdate(currentVersion: String, release: ReleaseInfo): UpdateCheckResult {
  val order = compareVersions(currentVersion, release.tag)
  if (order == null) {
    return if (release.tag.equals(currentVersion.trim(), ignoreCase = true)) {
      UpdateCheckResult.UpToDate(release.tag)
    } else {
      UpdateCheckResult.UpdateAvailable(release)
    }
  }
  return if (order < 0) {
    UpdateCheckResult.UpdateAvailable(release)
  } else {
    // order > 0 = 本地比远端还新（自己编的开发版），一样当作最新，不要反过来提示「降级」
    UpdateCheckResult.UpToDate(release.tag)
  }
}

/**
 * `"v1.4.0"` → `[1, 4, 0]`。只吃「v 前缀 + 数字与点」这一段，
 * 后面的后缀（`-beta1`、`+build`）忽略；完全解析不出数字就返回 null。
 */
internal fun parseVersion(text: String): List<Int>? {
  val trimmed = text.trim().removePrefix("v").removePrefix("V")
  val head = trimmed.takeWhile { it.isDigit() || it == '.' }
  if (head.isEmpty()) return null
  val parts = head.split('.').map { it.toIntOrNull() ?: return null }
  return parts.ifEmpty { null }
}

/**
 * 逐段比大小，缺的段按 0 算（`1.4` 与 `1.4.0` 相等）。
 * 任一侧解析不出来就返回 null，交给 [decideUpdate] 走退化分支。
 */
internal fun compareVersions(current: String, latest: String): Int? {
  val a = parseVersion(current) ?: return null
  val b = parseVersion(latest) ?: return null
  for (i in 0 until maxOf(a.size, b.size)) {
    val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
    if (diff != 0) return diff
  }
  return 0
}
