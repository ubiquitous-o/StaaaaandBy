package com.kazuto.standby

import android.content.Context
import android.content.SharedPreferences

/**
 * ユーザー設定。設定画面(MainActivity)で変更し、
 * ChargingWatchService / StandbyActivity が起動条件と表示向きに使う。
 */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_TRIGGER_ON_WIRED = "trigger_on_wired"
    private const val KEY_ALLOW_PORTRAIT = "allow_portrait"
    private const val KEY_LAST_MUSIC_APP = "last_music_app"
    private const val KEY_LAST_MUSIC_REMOTE = "last_music_remote"
    private const val KEY_LAST_REMOTE_TITLE = "last_remote_title"
    private const val KEY_LYRIC_VIDEO = "lyric_video"
    private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_ACCESS = "spotify_access_token"
    private const val KEY_SPOTIFY_REFRESH = "spotify_refresh_token"
    private const val KEY_SPOTIFY_EXPIRES = "spotify_expires_at"
    private const val KEY_SPOTIFY_PKCE_VERIFIER = "spotify_pkce_verifier"
    private const val KEY_SPOTIFY_PKCE_STATE = "spotify_pkce_state"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true: ケーブル(AC/USB)充電でも起動する。false(既定): Qi充電のときだけ。 */
    fun triggerOnWired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRIGGER_ON_WIRED, false)

    fun setTriggerOnWired(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_TRIGGER_ON_WIRED, value).apply()

    /** true: 縦向きでも起動し、縦用UIで表示する。false(既定): 横向きのときだけ。 */
    fun allowPortrait(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_PORTRAIT, false)

    fun setAllowPortrait(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALLOW_PORTRAIT, value).apply()

    /** 最後に曲情報を取った音楽アプリ。セッションが消えたときに起こしにいく相手。 */
    fun lastMusicApp(context: Context): String? =
        prefs(context).getString(KEY_LAST_MUSIC_APP, null)

    fun setLastMusicApp(context: Context, packageName: String) =
        prefs(context).edit().putString(KEY_LAST_MUSIC_APP, packageName).apply()

    /**
     * 直近の再生が「他端末の鏡」だったか(PLAYING なのに端末から音が出ていなかった)。
     * スタンバイを出し直しても引き継ぎ、PAUSED で固まった鏡を疑う材料にする。
     */
    fun lastMusicWasRemote(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAST_MUSIC_REMOTE, false)

    fun setLastMusicWasRemote(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LAST_MUSIC_REMOTE, value).apply()

    /**
     * 鏡(他端末の再生)で最後に流れていた曲名。
     * 鏡が畳まれた後に現れる「スマホ自身の古いローカル状態」の偽セッションを、
     * スタンバイを出し直した後でも見分けるために使う。
     */
    fun lastRemoteTitle(context: Context): String? =
        prefs(context).getString(KEY_LAST_REMOTE_TITLE, null)

    fun setLastRemoteTitle(context: Context, value: String) =
        prefs(context).edit().putString(KEY_LAST_REMOTE_TITLE, value).apply()

    /** true: 同期歌詞が見つかった曲はスリットスキャンの代わりにリリックビデオを出す。 */
    fun lyricVideo(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LYRIC_VIDEO, false)

    fun setLyricVideo(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LYRIC_VIDEO, value).apply()

    /** ユーザーが自分で作った Spotify アプリの Client ID(公開識別子、秘密ではない)。 */
    fun spotifyClientId(context: Context): String? =
        prefs(context).getString(KEY_SPOTIFY_CLIENT_ID, null)

    /** Client ID を変えたら古いトークンは無効なので捨てる。 */
    fun setSpotifyClientId(context: Context, value: String) {
        val trimmed = value.trim()
        if (trimmed == spotifyClientId(context)) return
        prefs(context).edit()
            .putString(KEY_SPOTIFY_CLIENT_ID, trimmed)
            .remove(KEY_SPOTIFY_ACCESS).remove(KEY_SPOTIFY_REFRESH).remove(KEY_SPOTIFY_EXPIRES)
            .apply()
    }

    class SpotifyTokens(val accessToken: String?, val refreshToken: String, val expiresAt: Long)

    /** 保存済みの Spotify トークン。リフレッシュトークンが無ければ未接続として null。 */
    fun spotifyTokens(context: Context): SpotifyTokens? {
        val p = prefs(context)
        val refresh = p.getString(KEY_SPOTIFY_REFRESH, null)?.takeIf { it.isNotEmpty() } ?: return null
        return SpotifyTokens(p.getString(KEY_SPOTIFY_ACCESS, null), refresh, p.getLong(KEY_SPOTIFY_EXPIRES, 0L))
    }

    fun setSpotifyTokens(context: Context, accessToken: String, refreshToken: String, expiresAt: Long) =
        prefs(context).edit()
            .putString(KEY_SPOTIFY_ACCESS, accessToken)
            .putString(KEY_SPOTIFY_REFRESH, refreshToken)
            .putLong(KEY_SPOTIFY_EXPIRES, expiresAt)
            .apply()

    fun clearSpotifyTokens(context: Context) =
        prefs(context).edit()
            .remove(KEY_SPOTIFY_ACCESS).remove(KEY_SPOTIFY_REFRESH).remove(KEY_SPOTIFY_EXPIRES)
            .apply()

    /** サインインの途中だけ持つ PKCE の verifier と state。(verifier, state) */
    fun spotifyPkce(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val v = p.getString(KEY_SPOTIFY_PKCE_VERIFIER, null) ?: return null
        val st = p.getString(KEY_SPOTIFY_PKCE_STATE, null) ?: return null
        return v to st
    }

    fun setSpotifyPkce(context: Context, verifier: String, state: String) =
        prefs(context).edit()
            .putString(KEY_SPOTIFY_PKCE_VERIFIER, verifier)
            .putString(KEY_SPOTIFY_PKCE_STATE, state)
            .apply()

    fun clearSpotifyPkce(context: Context) =
        prefs(context).edit().remove(KEY_SPOTIFY_PKCE_VERIFIER).remove(KEY_SPOTIFY_PKCE_STATE).apply()
}
