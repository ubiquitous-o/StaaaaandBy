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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.abs

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
                Intent.ACTION_SCREEN_ON -> stopOrientationWatch()
                Intent.ACTION_POWER_DISCONNECTED -> stopOrientationWatch()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopOrientationWatch()
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
            startOrientationWatch()
        }, delayMs)
    }

    private var orientationListener: SensorEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Qi充電中+画面オフの間、加速度センサーを監視して、端末が物理的に横向き
     * (重力がX軸=短辺方向に優勢)になった瞬間にスタンバイを起動する。
     * 縦置き(Y軸優勢)や平置き(Z軸優勢)のままなら起動せず監視を続ける。
     * 充電が外れる・画面がつくと監視をやめる。
     * 画面オフ中もセンサーイベントが届くよう部分ウェイクロックを持つ(充電中のみ、上限1時間)。
     */
    private fun startOrientationWatch() {
        if (orientationListener != null) return
        val sensorManager = getSystemService(SensorManager::class.java)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || sensor == null) {
            // センサーが無い端末では向き判定を諦めて従来通り起動する
            launchStandby()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StaaaaandBy:orientationWatch")
            .apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isWirelessCharging() ||
                    (isScreenOn() && !shouldRecoverFromChargingPause())
                ) {
                    stopOrientationWatch()
                    return
                }
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (abs(x) > abs(y) && abs(x) > abs(z)) {
                    stopOrientationWatch()
                    launchStandby()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        orientationListener = listener
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopOrientationWatch() {
        orientationListener?.let {
            getSystemService(SensorManager::class.java)?.unregisterListener(it)
        }
        orientationListener = null
        wakeLock?.release()
        wakeLock = null
    }

    private fun launchStandby() {
        startActivity(
            Intent(this, StandbyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
