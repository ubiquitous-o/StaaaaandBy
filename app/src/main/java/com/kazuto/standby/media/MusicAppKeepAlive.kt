package com.kazuto.standby.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * 音楽アプリのプロセスを生かしておく。
 *
 * Spotify Connect などで他の端末の再生を映しているとき、スマホ側の音楽アプリは
 * 音を出していないので「バックグラウンドの待機アプリ」扱いになり、
 * メモリ回収や Samsung の自動最適化で殺される。殺されると鏡が消えて表示が止まる。
 *
 * 対策として、スタンバイ表示中はその音楽アプリの MediaBrowserService
 * (Android Auto 等が接続する公開サービス)に bindService で繋ぎっぱなしにする。
 * フォアグラウンドのアプリに bind されたプロセスは背景扱いにならず、
 * 死んでいれば bind が即座に起こす。Binder 自体は使わない。
 */
class MusicAppKeepAlive(private val context: Context) {

    companion object {
        private const val TAG = "StaaaaandBy"
        private const val ACTION_MEDIA_BROWSER = "android.media.browse.MediaBrowserService"
    }

    private var boundPackage: String? = null
    private var connection: ServiceConnection? = null

    /** 指定パッケージの MediaBrowserService に繋ぐ。別のアプリに繋いでいたら繋ぎ替える。 */
    fun bind(packageName: String?) {
        if (packageName == boundPackage) return
        unbind()
        if (packageName == null) return

        val service = findMediaBrowserService(packageName)
        if (service == null) {
            Log.i(TAG, "keepAlive: no MediaBrowserService in $packageName")
            return
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                Log.i(TAG, "keepAlive: connected ${name.flattenToShortString()}")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // プロセスが死んだ。bind は生きているのでシステムが再起動して再接続する
                Log.w(TAG, "keepAlive: disconnected ${name.flattenToShortString()} (process died)")
            }

            override fun onNullBinding(name: ComponentName) {
                Log.i(TAG, "keepAlive: null binding ${name.flattenToShortString()} (still bound)")
            }

            override fun onBindingDied(name: ComponentName) {
                Log.w(TAG, "keepAlive: binding died ${name.flattenToShortString()}, rebinding")
                val pkg = boundPackage
                unbind()
                bind(pkg)
            }
        }
        val intent = Intent(ACTION_MEDIA_BROWSER).setComponent(service)
        val ok = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse { e ->
            Log.w(TAG, "keepAlive: bindService threw for $packageName", e)
            false
        }
        if (ok) {
            boundPackage = packageName
            connection = conn
            Log.i(TAG, "keepAlive: bound to ${service.flattenToShortString()}")
        } else {
            runCatching { context.unbindService(conn) }
            Log.w(TAG, "keepAlive: bindService returned false for $packageName")
        }
    }

    fun unbind() {
        connection?.let {
            runCatching { context.unbindService(it) }
            Log.i(TAG, "keepAlive: unbound from $boundPackage")
        }
        connection = null
        boundPackage = null
    }

    /** 繋ぎ直す。鏡が止まっているときの刺激として使う。 */
    fun rebind() {
        val pkg = boundPackage ?: return
        Log.i(TAG, "keepAlive: rebind $pkg")
        unbind()
        bind(pkg)
    }

    private fun findMediaBrowserService(packageName: String): ComponentName? {
        val intent = Intent(ACTION_MEDIA_BROWSER).setPackage(packageName)
        val resolved = context.packageManager.queryIntentServices(intent, 0)
        val info = resolved.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }
}
