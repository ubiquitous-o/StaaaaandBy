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
 * また、優先度が落ちた状態では Connect の同期が静かに切れることがある。
 *
 * 対策として、スタンバイ表示中はその音楽アプリの公開サービスに bindService で
 * 繋ぎっぱなしにする。フォアグラウンドのアプリに bind されたプロセスは背景扱いに
 * ならず、死んでいれば bind が即座に起こす。Binder 自体は使わない。
 */
class MusicAppKeepAlive(private val context: Context) {

    companion object {
        private const val TAG = "StaaaaandBy"

        /**
         * 繋ぐ先の優先順。まずシステム自身が常時 bind している MediaRoute2ProviderService、
         * 無ければ Android Auto 等が使う MediaBrowserService。
         */
        private val BIND_ACTIONS = listOf(
            "android.media.MediaRoute2ProviderService",
            "android.media.browse.MediaBrowserService",
        )
    }

    private var boundPackage: String? = null
    private var connection: ServiceConnection? = null

    /** 指定パッケージのサービスに繋ぐ。別のアプリに繋いでいたら繋ぎ替える。 */
    fun bind(packageName: String?) {
        if (packageName == boundPackage) return
        unbind()
        if (packageName == null) return

        val (action, service) = findBindableService(packageName) ?: run {
            Log.i(TAG, "keepAlive: no bindable service in $packageName")
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
        val intent = Intent(action).setComponent(service)
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

    private fun findBindableService(packageName: String): Pair<String, ComponentName>? {
        for (action in BIND_ACTIONS) {
            val intent = Intent(action).setPackage(packageName)
            val info = context.packageManager.queryIntentServices(intent, 0)
                .firstOrNull()?.serviceInfo ?: continue
            return action to ComponentName(info.packageName, info.name)
        }
        return null
    }
}
