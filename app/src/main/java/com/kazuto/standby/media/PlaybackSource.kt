package com.kazuto.standby.media

import kotlinx.coroutines.flow.StateFlow

/**
 * スタンバイ画面が表示・操作する「いま再生中のもの」の供給元。
 *  - [MediaSessionWatcher]: 端末上の音楽アプリの MediaSession(他端末の鏡を含む)
 *  - [com.kazuto.standby.spotify.SpotifyWebPlayer]: Spotify Web API のポーリング
 */
interface PlaybackSource {
    val nowPlaying: StateFlow<NowPlaying?>
    fun start()
    fun stop()
    fun playPause()
    fun skipToNext()
    fun skipToPrevious()
}
