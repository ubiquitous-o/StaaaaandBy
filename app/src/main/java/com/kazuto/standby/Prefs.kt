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
}
