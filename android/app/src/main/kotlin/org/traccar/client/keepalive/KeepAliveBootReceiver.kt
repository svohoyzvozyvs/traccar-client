package org.traccar.client.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机广播接收器，用于监听设备开机自启事件。
 * 在系统开机完成时，自动恢复无障碍保活服务、重新锁定 OOM Adj 并拉起看门狗。
 */
class KeepAliveBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TraccarBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received boot broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.i(TAG, "System boot completed. Initializing KeepAlive policies...")
                    KeepAliveManager.onAppStart(context.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in KeepAliveBootReceiver onAppStart: ${t.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
