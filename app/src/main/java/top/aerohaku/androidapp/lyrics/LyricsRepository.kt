package top.aerohaku.androidapp.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface LyricsState {
  data object Idle : LyricsState
  data class Loading(val songId: String) : LyricsState
  data class Ready(val songId: String, val lines: List<LyricLine>, val hasTranslation: Boolean) : LyricsState
  data class Unavailable(val songId: String, val reason: String) : LyricsState
}

/**
 * 网易云歌词抓取。
 *
 * 关键点：网易云在 MediaSession 的 `METADATA_KEY_MEDIA_ID` 里直接给出了**歌曲 ID**
 * （实测 `1840401436`），所以不需要任何搜索/匹配，直接拿 ID 打歌词接口即可。
 *
 * 接口：`https://music.163.com/api/song/lyric?id=<id>&lv=-1&kv=-1&tv=-1`
 * 必须带浏览器 UA + Referer，否则会被拒。JSON 解析用系统自带的 `org.json`，不引第三方库。
 */
object LyricsRepository {

  private const val ENDPOINT = "https://music.163.com/api/song/lyric"
  private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  private const val MAX_CACHE = 12
  private const val TIMEOUT_MS = 8_000

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val cache = LinkedHashMap<String, LyricsState.Ready>()
  private val inFlight = mutableSetOf<String>()

  private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
  val state: StateFlow<LyricsState> = _state.asStateFlow()

  private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
  val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

  /** songId 变化时调用；内部去重，重复调用同一首歌不会重复请求 */
  fun load(songId: String?, force: Boolean = false) {
    if (songId.isNullOrBlank()) {
      synchronized(this) { inFlight.clear() }
      _state.value = LyricsState.Idle
      return
    }

    if (!force) {
      val current = _state.value
      when {
        current is LyricsState.Loading && current.songId == songId -> return
        current is LyricsState.Ready && current.songId == songId -> return
        current is LyricsState.Unavailable && current.songId == songId -> return
      }
    }

    val cached = synchronized(this) { if (force) cache.remove(songId) else cache[songId] }
    if (cached != null) {
      _state.value = cached
      return
    }

    val alreadyRunning = synchronized(this) { !inFlight.add(songId) }
    if (alreadyRunning) return

    _state.value = LyricsState.Loading(songId)
    scope.launch {
      val result = try {
        fetch(songId)
      } catch (t: Throwable) {
        LyricsState.Unavailable(songId, "网络异常：${t.javaClass.simpleName} ${t.message}")
      }

      synchronized(this@LyricsRepository) {
        inFlight.remove(songId)
        if (result is LyricsState.Ready) {
          cache[songId] = result
          while (cache.size > MAX_CACHE) {
            cache.remove(cache.keys.first())
          }
        }
      }

      // 只有当用户还在等这首歌时才覆盖 UI，避免慢慢返回的旧请求把新歌的歌词顶掉
      val current = _state.value
      if (current is LyricsState.Loading && current.songId == songId) {
        _state.value = result
      }
      log("$songId → ${result.summary()}")
    }
  }

  /** 供 UI 手动重试 */
  fun reload(songId: String?) = load(songId, force = true)

  private fun fetch(songId: String): LyricsState {
    val connection = (URL("$ENDPOINT?id=$songId&lv=-1&kv=-1&tv=-1").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      setRequestProperty("User-Agent", USER_AGENT)
      setRequestProperty("Referer", "https://music.163.com/")
      setRequestProperty("Accept", "application/json, text/plain, */*")
    }

    try {
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      log("HTTP $code 响应长度=${body.length}")

      if (code !in 200..299) return LyricsState.Unavailable(songId, "HTTP $code")
      if (body.isBlank()) return LyricsState.Unavailable(songId, "响应为空")

      val json = JSONObject(body)
      val apiCode = json.optInt("code", -1)
      if (apiCode != 200) return LyricsState.Unavailable(songId, "接口返回 code=$apiCode")
      if (json.optBoolean("nolyric") || json.optBoolean("uncollected")) {
        return LyricsState.Unavailable(songId, "网易云标记为无歌词（nolyric/uncollected）")
      }

      val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
      val translation = json.optJSONObject("tlyric")?.optString("lyric").orEmpty()
      val lines = LrcParser.parse(lrc, translation)
      if (lines.isEmpty()) return LyricsState.Unavailable(songId, "解析后没有有效歌词行")

      return LyricsState.Ready(songId, lines, translation.isNotBlank())
    } finally {
      runCatching { connection.disconnect() }
    }
  }

  private fun log(line: String) {
    _diagnostics.value = (_diagnostics.value + line).takeLast(20)
  }

  private fun LyricsState.summary(): String = when (this) {
    is LyricsState.Ready -> "OK ${lines.size} 行${if (hasTranslation) "（含翻译）" else ""}"
    is LyricsState.Unavailable -> "失败：$reason"
    is LyricsState.Loading -> "加载中"
    LyricsState.Idle -> "空闲"
  }
}
