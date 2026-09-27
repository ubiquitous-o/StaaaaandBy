package com.kazuto.standby.lyrics

import android.content.Context
import android.util.Log
import com.kazuto.standby.media.NowPlaying
import com.kazuto.standby.media.TrackRef
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
 * 画面に渡す歌詞の状態。
 * @param lyrics 再生中の曲の同期歌詞。無ければ null
 * @param pendingKey 問い合わせ中の曲の鍵。この曲のあいだはスリットスキャンを出さずに待つ
 */
data class LyricsState(val lyrics: SyncedLyrics? = null, val pendingKey: String? = null)

/**
 * LRCLIB (https://lrclib.net、公開・キー不要) から同期歌詞を引く。
 * 曲名・アーティスト・曲の長さで探す。jizura-sync の web/lyrics.js の移植。
 *
 * [follow] に再生状態と「次の曲」の Flow を渡す。曲が変わるたびに引き直して [state] に流し、
 * 次の曲が分かったら先に引いておく(切り替えの瞬間にスリットスキャンが映らないように)。
 * 見つからなかった曲も覚えておき、同じ曲で何度も問い合わせない。
 */
class LyricsRepository(context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "StaaaaandBy"
        private const val API = "https://lrclib.net/api"
        // LRCLIB はクライアントを名乗るよう求めている
        private const val CLIENT = "StaaaaandBy (https://github.com/ubiquitous-o/StaaaaandBy)"
        /** 429 の Retry-After をこれ(秒)まで待つ。超えたらその曲は諦める */
        private const val MAX_RETRY_AFTER_S = 30
        /** 曲の長さがこれ(秒)以上ズレていたら別の曲とみなす */
        private const val DURATION_TOLERANCE_S = 4
        private const val CACHE_SIZE = 32

        /** [NowPlaying.trackKey] と同じ形の鍵 */
        private fun exactKeyOf(title: String, artist: String, durationMs: Long) = "$title\u0001$artist\u0001${durationMs / 1000}"
        private fun looseKeyOf(title: String, artist: String) = "$title\u0001$artist"
    }

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state

    private data class Track(val key: String, val title: String, val artist: String, val album: String?, val durationMs: Long) {
        val looseKey get() = looseKeyOf(title, artist)
    }

    /** LRCLIB のレコード1件ぶん: 長さ(秒)と同期歌詞 */
    private data class Hit(val durationS: Int, val lrc: String)

    private class Lru<V>(size: Int) : LinkedHashMap<String, V>(size, 0.75f, true) {
        private val max = size
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    /** 鍵(曲名・アーティスト・長さ秒) → 同期歌詞。無かった曲は null で覚える */
    private val exact = Lru<String?>(CACHE_SIZE)
    /** 曲名・アーティストだけの鍵 → 見つかったレコード。長さが不明な先読みに使う */
    private val loose = Lru<Hit?>(CACHE_SIZE)
    /** メモリの次に見るディスクキャッシュ。スタンバイを閉じても残る */
    private val disk = LyricsDiskCache(context)
    private val prefetching = mutableMapOf<String, Job>()
    private var job: Job? = null

    fun follow(nowPlaying: Flow<NowPlaying?>, upNext: Flow<TrackRef?>) {
        scope.launch {
            nowPlaying
                .map { np ->
                    np?.takeIf { it.durationMs > 0 && it.title.isNotBlank() }
                        ?.let { Track(it.trackKey, it.title, it.artist, it.album, it.durationMs) }
                }
                .distinctUntilChanged()
                .collect { onTrack(it) }
        }
        scope.launch {
            upNext.distinctUntilChanged().collect { ref -> if (ref != null) prefetch(ref) }
        }
    }

    private fun onTrack(track: Track?) {
        job?.cancel()
        if (track == null) {
            _state.value = LyricsState()
            return
        }
        if (_state.value.lyrics?.key == track.key) return
        _state.value = LyricsState(lyrics = null, pendingKey = track.key)
        job = scope.launch {
            // 先読みが走っていれば待つ
            prefetching[track.looseKey]?.join()
            var from = "memory"
            val lrc: String? = if (exact.containsKey(track.key)) {
                exact[track.key]
            } else {
                cached(track)?.also { exact[track.key] = it } ?: run {
                    val onDisk = withContext(Dispatchers.IO) { disk.get("x:" + track.key) }
                    if (onDisk != null) {
                        from = "disk"
                        exact[track.key] = onDisk.lrc
                        onDisk.lrc
                    } else {
                        from = "lrclib"
                        val hit = withContext(Dispatchers.IO) {
                            runCatching { fetch(track) }
                                .onFailure { Log.w(TAG, "lyrics: lookup failed for '${track.title}': ${it.message}") }
                                .getOrNull()
                        }
                        remember(track, hit)
                        hit?.lrc
                    }
                }
            }
            Log.i(TAG, "lyrics: '${track.title}' → ${if (lrc == null) "none" else "synced (${lrc.length} chars)"} [$from]")
            _state.value = LyricsState(
                lyrics = lrc?.let { SyncedLyrics(track.key, track.title, track.artist, track.durationMs, it) },
                pendingKey = null,
            )
        }
    }

    /** 次の曲の歌詞を先に引いておく。長さが分かっていれば厳密に、無ければ曲名とアーティストで */
    private fun prefetch(ref: TrackRef) {
        if (ref.title.isBlank()) return
        val durationS = (ref.durationMs / 1000.0).roundToInt()
        val looseKey = looseKeyOf(ref.title, ref.artist)
        val exactKey = if (ref.durationMs > 0) exactKeyOf(ref.title, ref.artist, ref.durationMs) else null
        if (exactKey != null && exact.containsKey(exactKey)) return
        if (loose.containsKey(looseKey) || prefetching.containsKey(looseKey)) return
        prefetching[looseKey] = scope.launch {
            // ディスクにあればネットに行かない
            val onDisk = withContext(Dispatchers.IO) {
                (exactKey?.let { disk.get("x:$it") }) ?: disk.get("l:$looseKey")
            }
            if (onDisk != null) {
                val hit = onDisk.lrc?.let { Hit(onDisk.durationS, it) }
                loose[looseKey] = hit
                if (exactKey != null) exact[exactKey] = hit?.lrc
                Log.i(TAG, "lyrics: prefetched '${ref.title}' → ${if (hit == null) "none" else "synced"} [disk]")
                prefetching.remove(looseKey)
                return@launch
            }
            val hit = withContext(Dispatchers.IO) {
                runCatching {
                    if (ref.durationMs > 0) {
                        fetch(Track(exactKey!!, ref.title, ref.artist, ref.album, ref.durationMs))
                    } else {
                        search(ref.title, ref.artist, null)
                    }
                }.onFailure { Log.w(TAG, "lyrics: prefetch failed for '${ref.title}': ${it.message}") }.getOrNull()
            }
            loose[looseKey] = hit
            if (exactKey != null) exact[exactKey] = hit?.lrc
            withContext(Dispatchers.IO) {
                disk.put("l:$looseKey", hit?.durationS ?: durationS, hit?.lrc)
                if (exactKey != null) disk.put("x:$exactKey", hit?.durationS ?: durationS, hit?.lrc)
            }
            Log.i(TAG, "lyrics: prefetched '${ref.title}' (dur=${durationS}s) → ${if (hit == null) "none" else "synced, ${hit.durationS}s"} [lrclib]")
            prefetching.remove(looseKey)
        }
    }

    /** 長さの合う先読み結果(曲名・アーティストの鍵、メモリ)があれば返す */
    private fun cached(track: Track): String? {
        val hit = loose[track.looseKey] ?: return null
        val durationS = (track.durationMs / 1000.0).roundToInt()
        return hit.lrc.takeIf { abs(hit.durationS - durationS) <= DURATION_TOLERANCE_S }
    }

    private fun remember(track: Track, hit: Hit?) {
        exact[track.key] = hit?.lrc
        if (hit != null) loose[track.looseKey] = hit
        scope.launch(Dispatchers.IO) {
            disk.put("x:" + track.key, hit?.durationS ?: (track.durationMs / 1000.0).roundToInt(), hit?.lrc)
            if (hit != null) disk.put("l:" + track.looseKey, hit.durationS, hit.lrc)
        }
    }

    /** 同期歌詞のレコード。無ければ null。 */
    private suspend fun fetch(track: Track): Hit? {
        val durationS = (track.durationMs / 1000.0).roundToInt()
        // Spotify は複数アーティストを ", " で繋ぐ。LRCLIB はふつう先頭の1人だけで登録されている
        val artists = listOf(track.artist, track.artist.substringBefore(", ")).distinct()
        for (artist in artists) {
            val params = mutableListOf(
                "artist_name" to artist, "track_name" to track.title, "duration" to durationS.toString()
            )
            track.album?.let { params += "album_name" to it }
            val res = lrclib("/get?" + query(params))
            if (res.first == 200) return hit(JSONObject(res.second))
            if (res.first != 404) throw IllegalStateException("LRCLIB /get ${res.first}")
        }
        // /get は長さを厳密(±2秒)に見る。/search は見ないので自分で絞る
        return search(track.title, artists.last(), durationS)
    }

    /** /search で探す。durationS を渡すと長さで絞り、null なら同期歌詞のあるものを優先して先頭を返す */
    private suspend fun search(title: String, artist: String, durationS: Int?): Hit? {
        val res = lrclib("/search?" + query(listOf("track_name" to title, "artist_name" to artist.substringBefore(", "))))
        if (res.first != 200) throw IllegalStateException("LRCLIB /search ${res.first}")
        val hits = JSONArray(res.second)
        var best: Hit? = null
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val d = h.optDouble("duration", 0.0)
            if (durationS != null && abs(d - durationS) > DURATION_TOLERANCE_S) continue
            val candidate = hit(h) ?: continue
            if (best == null) best = candidate
        }
        return best
    }

    private fun hit(rec: JSONObject): Hit? {
        val lrc = rec.optString("syncedLyrics").takeIf { it.isNotBlank() && !rec.isNull("syncedLyrics") } ?: return null
        return Hit(rec.optDouble("duration", 0.0).roundToInt(), lrc)
    }

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
