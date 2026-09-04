package com.kazuto.standby.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kazuto.standby.Prefs
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
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.i(TAG, "metadata: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.i(
                TAG,
                "playbackState: state=${state?.state} pos=${state?.position} " +
                    "updated=${state?.lastPositionUpdateTime} now=${SystemClock.elapsedRealtime()}"
            )
            publish()
        }

        override fun onSessionDestroyed() {
            Log.w(TAG, "session destroyed: ${controller?.packageName}")
            refreshSessions()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.i(TAG, "active sessions changed: ${controllers.orEmpty().map { it.packageName }}")
            attach(controllers.orEmpty())
        }

    // 音楽アプリのプロセスを生かしておく係(スタンバイ表示中だけ)
    private val keepAlive = MusicAppKeepAlive(context)

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 鏡の同期し直しが必要なときに呼ばれる。引数は音楽アプリのパッケージ名。
     * 受け手(StandbyActivity)はそのアプリを一瞬前に出して即座に自分を戻す。
     * Spotify は自分の画面が前に出たときにしか他端末の状態を取りに行かないため。
     */
    var onResyncNeeded: ((packageName: String) -> Unit)? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * 直近で PLAYING だったとき、端末から音が出ていなかった = 他端末の再生を
     * 映している鏡だった。ローカル再生のアプリでは PAUSED を疑わないために覚えておく。
     */
    private var lastKnownRemote = Prefs.lastMusicWasRemote(context)
        set(value) {
            if (field != value) {
                field = value
                Prefs.setLastMusicWasRemote(context, value)
                Log.i(TAG, "remote playback: $value")
            }
        }

    /** PLAYING 中に端末から音が出ていた見張りの連続回数 */
    private var localAudioTicks = 0

    /**
     * 鏡の巻き戻り対策。他端末の再生が10分止まると Spotify は Connect の鏡を畳み、
     * スマホ自身の古いローカル再生状態(別の曲・別の位置・PAUSED)でセッションを
     * 出し直す(2026-09-01 実測)。これをそのまま信じると表示が別の曲に変わり、
     * タップするとスマホからその曲がローカル再生されてしまう。
     *
     * 対策: 鏡が消えた瞬間に直前の表示を凍結し(frozenRemote)、その後に現れた
     * 「最後に鏡で流れていた曲と違う曲の、再生中でないセッション」は偽物と疑う
     * (fallbackSuspected)。疑っているあいだは表示は凍結のまま、タップも無視する。
     * セッションが PLAYING になったら(Mac側の再開、またはユーザーが自分で再生)
     * 疑いを解いて通常に戻る。
     */
    private var frozenRemote: NowPlaying? = null
    private var fallbackSuspected = false

    /**
     * 鏡の静かな切れ対策(2026-09-04 実測)。鏡は畳まれずセッションも同じ曲のまま、
     * 他端末が再生中なのに突然 PAUSED を発行して固まることがある。本当の一時停止と
     * 見た目は同じで区別できない。見張りが「鏡だったセッションが PAUSED のまま
     * PAUSED_STALE_MS 更新なし」を検知したら音楽表示を消して時計だけにする。
     * 何かが PLAYING になった瞬間に解く。
     */
    private var staleHidden = false

    /** Prefs に保存済みの「鏡で最後に流れていた曲名」(書き込み間引き用のキャッシュ) */
    private var savedRemoteTitle: String? = Prefs.lastRemoteTitle(context)

    // 同期し直しの乱発防止: 疑いが続くあいだは間隔を広げていく
    private var resyncCount = 0
    private var nextResyncAt = 0L

    /**
     * 見張り: 定期的に状態をログに残し、他端末の鏡が静かに切れている疑いを検知する。
     *  - PLAYING なのに計算上の再生位置が曲の長さを大きく超えている
     *  - (鏡だったセッションが) PAUSED のまま長いあいだ一度も更新が来ていない。
     *    Spotify は同期が切れると相手を PAUSED と思い込んだまま固まる。本当の
     *    一時停止と区別はつかないので、間隔を広げながら疑い続ける
     * 検知したらセッションを取り直し、onResyncNeeded で同期し直しを頼む。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val np = _nowPlaying.value
            val now = SystemClock.elapsedRealtime()
            if (np != null) {
                val sinceUpdate = now - np.positionUpdatedAt
                val extrapolated = np.positionMs +
                    if (np.isPlaying) (sinceUpdate * np.playbackSpeed).toLong() else 0L
                if (np.isPlaying && sinceUpdate > 3_000) {
                    // Spotify は他端末の再生が再開した瞬間に数秒だけ自分でも音声出力を
                    // 開く癖があるので、「ローカル再生」への判定は音が2回連続で
                    // 出ているときだけにする。「鏡」への判定は1回でよい
                    if (audioManager?.isMusicActive == true) {
                        localAudioTicks++
                        if (localAudioTicks >= 2) lastKnownRemote = false
                    } else {
                        localAudioTicks = 0
                        lastKnownRemote = true
                    }
                }
                val overrun = np.isPlaying && np.durationMs > 0 &&
                    extrapolated > np.durationMs + STALE_MARGIN_MS
                val pausedTooLong = !np.isPlaying && lastKnownRemote && sinceUpdate > PAUSED_STALE_MS
                val stale = overrun || pausedTooLong
                Log.i(
                    TAG,
                    "watchdog: '${np.title}' playing=${np.isPlaying} remote=$lastKnownRemote " +
                        "pos=$extrapolated/${np.durationMs} sinceUpdate=${sinceUpdate}ms" +
                        if (stale) " STALE(${if (overrun) "overrun" else "paused"})" else ""
                )
                if (pausedTooLong && !staleHidden) {
                    staleHidden = true
                    Log.w(TAG, "watchdog: mirror stale, hiding '${np.title}' (clock only)")
                    _nowPlaying.value = null
                }
                if (!stale) {
                    resyncCount = 0
                    nextResyncAt = 0L
                } else if (now >= nextResyncAt) {
                    val pkg = controller?.packageName
                    val backoff = RESYNC_BACKOFF_MS[minOf(resyncCount, RESYNC_BACKOFF_MS.lastIndex)]
                    resyncCount++
                    nextResyncAt = now + backoff
                    Log.w(TAG, "watchdog: resync #$resyncCount via $pkg, next in ${backoff / 1000}s")
                    refreshSessions()
                    if (AUTO_RESYNC_ENABLED && pkg != null) {
                        onResyncNeeded?.invoke(pkg)
                    }
                }
            } else {
                Log.i(TAG, "watchdog: no now-playing (controller=${controller?.packageName})")
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "StaaaaandBy"

        /**
         * 鏡切れを検知したときに自動で同期し直す(音楽アプリの画面を一瞬起動する)か。
         *
         * 無効にしている理由: 画面起動は同期し直しに効く唯一の手段だったが、
         * ロック中は Spotify の画面が「開始したが表示されない」半端な状態になり、
         * 以後 Spotify は他端末の再生が再開するたびにスマホ側でも音声出力を開く
         * (マルチポイントの Bluetooth イヤホンがスマホに奪われる)。本当の一時停止と
         * 鏡切れの区別もつかない。ユーザーが Spotify を普通に開いて閉じれば直る。
         * 見張り自体はログのために動かし続ける。
         */
        private const val AUTO_RESYNC_ENABLED = false
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val STALE_MARGIN_MS = 5_000L
        private const val PAUSED_STALE_MS = 30_000L
        /** 同期し直しの間隔: 1回目は即、その後 5分 → 15分 → 30分ごと */
        private val RESYNC_BACKOFF_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

        /**
         * スタンバイに表示するのは音楽アプリのみ。
         * YouTube等の動画アプリもMediaSessionを持つが、対象にしない。
         */
        val MUSIC_APP_PACKAGES = setOf(
            "com.spotify.music",                      // Spotify
            "com.google.android.apps.youtube.music",  // YouTube Music
            "com.apple.android.music",                // Apple Music
            "com.amazon.mp3",                         // Amazon Music
            "jp.linecorp.linemusic.android",          // LINE MUSIC
            "fm.awa.liverpool",                       // AWA
            "com.aspiro.tidal",                       // TIDAL
            "com.soundcloud.android",                 // SoundCloud
            "deezer.android.app",                     // Deezer
            "com.sec.android.app.music",              // Samsung Music
        )
    }

    fun start() {
        Log.i(TAG, "start")
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            attach(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // 通知アクセスが未許可。曲情報なしで動かす。
            Log.w(TAG, "no notification access")
            _nowPlaying.value = null
        }
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    fun stop() {
        Log.i(TAG, "stop")
        handler.removeCallbacks(watchdog)
        keepAlive.unbind()
        scope.cancel()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    /**
     * タップを渡してはいけない状態か。
     *  - fallbackSuspected: 鏡が畳まれた後の偽セッション(別の曲のローカル状態)
     *  - 鏡が PAUSED のまま長く更新なし: 静かに切れた鏡の疑い。ここに play を
     *    送ると Connect の再生権ごとスマホに移り、他端末の再生を乗っ取って
     *    しまう(2026-09-01 実測: Mac の再生が止まりスマホから鳴り出した)
     */
    private fun tapsUnsafe(): Boolean {
        if (fallbackSuspected || staleHidden) return true
        val np = _nowPlaying.value ?: return false
        return lastKnownRemote && !np.isPlaying &&
            SystemClock.elapsedRealtime() - np.positionUpdatedAt > PAUSED_STALE_MS
    }

    fun playPause() {
        if (tapsUnsafe()) {
            Log.w(TAG, "tap ignored: possibly desynced mirror (would steal playback)")
            return
        }
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun skipToNext() {
        if (tapsUnsafe()) {
            Log.w(TAG, "tap ignored: possibly desynced mirror (would steal playback)")
            return
        }
        controller?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        if (tapsUnsafe()) {
            Log.w(TAG, "tap ignored: possibly desynced mirror (would steal playback)")
            return
        }
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
        val music = controllers.filter { it.packageName in MUSIC_APP_PACKAGES }
        Log.i(
            TAG,
            "attach: " + music.joinToString { c ->
                "${c.packageName}(state=${c.playbackState?.state}, " +
                    "title=${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)})"
            }.ifEmpty { "no music sessions" }
        )
        // 鏡が消えた瞬間: 直前の表示を凍結する(PAUSED 表示として持ち続ける)
        if (music.isEmpty() && lastKnownRemote && frozenRemote == null) {
            _nowPlaying.value?.let {
                frozenRemote = it.copy(isPlaying = false)
                Log.w(TAG, "mirror vanished: freezing '${it.title}'")
            }
        }

        controller = pickBest(music)
        controller?.registerCallback(controllerCallback)

        // 現れたセッションが「鏡で最後に流れていた曲と違う曲の、再生中でないもの」
        // なら、鏡が畳まれてローカル状態に巻き戻った偽物と疑う
        val newState = controller?.playbackState?.state
        val newTitle = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val expected = frozenRemote?.title ?: savedRemoteTitle
        if (controller != null && lastKnownRemote &&
            newState != PlaybackState.STATE_PLAYING &&
            expected != null && newTitle != expected
        ) {
            if (!fallbackSuspected) {
                Log.w(TAG, "fallback session suspected: '$newTitle' (expected '$expected')")
            }
            fallbackSuspected = true
        }

        // 生かしておく相手: いま選んだ音楽アプリ。セッションが無いときは
        // 最後に使っていたアプリ、それも無ければ入っている音楽アプリの先頭を
        // 起こしにいく(他端末の鏡を取り戻すため)
        val pkg = controller?.packageName
        if (pkg != null) Prefs.setLastMusicApp(context, pkg)
        keepAlive.bind(pkg ?: Prefs.lastMusicApp(context) ?: firstInstalledMusicApp())

        publish()
    }

    private fun firstInstalledMusicApp(): String? =
        MUSIC_APP_PACKAGES.firstOrNull { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }

    /** 再生中のセッションを最優先、なければメタデータを持つもの、それもなければ先頭。 */
    private fun pickBest(controllers: List<MediaController>): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
            ?: controllers.firstOrNull()

    private fun publish() {
        val c = controller
        val metadata = c?.metadata

        // 鏡の巻き戻り・静かな切れを疑っているあいだの扱い
        if (frozenRemote != null || fallbackSuspected || staleHidden) {
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                // 再生が始まった: 鏡の復帰か、ユーザー自身の意図的な再生。信じる
                Log.i(TAG, "playback resumed: unfreeze")
                frozenRemote = null
                fallbackSuspected = false
                staleHidden = false
            } else if (fallbackSuspected || staleHidden) {
                // 偽セッション、または PAUSED のまま固まった鏡: 音楽表示を消して
                // 時計だけにする。凍結表示のままだとタップが効かない理由が見た目で
                // 分からないため、「音楽情報が取れなくなった」ことをはっきり見せる
                _nowPlaying.value = null
                return
            } else if (c == null || metadata == null) {
                // セッション消滅直後の短い隙間: ちらつき防止に直前の表示を保つ
                _nowPlaying.value = frozenRemote
                return
            } else {
                // 同じ曲のままの PAUSED セッションが戻ってきた: 本物とみなす
                frozenRemote = null
            }
        }

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

        // 鏡で再生中の曲名を覚えておく(偽セッションの見分けに使う)
        if (lastKnownRemote && state?.state == PlaybackState.STATE_PLAYING &&
            title != savedRemoteTitle
        ) {
            savedRemoteTitle = title
            Prefs.setLastRemoteTitle(context, title)
        }
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
