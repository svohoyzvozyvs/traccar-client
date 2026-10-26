package org.traccar.client.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 静默唤醒服务，负责在被看门狗唤醒时提供前台承接并复活主链路。
 */
class KeepAliveWakeupService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "traccar_keepalive_channel"
            val channel = NotificationChannel(
                channelId,
                "Traccar KeepAlive Wakeup",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Internal channel used for silent keep-alive wakeups"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Traccar Client")
                .setContentText("Keep-alive service active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()

            // Android 14+ (targetSdk >= 34) 必须显式传入 foregroundServiceType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    WAKEUP_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(WAKEUP_NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra("source") ?: "unknown"
        Log.d(TAG, "KeepAliveWakeupService triggered by: $source")

        // 如果开启了 Root，在此处再次确保 OOM Score 锁定为 -1000
        if (KeepAliveManager.isRootOomLockEnabled(this)) {
            RootShellExecutor.lockOomScoreAdj()
        }

        // 短暂拉起后可自然结束自身前台（避免多重前台通知）
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TraccarWakeup"
        private const val WAKEUP_NOTIFICATION_ID = 9527
    }
}
