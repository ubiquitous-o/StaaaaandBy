package com.kazuto.standby.media

import android.service.notification.NotificationListenerService

/**
 * MediaSessionManager.getActiveSessions() を呼ぶには、有効化された
 * NotificationListenerService のコンポーネントが必要。
 * 通知そのものは扱わないので中身は空でよい。
 */
class NowPlayingListenerService : NotificationListenerService()
