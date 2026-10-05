package top.aerohaku.androidapp.lyrics

import kotlin.math.abs

/** 一行歌词（可带翻译） */
data class LyricLine(
  val timeMs: Long,
  val text: String,
  val translation: String? = null,
)

/**
 * LRC 解析。
 *
 * 支持：
 * - `[mm:ss.xxx]` / `[mm:ss:xx]` / `[mm:ss]` 三种时间戳写法（小数位数自适应）
 * - 一行多个时间戳（`[00:01.00][00:05.00]同一句`）
 * - `[offset:+/-n]` 整体偏移（毫秒）
 * - 与翻译歌词按时间戳合并（容差 80ms，因为两边时间戳常见轻微不一致）
 */
object LrcParser {

  private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
  private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

  private const val TRANSLATION_TOLERANCE_MS = 80L

  fun parse(lrc: String?, translation: String? = null): List<LyricLine> {
    if (lrc.isNullOrBlank()) return emptyList()

    val offset = OFFSET_TAG.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val main = parseOne(lrc, offset)
    if (main.isEmpty()) return emptyList()

    val translated = parseOne(translation.orEmpty(), offset)
    if (translated.isEmpty()) return main

    val byExactTime = translated.groupBy { it.timeMs }
    return main.map { line ->
      val exact = byExactTime[line.timeMs]
      val text = if (exact != null) {
        exact.joinToString(" ") { it.text }
      } else {
        translated
          .minByOrNull { abs(it.timeMs - line.timeMs) }
          ?.takeIf { abs(it.timeMs - line.timeMs) <= TRANSLATION_TOLERANCE_MS }
          ?.text
      }
      if (text.isNullOrBlank() || text == line.text) line else line.copy(translation = text)
    }
  }

  private fun parseOne(raw: String, offset: Long): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val out = mutableListOf<LyricLine>()

    for (line in raw.lineSequence()) {
      val tags = TIME_TAG.findAll(line).toList()
      if (tags.isEmpty()) continue // 元数据行（[ti:] [ar:] [by:] 等）直接跳过

      val text = line.substring(tags.last().range.last + 1).trim()
      if (text.isEmpty()) continue

      for (tag in tags) {
        val minutes = tag.groupValues[1].toLongOrNull() ?: continue
        val seconds = tag.groupValues[2].toLongOrNull() ?: continue
        val fraction = tag.groupValues[3]
        val fractionMs = when (fraction.length) {
          0 -> 0L
          1 -> fraction.toLong() * 100
          2 -> fraction.toLong() * 10
          else -> fraction.take(3).toLong()
        }
        val timeMs = (minutes * 60_000 + seconds * 1_000 + fractionMs + offset).coerceAtLeast(0L)
        out += LyricLine(timeMs = timeMs, text = text)
      }
    }

    return out.sortedBy { it.timeMs }
  }

  /** 二分查找给定时间点对应的歌词行下标；早于第一句时返回 -1 */
  fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    if (positionMs < lines.first().timeMs) return -1
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
      val mid = (low + high) / 2
      if (lines[mid].timeMs <= positionMs) {
        result = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }
    return result
  }
}
