package org.traccar.client

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.traccar.client.keepalive.KeepAliveManager

class MainActivity : FlutterActivity() {

    private val CHANNEL = "org.traccar.client/keepalive"
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动时自动初始化保活状态（如 OOM 锁与看门狗）
        scope.launch {
            KeepAliveManager.onAppStart(applicationContext)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatus" -> {
                    scope.launch {
                        try {
                            val status = KeepAliveManager.getStatus(applicationContext)
                            result.success(status)
                        } catch (e: Throwable) {
                            result.error("ERROR", e.message, null)
                        }
                    }
                }
                "applySettings" -> {
                    val settings = call.arguments as? Map<String, Any> ?: emptyMap()
                    scope.launch {
                        try {
                            val updated = KeepAliveManager.applySettings(applicationContext, settings)
                            result.success(updated)
                        } catch (e: Throwable) {
                            result.error("ERROR", e.message, null)
                        }
                    }
                }
                "requestIgnoreBatteryOptimizations" -> {
                    KeepAliveManager.requestIgnoreBatteryOptimizations(this)
                    result.success(true)
                }
                "openAccessibilitySettings" -> {
                    KeepAliveManager.openAccessibilitySettings(this)
                    result.success(true)
                }
                "enableAccessibilityViaRoot" -> {
                    scope.launch {
                        try {
                            val ok = KeepAliveManager.enableAccessibilityViaRoot(applicationContext)
                            result.success(ok)
                        } catch (e: Throwable) {
                            result.error("ERROR", e.message, null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
