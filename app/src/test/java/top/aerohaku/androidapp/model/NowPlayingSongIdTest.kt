package top.aerohaku.androidapp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [NowPlaying.songId] 的解析规则。
 *
 * 这个归一化不是洁癖，是**真机实测**踩出来的：网易云在两条链路上往
 * `METADATA_KEY_MEDIA_ID` 里塞的东西不一样 ——
 *
 * - 通知使用权路径：`411500348`（纯数字，直接能查歌词）
 * - MediaBrowser 路径：`playlist_id_4866956204_3340114786`（拿去查歌词接口返回 `code=400`）
 *
 * 后者是 `playlist_id_<歌单ID>_<歌曲ID>`，**最后一段才是歌曲 ID**。
 * 实测取最后一段能拿到 2171 字节带翻译的歌词；取中间那段歌单 ID 只会得到「暂无歌词」。
 *
 * 所以规则写得保守一点：认不出来就当没有 —— 宁可不显示歌词，
 * 也不要拿一个错的 ID 去查出一首不相干的歌的歌词。
 */
class NowPlayingSongIdTest {

  private fun idOf(mediaId: String?): String? = NowPlaying(mediaId = mediaId).songId

  @Test
  fun `纯数字直接可用`() {
    assertEquals("411500348", idOf("411500348"))
    assertEquals("3340114786", idOf("3340114786"))
  }

  @Test
  fun `playlist_id 形式取最后一段`() {
    assertEquals("3340114786", idOf("playlist_id_4866956204_3340114786"))
    assertEquals("3340112782", idOf("playlist_id_4866956204_3340112782"))
  }

  @Test
  fun `前缀任意_只要能找出数字尾巴`() {
    assertEquals("12345", idOf("song_id_12345"))
    assertEquals("999", idOf("whatever_999"))
  }

  @Test
  fun `认不出来就当没有_不要瞎猜`() {
    assertNull(idOf("playlist_id_4866956204_"))
    assertNull(idOf("playlist_id_4866956204_abc"))
    assertNull(idOf("abc"))
    assertNull(idOf("a1b2"))
    assertNull(idOf(""))
    assertNull(idOf("   "))
    assertNull(idOf(null))
  }

  @Test
  fun `前后空白会被裁掉`() {
    assertEquals("411500348", idOf("  411500348  "))
  }
}
