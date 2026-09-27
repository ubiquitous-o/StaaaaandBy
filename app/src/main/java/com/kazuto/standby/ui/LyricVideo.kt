package com.kazuto.standby.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.kazuto.standby.lyrics.SyncedLyrics
import com.kazuto.standby.media.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * JIZURA(assets/lyric)を WebView で動かすリリックビデオ。
 * 曲(同期歌詞)と再生位置の基準点を JS 側に渡し、描画は JS が毎フレーム行う。
 * WebView はタッチを受け取らないので、画面3分割のタップ操作は外側の Box に届く。
 */
@Composable
fun LyricVideo(lyrics: SyncedLyrics, playing: NowPlaying, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val page = remember { LyricPage(context) }
    DisposableEffect(page) { onDispose { page.destroy() } }

    AndroidView(factory = { page.view }, modifier = modifier)

    val ready by page.ready.collectAsState()
    LaunchedEffect(ready, lyrics.key) {
        if (ready) page.setSong(lyrics)
    }
    LaunchedEffect(
        ready, lyrics.key,
        playing.positionMs, playing.positionUpdatedAt, playing.isPlaying, playing.playbackSpeed
    ) {
        if (ready) page.setAnchor(playing)
    }
}

/** タッチを一切扱わない WebView。イベントは Compose 側の親に流れる */
private class PassThroughWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

private class LyricPage(context: Context) {
    companion object {
        private const val TAG = "StaaaaandBy"
        private const val URL = "https://appassets.androidplatform.net/assets/lyric/index.html"
    }

    val ready = MutableStateFlow(false)
    val view: WebView = PassThroughWebView(context)

    init {
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        @SuppressLint("SetJavaScriptEnabled")
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.setBackgroundColor(Color.BLACK)
        view.isClickable = false
        view.isFocusable = false
        view.isLongClickable = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "lyric page ready")
                ready.value = true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "lyric js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        view.loadUrl(URL)
    }

    fun setSong(l: SyncedLyrics) {
        val json = JSONObject()
            .put("key", l.key).put("title", l.title).put("artist", l.artist)
            .put("durationMs", l.durationMs).put("lrc", l.lrc)
        view.evaluateJavascript("window.lyric && window.lyric.setSong($json)", null)
    }

    /** 再生位置の基準点。JS 側は受け取った瞬間からの経過で外挿する */
    fun setAnchor(np: NowPlaying) {
        val now = SystemClock.elapsedRealtime()
        val pos = if (np.isPlaying) {
            np.positionMs + ((now - np.positionUpdatedAt) * np.playbackSpeed).toLong()
        } else {
            np.positionMs
        }
        val json = JSONObject().put("positionMs", pos).put("playing", np.isPlaying).put("speed", np.playbackSpeed)
        view.evaluateJavascript("window.lyric && window.lyric.setAnchor($json)", null)
    }

    fun destroy() {
        ready.value = false
        view.loadUrl("about:blank")
        view.destroy()
    }
}
