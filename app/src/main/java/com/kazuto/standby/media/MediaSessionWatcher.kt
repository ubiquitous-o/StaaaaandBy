package com.kazuto.standby.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class NowPlaying(
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val appName: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** positionMs を取得した時点の SystemClock.elapsedRealtime() */
    val positionUpdatedAt: Long,
    val playbackSpeed: Float,
) {
    /** 現在の再生進行度 0f..1f。elapsedRealtime は SystemClock.elapsedRealtime() を渡す。 */
    fun progressAt(elapsedRealtime: Long): Float {
        if (durationMs <= 0) return 0f
        val position = if (isPlaying) {
            positionMs + ((elapsedRealtime - positionUpdatedAt) * playbackSpeed).toLong()
        } else {
            positionMs
        }
        return (position.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/**
 * 端末上のアクティブな MediaSession を監視して、再生中の曲情報を StateFlow で公開する。
 * 通知アクセス許可(NowPlayingListenerService の有効化)が前提。
 */
class MediaSessionWatcher(private val context: Context) {

    private val sessionManager =
        context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent =
        ComponentName(context, NowPlayingListenerService::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private var controller: MediaController? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // URI経由で読み込んだ高解像度アートのキャッシュ(直近1件) と、読み込み中のURI
    private var artCache: Pair<String, Bitmap>? = null
    private var loadingArtUri: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers.orEmpty())
        }

    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            attach(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // 通知アクセスが未許可。曲情報なしで動かす。
            _nowPlaying.value = null
        }
    }

    fun stop() {
        scope.cancel()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun refreshSessions() {
        try {
            attach(sessionManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            _nowPlaying.value = null
        }
    }

    private fun attach(controllers: List<MediaController>) {
        controller?.unregisterCallback(controllerCallback)
        controller = pickBest(controllers)
        controller?.registerCallback(controllerCallback)
        publish()
    }

    /** 再生中のセッションを最優先、なければメタデータを持つもの、それもなければ先頭。 */
    private fun pickBest(controllers: List<MediaController>): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
            ?: controllers.firstOrNull()

    private fun publish() {
        val c = controller
        val metadata = c?.metadata
        if (c == null || metadata == null) {
            _nowPlaying.value = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title.isNullOrBlank()) {
            _nowPlaying.value = null
            return
        }
        val state = c.playbackState

        // 高解像度アート: URI版がキャッシュにあればそれを使い、
        // なければメタデータ内のビットマップ(Spotifyは300x300)でまず表示して裏で読み込む
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val cachedArt = artCache?.takeIf { it.first == artUri }?.second
        if (artUri != null && cachedArt == null) {
            loadArtAsync(artUri)
        }

        _nowPlaying.value = NowPlaying(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "",
            albumArt = cachedArt
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            appName = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrNull(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime
                ?: android.os.SystemClock.elapsedRealtime(),
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    /**
     * ALBUM_ART_URI から高解像度のアートを非同期で読み込む。
     * 成功したらキャッシュして publish() し直し、表示を差し替える。
     */
    private fun loadArtAsync(uriString: String) {
        if (uriString == loadingArtUri) return
        loadingArtUri = uriString
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decodeArtUri(uriString) }.getOrNull()
            }
            loadingArtUri = null
            if (bitmap != null) {
                artCache = uriString to bitmap
                publish()
            }
        }
    }

    private fun decodeArtUri(uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "content", "file", "android.resource" ->
                context.contentResolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it) }
            "http", "https" ->
                URL(uriString).openStream().use { BitmapFactory.decodeStream(it) }
            else -> null
        }
    }
}
