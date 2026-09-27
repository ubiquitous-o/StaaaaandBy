package com.kazuto.standby.lyrics

import android.util.Log
import com.kazuto.standby.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/** いま再生中の曲の同期歌詞(LRC テキスト)。[key] は [NowPlaying.trackKey] と一致する */
data class SyncedLyrics(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val lrc: String,
)

/**
 * LRCLIB (https://lrclib.net、公開・キー不要) から同期歌詞を引く。
 * 曲名・アーティスト・曲の長さで探す。jizura-sync の web/lyrics.js の移植。
 *
 * [follow] に再生状態の Flow を渡すと、曲が変わるたびに引き直して [lyrics] に流す。
 * 見つからなかった曲は覚えておき、同じ曲で何度も問い合わせない。
 */
class LyricsRepository(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "StaaaaandBy"
        private const val API = "https://lrclib.net/api"
        // LRCLIB はクライアントを名乗るよう求めている
        private const val CLIENT = "StaaaaandBy (https://github.com/ubiquitous-o/StaaaaandBy)"
        /** 429 の Retry-After をこれ(秒)まで待つ。超えたらその曲は諦める */
        private const val MAX_RETRY_AFTER_S = 30
        /** /search の結果で曲の長さがこれ(秒)以上ズレていたら別の曲とみなす */
        private const val DURATION_TOLERANCE_S = 4
        private const val CACHE_SIZE = 32
    }

    private val _lyrics = MutableStateFlow<SyncedLyrics?>(null)
    val lyrics: StateFlow<SyncedLyrics?> = _lyrics

    private data class Track(val key: String, val title: String, val artist: String, val album: String?, val durationMs: Long)

    private val cache = object : LinkedHashMap<String, String?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > CACHE_SIZE
    }
    private var job: Job? = null

    fun follow(nowPlaying: Flow<NowPlaying?>) {
        scope.launch {
            nowPlaying
                .map { np ->
                    np?.takeIf { it.durationMs > 0 && it.title.isNotBlank() }
                        ?.let { Track(it.trackKey, it.title, it.artist, it.album, it.durationMs) }
                }
                .distinctUntilChanged()
                .collect { onTrack(it) }
        }
    }

    private fun onTrack(track: Track?) {
        job?.cancel()
        if (track == null) {
            _lyrics.value = null
            return
        }
        if (_lyrics.value?.key == track.key) return
        _lyrics.value = null
        if (cache.containsKey(track.key)) {
            publish(track, cache[track.key])
            return
        }
        job = scope.launch {
            val lrc = withContext(Dispatchers.IO) {
                runCatching { fetch(track) }
                    .onFailure { Log.w(TAG, "lyrics: lookup failed for '${track.title}': ${it.message}") }
                    .getOrNull()
            }
            cache[track.key] = lrc
            publish(track, lrc)
        }
    }

    private fun publish(track: Track, lrc: String?) {
        Log.i(TAG, "lyrics: '${track.title}' → ${if (lrc == null) "none" else "synced (${lrc.length} chars)"}")
        _lyrics.value = lrc?.let { SyncedLyrics(track.key, track.title, track.artist, track.durationMs, it) }
    }

    /** 同期歌詞の LRC テキスト。無ければ null。 */
    private suspend fun fetch(track: Track): String? {
        val durationS = (track.durationMs / 1000.0).roundToInt()
        // Spotify は複数アーティストを ", " で繋ぐ。LRCLIB はふつう先頭の1人だけで登録されている
        val artists = listOf(track.artist, track.artist.substringBefore(", ")).distinct()
        for (artist in artists) {
            val params = mutableListOf(
                "artist_name" to artist, "track_name" to track.title, "duration" to durationS.toString()
            )
            track.album?.let { params += "album_name" to it }
            val res = lrclib("/get?" + query(params))
            if (res.first == 200) return synced(JSONObject(res.second))
            if (res.first != 404) throw IllegalStateException("LRCLIB /get ${res.first}")
        }
        // /get は長さを厳密(±2秒)に見る。/search は見ないので自分で絞る
        val res = lrclib("/search?" + query(listOf("track_name" to track.title, "artist_name" to artists.last())))
        if (res.first != 200) throw IllegalStateException("LRCLIB /search ${res.first}")
        val hits = JSONArray(res.second)
        var best: JSONObject? = null
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            if (abs(h.optDouble("duration", 0.0) - durationS) > DURATION_TOLERANCE_S) continue
            if (best == null || (synced(best) == null && synced(h) != null)) best = h
        }
        return best?.let { synced(it) }
    }

    private fun synced(rec: JSONObject): String? =
        rec.optString("syncedLyrics").takeIf { it.isNotBlank() && !rec.isNull("syncedLyrics") }

    /** GET。429 は Retry-After を1回だけ待つ(LRCLIB の求め)。 */
    private suspend fun lrclib(path: String, retried: Boolean = false): Pair<Int, String> {
        val conn = URL("$API$path").openConnection() as HttpURLConnection
        val status: Int
        val body: String
        val retryAfter: Int
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Lrclib-Client", CLIENT)
            status = conn.responseCode
            body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 5
        } finally {
            conn.disconnect()
        }
        if (status != 429 || retried || retryAfter > MAX_RETRY_AFTER_S) return status to body
        delay(retryAfter * 1000L)
        return lrclib(path, retried = true)
    }

    private fun query(params: List<Pair<String, String>>) =
        params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
