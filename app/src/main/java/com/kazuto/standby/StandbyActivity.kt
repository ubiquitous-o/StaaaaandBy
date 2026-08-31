package com.kazuto.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kazuto.standby.media.MediaSessionWatcher
import com.kazuto.standby.ui.StandbyScreen

/**
 * ロック画面の上に表示するスタンバイ画面。
 * ChargingWatchService が「充電開始」「サイドキー消灯」を検知して起動する。
 */
class StandbyActivity : ComponentActivity() {

    companion object {
        /**
         * この画面が最後に表示されていた時刻(elapsedRealtime)。
         * サイドキーで消灯した直後に ChargingWatchService が再起動をかけて
         * 無限ループになるのを防ぐために使う。
         */
        @Volatile
        var lastVisibleAt: Long = 0L
    }

    private lateinit var mediaWatcher: MediaSessionWatcher

    private val handler = Handler(Looper.getMainLooper())

    /**
     * パッドの位置ズレ等の一瞬の切断は無視しつつ、取り上げたらすぐ閉じるよう短めに待つ。
     * 満充電保護などによる長めの充電一時停止で誤終了した場合は、
     * ChargingWatchService 側の再接続リカバリでスタンバイに戻る。
     */
    private val delayedFinish = Runnable {
        if (!isPluggedIn()) finish()
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED ->
                    handler.postDelayed(delayedFinish, 1_500)
                Intent.ACTION_POWER_CONNECTED ->
                    handler.removeCallbacks(delayedFinish)
            }
        }
    }

    private fun isPluggedIn(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 既定は横向き固定(上下はセンサーで自動)。設定で縦向きも許可できる
        val allowPortrait = Prefs.allowPortrait(this)
        requestedOrientation = if (allowPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 縦→横の回転をアニメーションさせず、最初から横向きの絵で出す
        window.attributes = window.attributes.also {
            it.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }

        // ステータスバー・ナビゲーションバーを隠して全画面にする
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        mediaWatcher = MediaSessionWatcher(applicationContext)
        mediaWatcher.onResyncNeeded = ::resyncViaForeground
        mediaWatcher.start()

        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        })

        setContent {
            // 横向き限定のときは、起動直後の1〜2フレームが縦向きで描かれるので
            // 横向きになるまでは黒だけを出し、横になったらフェードインする。
            // 縦向き許可のときはどちらの向きでもすぐフェードインする
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AnimatedVisibility(
                    visible = allowPortrait || isLandscape,
                    enter = fadeIn(animationSpec = tween(durationMillis = 700)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 200))
                ) {
                    StandbyScreen(mediaWatcher = mediaWatcher)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lastVisibleAt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        lastVisibleAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onStop() {
        lastVisibleAt = SystemClock.elapsedRealtime()
        super.onStop()
        // 画面が消えた/他の画面に隠れたら裏に残さない。
        // ただし構成変更(折りたたみ姿勢・回転等)による再生成や、
        // 同期し直しのために自分で一瞬隠れたときは終了しない
        if (!isChangingConfigurations && !resyncing) {
            finish()
        }
    }

    /** 同期し直しで音楽アプリを一瞬前に出しているあいだ true(onStop で終了しないため) */
    private var resyncing = false

    private val endResync = Runnable { resyncing = false }

    /**
     * 他端末の鏡が固まったときの同期し直し。
     * Spotify は自分の画面が前に出たときにしか他端末の再生状態を取りに行かないので、
     * 音楽アプリを起動して、描画される前に自分を前に戻す。
     * 起動しただけで同期が走ることは実機で確認済み(ロック画面が一瞬ちらつく)。
     */
    private fun resyncViaForeground(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        resyncing = true
        handler.removeCallbacks(endResync)
        handler.postDelayed(endResync, 3_000)
        startActivity(
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
        handler.postDelayed({
            startActivity(
                Intent(this, StandbyActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        }, 300)
    }

    override fun onDestroy() {
        handler.removeCallbacks(delayedFinish)
        handler.removeCallbacks(endResync)
        unregisterReceiver(powerReceiver)
        mediaWatcher.stop()
        super.onDestroy()
    }
}
