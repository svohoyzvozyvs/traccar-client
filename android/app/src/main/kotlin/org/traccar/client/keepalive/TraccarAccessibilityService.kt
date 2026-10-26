package org.traccar.client.keepalive

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Traccar Client 专用无障碍保活锚定服务（借鉴自 xinyi-relay 专用保活服务）。
 * 系统对启用的无障碍服务具备系统级重启保证（一旦因异常或极端内存被杀，系统会自动复活）。
 * 当此服务连接或保活时，可周期性保障主进程定位链路处于活跃状态。
 */
class TraccarAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                checkAndWakeup()
            } finally {
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "TraccarAccessibilityService connected.")
        handler.post(heartbeatRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无障碍保活锚定服务，无需处理任何视图事件，保护用户隐私
    }

    override fun onInterrupt() {
        Log.w(TAG, "TraccarAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "TraccarAccessibilityService destroyed.")
    }

    private fun checkAndWakeup() {
        try {
            // 无障碍服务本身驻留在主进程，享有系统级自动重启保障；
            // 此处仅周期性确保 Root OOM Score 保持 -1000 锁定，无需频繁拉起前台服务
            if (KeepAliveManager.isRootOomLockEnabled(applicationContext)) {
                RootShellExecutor.lockOomScoreAdj()
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkAndWakeup error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TraccarAccessibility"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // 每分钟一次心跳

        var isRunning: Boolean = false
            private set

        fun isAccessibilityEnabled(context: Context): Boolean {
            return isRunning || isAccessibilitySettingsEnabled(context)
        }

        private fun isAccessibilitySettingsEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${TraccarAccessibilityService::class.java.canonicalName}"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val colonSplitter = enabledServices.split(":")
            for (service in colonSplitter) {
                if (service.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
