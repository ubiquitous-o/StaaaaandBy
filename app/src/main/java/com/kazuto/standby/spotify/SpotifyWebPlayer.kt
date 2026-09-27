package com.kazuto.standby.spotify

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.kazuto.standby.media.ArtLoader
import com.kazuto.standby.media.NowPlaying
import com.kazuto.standby.media.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Spotify Web API でアカウントの再生状態を追う [PlaybackSource]。
 * スマホ上の Spotify の鏡(他端末の再生の写し)は静かに切れるが、こちらは
 * どの端末で再生していても Spotify のサーバーから直接聞くので切れない。
 *
 * Spotify は状態をプッシュしてくれないので、1秒ごとに `GET /me/player` を叩き、
 * その間は再生位置を外挿する。読んだ位置の基準時刻は往復の中間点にする
 * (`progress_ms` は往復のどこかで採られていて、中間なら最悪誤差が半分になる)。
 * jizura-sync の web/spotify.js の SpotifyPlayer の移植。
 */
class SpotifyWebPlayer(private val context: Context) : PlaybackSource {

    companion object {
        private const val TAG = "StaaaaandBy"
        private const val POLL_MS = 1_000L
        /** 外挿と実測のズレがこれ未満ならジッタ扱いで基準を動かさない */
        private const val RESYNC_MS = 120L
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var failures = 0
    private var isPlaying = false
    private var trackId: String? = null
    private var art: Pair<String, Bitmap?>? = null   // artUrl → bitmap(読み込み中は null)
    private var seekAt = 0L                          // 操作直後の古いポーリング結果を捨てる

    override fun start() {
        if (pollJob != null) return
        Log.i(TAG, "spotify player: start")
        pollJob = scope.launch {
            var next = 0L
            while (isActive) {
                if (next > 0) delay(next)
                next = poll()
            }
        }
    }

    override fun stop() {
        Log.i(TAG, "spotify player: stop")
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    override fun playPause() = command(if (isPlaying) "PUT" to "/me/player/pause" else "PUT" to "/me/player/play")
    override fun skipToNext() = command("POST" to "/me/player/next")
    override fun skipToPrevious() = command("POST" to "/me/player/previous")

    private fun command(call: Pair<String, String>) {
        scope.launch {
            try {
                seekAt = SystemClock.elapsedRealtime()
                SpotifyAuth.api(context, call.first, call.second)
                // 新しい状態をすぐ拾い直す
                pollJob?.cancel()
                pollJob = null
                delay(250)
                start()
            } catch (e: SpotifyAuth.SpotifyException) {
                Log.w(TAG, "spotify command ${call.second} failed: ${e.message}")
            }
        }
    }

    /** 1回ポーリングして、次までの待ち時間(ms)を返す。 */
    private suspend fun poll(): Long {
        val t0 = SystemClock.elapsedRealtime()
        val res = try {
            SpotifyAuth.api(context, "GET", "/me/player?additional_types=track")
        } catch (e: SpotifyAuth.SpotifyException) {
            Log.w(TAG, "spotify poll failed (${e.kind}): ${e.message}")
            if (e.kind == SpotifyAuth.Kind.AUTH || e.kind == SpotifyAuth.Kind.NOT_REGISTERED) {
                _nowPlaying.value = null
                return 60_000L   // 入り直しが要る。たまに様子を見る程度にする
            }
            if (e.kind == SpotifyAuth.Kind.RATE_LIMITED) return max(1, e.retryAfterS) * 1000L
            failures++
            return min(30_000L, 1000L shl min(failures, 5))   // 2, 4, 8, 16, 30 s
        }
        val t1 = SystemClock.elapsedRealtime()
        failures = 0
        if (res.status == 204 || res.body.isBlank()) {
            // 再生中の端末が無い
            if (_nowPlaying.value != null) Log.i(TAG, "spotify: nothing is playing")
            trackId = null
            isPlaying = false
            _nowPlaying.value = null
            return POLL_MS * 3
        }
        val s = runCatching { JSONObject(res.body) }.getOrNull() ?: return POLL_MS
        isPlaying = s.optBoolean("is_playing", false)
        val item = s.optJSONObject("item")
        if (item != null && item.optString("type") == "track" && t0 >= seekAt) {
            read(s, item, (t0 + t1) / 2)
        }
        // 曲の終わり際はそこに合わせてポーリングし、次の曲へ遅れずに切り替える
        val np = _nowPlaying.value
        if (np != null && np.isPlaying) {
            val remaining = np.durationMs - np.progressAt(SystemClock.elapsedRealtime()) * np.durationMs
            return max(200L, min(POLL_MS, remaining.toLong() + 150))
        }
        return POLL_MS
    }

    private fun read(s: JSONObject, item: JSONObject, at: Long) {
        val id = item.optString("id").ifEmpty { item.optString("uri") }
        val sameTrack = id == trackId
        val playing = s.optBoolean("is_playing", false)
        val progress = s.optLong("progress_ms", 0)
        val prev = _nowPlaying.value

        if (sameTrack && prev != null && playing == prev.isPlaying && playing) {
            val predicted = prev.positionMs + (at - prev.positionUpdatedAt)
            if (abs(predicted - progress) < RESYNC_MS) return
        }

        val artists = item.optJSONArray("artists")
        val artist = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
            .joinToString(", ")
        val album = item.optJSONObject("album")
        val images = album?.optJSONArray("images")
        // 画像は大きい順。最大のものをスリットスキャン用に使う
        val artUrl = images?.optJSONObject(0)?.optString("url")?.ifEmpty { null }

        if (!sameTrack) {
            trackId = id
            Log.i(TAG, "spotify: track '${item.optString("name")}' playing=$playing")
        }
        if (artUrl != null && art?.first != artUrl) {
            art = artUrl to null
            scope.launch {
                val bmp = ArtLoader.load(artUrl)
                if (art?.first == artUrl) {
                    art = artUrl to bmp
                    _nowPlaying.value?.let { if (it.albumArt == null) _nowPlaying.value = it.copy(albumArt = bmp) }
                }
            }
        }
        _nowPlaying.value = NowPlaying(
            title = item.optString("name"),
            artist = artist,
            albumArt = art?.takeIf { it.first == artUrl }?.second,
            isPlaying = playing,
            appName = "Spotify",
            durationMs = item.optLong("duration_ms", 0),
            positionMs = progress,
            positionUpdatedAt = at,
            playbackSpeed = 1f,
            album = album?.optString("name")?.ifEmpty { null },
        )
    }
}
