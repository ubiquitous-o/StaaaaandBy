package com.kazuto.standby.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 監視サービスを立ち上げ直す:
 *  - 端末の再起動後(BOOT_COMPLETED)
 *  - アプリの更新後(MY_PACKAGE_REPLACED)。更新でプロセスごと止められるので、
 *    ユーザーがアプリを開き直さなくても監視が続くようにする
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (Settings.canDrawOverlays(context)) {
            ChargingWatchService.start(context)
        }
    }
}
