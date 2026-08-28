package com.kazuto.standby.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kazuto.standby.R
import com.kazuto.standby.StandbyActivity

/**
 * 充電・画面状態を常駐監視して、条件が揃ったら StandbyActivity を起動する。
 *
 * 起動条件:
 *  - 充電開始時、画面が消えていたら起動
 *  - 画面が消えた時(サイドキー等)、充電中なら起動
 *
 * どちらも「他のアプリの上に表示」権限(SYSTEM_ALERT_WINDOW)が
 * バックグラウンドからのActivity起動許可として必要。
 */
class ChargingWatchService : Service() {

    companion object {
        private const val CHANNEL_ID = "charging_watch"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ChargingWatchService::class.java)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> maybeLaunch(delayMs = 800)
                Intent.ACTION_SCREEN_OFF -> {
                    // スタンバイ表示中にサイドキーで消灯した直後は再起動しない(ループ防止)
                    val sinceVisible =
                        SystemClock.elapsedRealtime() - StandbyActivity.lastVisibleAt
                    if (sinceVisible < 3_000) return
                    maybeLaunch(delayMs = 500)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun maybeLaunch(delayMs: Long) {
        handler.postDelayed({
            if (!isWirelessCharging() || !Settings.canDrawOverlays(this)) {
                return@postDelayed
            }
            // 基本は画面オフのときだけ起動する。
            // 例外: 直前までスタンバイが表示されていて(充電の一時停止で誤終了)、
            // まだロック画面のままなら、画面がついていても復帰させる
            if (isScreenOn() && !shouldRecoverFromChargingPause()) {
                return@postDelayed
            }
            startActivity(
                Intent(this, StandbyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }, delayMs)
    }

    private fun shouldRecoverFromChargingPause(): Boolean {
        val sinceVisible = SystemClock.elapsedRealtime() - StandbyActivity.lastVisibleAt
        val keyguardLocked =
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return sinceVisible < 30_000 && keyguardLocked
    }

    /**
     * Qi(ワイヤレス)充電中のみ true。ケーブル(AC/USB)では起動しない。
     * BatteryManager.isCharging は満充電(status=FULL)で false になるので、
     * sticky な ACTION_BATTERY_CHANGED からプラグ状態を見る。
     */
    private fun isWirelessCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private fun isScreenOn(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "充電スタンバイ監視",
                NotificationManager.IMPORTANCE_MIN
            )
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("充電スタンバイ監視中")
            .setOngoing(true)
            .build()
    }
}
