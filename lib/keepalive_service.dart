import 'dart:io';
import 'package:flutter/services.dart';

/// 保活状态数据模型
class KeepAliveStatus {
  final bool hasRoot;
  final int? oomScoreAdj;
  final bool isWatchdogRunning;
  final bool isAccessibilityActive;
  final bool isXposedActive;
  final bool isIgnoringBatteryOptimizations;
  final bool rootOomLockConfigured;
  final bool rootWhitelistConfigured;
  final bool rootWatchdogConfigured;
  final bool accessibilityConfigured;

  KeepAliveStatus({
    this.hasRoot = false,
    this.oomScoreAdj,
    this.isWatchdogRunning = false,
    this.isAccessibilityActive = false,
    this.isXposedActive = false,
    this.isIgnoringBatteryOptimizations = false,
    this.rootOomLockConfigured = false,
    this.rootWhitelistConfigured = false,
    this.rootWatchdogConfigured = false,
    this.accessibilityConfigured = false,
  });

  factory KeepAliveStatus.fromMap(Map<dynamic, dynamic> map) {
    return KeepAliveStatus(
      hasRoot: map['hasRoot'] == true,
      oomScoreAdj: map['oomScoreAdj'] as int?,
      isWatchdogRunning: map['isWatchdogRunning'] == true,
      isAccessibilityActive: map['isAccessibilityActive'] == true,
      isXposedActive: map['isXposedActive'] == true,
      isIgnoringBatteryOptimizations: map['isIgnoringBatteryOptimizations'] == true,
      rootOomLockConfigured: map['rootOomLockConfigured'] == true,
      rootWhitelistConfigured: map['rootWhitelistConfigured'] == true,
      rootWatchdogConfigured: map['rootWatchdogConfigured'] == true,
      accessibilityConfigured: map['accessibilityConfigured'] == true,
    );
  }
}

/// 保活服务通信接口，用于 Flutter 与 Android 原生保活中枢交互
class KeepAliveService {
  static const MethodChannel _channel = MethodChannel('org.traccar.client/keepalive');

  static bool get isSupported => Platform.isAndroid;

  /// 获取当前保活能力与配置状态
  static Future<KeepAliveStatus> getStatus() async {
    if (!isSupported) return KeepAliveStatus();
    try {
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStatus');
      if (res != null) {
        return KeepAliveStatus.fromMap(res);
      }
    } catch (_) {}
    return KeepAliveStatus();
  }

  /// 更新保活配置项并立即应用
  static Future<KeepAliveStatus> applySettings({
    bool? rootOomLock,
    bool? rootWhitelist,
    bool? rootWatchdog,
    bool? accessibility,
  }) async {
    if (!isSupported) return KeepAliveStatus();
    final args = <String, dynamic>{};
    if (rootOomLock != null) args['rootOomLock'] = rootOomLock;
    if (rootWhitelist != null) args['rootWhitelist'] = rootWhitelist;
    if (rootWatchdog != null) args['rootWatchdog'] = rootWatchdog;
    if (accessibility != null) args['accessibility'] = accessibility;

    try {
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('applySettings', args);
      if (res != null) {
        return KeepAliveStatus.fromMap(res);
      }
    } catch (_) {}
    return await getStatus();
  }

  /// 请求忽略电池优化
  static Future<void> requestIgnoreBatteryOptimizations() async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('requestIgnoreBatteryOptimizations');
    } catch (_) {}
  }

  /// 跳转至系统无障碍设置页
  static Future<void> openAccessibilitySettings() async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  /// 通过 Root 权限免手动一键静默激活无障碍服务
  static Future<bool> enableAccessibilityViaRoot() async {
    if (!isSupported) return false;
    try {
      final res = await _channel.invokeMethod<bool>('enableAccessibilityViaRoot');
      return res == true;
    } catch (_) {
      return false;
    }
  }
}
