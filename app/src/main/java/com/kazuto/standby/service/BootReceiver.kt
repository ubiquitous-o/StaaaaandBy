package com.kazuto.standby.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 再起動後に監視サービスを立ち上げ直す。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Settings.canDrawOverlays(context)
        ) {
            ChargingWatchService.start(context)
        }
    }
}
