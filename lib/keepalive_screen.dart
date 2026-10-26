import 'dart:io';

import 'package:flutter/material.dart';
import 'package:traccar_client/keepalive_service.dart';

class KeepAliveScreen extends StatefulWidget {
  const KeepAliveScreen({super.key});

  @override
  State<KeepAliveScreen> createState() => _KeepAliveScreenState();
}

class _KeepAliveScreenState extends State<KeepAliveScreen> with WidgetsBindingObserver {
  KeepAliveStatus _status = KeepAliveStatus();
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  Future<void> _refreshStatus() async {
    setState(() => _loading = true);
    final status = await KeepAliveService.getStatus();
    if (!mounted) return;
    setState(() {
      _status = status;
      _loading = false;
    });
  }

  Widget _buildTierBanner() {
    String title;
    String subtitle;
    IconData icon;
    Color color;

    if (_status.isXposedActive) {
      title = 'LSPosed 系统级免死模式';
      subtitle = '已挂载至 system_server，享有系统级 OomAdjuster 优先级锁定与防杀保护';
      icon = Icons.verified_user;
      color = Colors.deepPurple;
    } else if (_status.hasRoot) {
      title = 'Root 特权级增强模式';
      subtitle = '已获取 Root (su) 权限，支持 Linux OOM -1000 锁死、系统待机白名单与独立看门狗';
      icon = Icons.security;
      color = Colors.teal;
    } else {
      title = '应用层兼容保活模式';
      subtitle = '非 Root 模式：依托无障碍服务锚点、前台通知与电池优化豁免保障运行';
      icon = Icons.shield_outlined;
      color = Colors.blue;
    }

    return Card(
      color: color.withValues(alpha: 0.12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: color.withValues(alpha: 0.4)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(icon, size: 40, color: color),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: color,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppLayerSection() {
    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              '应用层保活（全设备兼容）',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
            ),
          ),
          SwitchListTile(
            title: const Text('专用无障碍服务锚定'),
            subtitle: Text(
              _status.isAccessibilityActive
                  ? '已启用并连接。配备【开机自动恢复 + 独立看门狗 30 秒定时巡检】，即使被系统管家意外关闭也能秒级自动重新使能。'
                  : '未启用。利用 Android 框架在无障碍服务异常退出后自动拉起的特性进行托底保活。',
            ),
            value: _status.isAccessibilityActive,
            onChanged: (bool value) async {
              if (value) {
                if (_status.hasRoot) {
                  final useRoot = await showDialog<bool>(
                    context: context,
                    builder: (context) => AlertDialog(
                      title: const Text('激活无障碍服务'),
                      content: const Text('检测到设备具备 Root 权限，是否通过 Root 权限一键静默激活无障碍保活服务？无需在系统设置中反复查找。'),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('手动跳转设置'),
                        ),
                        FilledButton(
                          onPressed: () => Navigator.pop(context, true),
                          child: const Text('Root 一键激活'),
                        ),
                      ],
                    ),
                  );
                  if (useRoot == true) {
                    final ok = await KeepAliveService.enableAccessibilityViaRoot();
                    if (!mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text(ok ? '已通过 Root 权限成功激活无障碍保活服务！' : '激活失败，请手动前往设置开启')),
                    );
                    _refreshStatus();
                    return;
                  }
                }
                await KeepAliveService.openAccessibilitySettings();
              } else {
                await KeepAliveService.openAccessibilitySettings();
              }
            },
          ),
          ListTile(
            title: const Text('忽略系统电池优化'),
            subtitle: Text(
              _status.isIgnoringBatteryOptimizations
                  ? '已加入电池优化白名单，息屏后系统不会强行冻结网络与唤醒。'
                  : '未开启，息屏待机状态下可能被系统 Doze 机制对齐限制定位。点击申请。',
            ),
            trailing: _status.isIgnoringBatteryOptimizations
                ? const Icon(Icons.check_circle, color: Colors.green)
                : const Icon(Icons.arrow_forward_ios, size: 16),
            onTap: () async {
              await KeepAliveService.requestIgnoreBatteryOptimizations();
              await Future.delayed(const Duration(seconds: 1));
              _refreshStatus();
            },
          ),
        ],
      ),
    );
  }

  Widget _buildRootSection() {
    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              children: [
                const Text(
                  'Root 特权保活',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: _status.hasRoot ? Colors.green.withValues(alpha: 0.15) : Colors.grey.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    _status.hasRoot ? 'Root 已就绪' : '未检测到 Root',
                    style: TextStyle(
                      fontSize: 11,
                      color: _status.hasRoot ? Colors.green[800] : Colors.grey[700],
                    ),
                  ),
                ),
              ],
            ),
          ),
          if (!_status.hasRoot)
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Text(
                '当前设备未授予或不具备 Root 权限。Root 专属能力（OOM 锁死、系统待机白名单、看门狗）需 Magisk / KernelSU / APatch 授权后可用。',
                style: TextStyle(fontSize: 13, color: Colors.grey),
              ),
            )
          else ...[
            SwitchListTile(
              title: const Text('Linux OOM Score 锁定 (-1000)'),
              subtitle: Text(
                '通过 su 将当前进程的 oom_score_adj 强制锁定为 -1000（享受与 init 守护进程同级别的 Linux 内核免死金牌）。\n当前内核 Adj 值: ${_status.oomScoreAdj ?? '未知'}',
              ),
              value: _status.rootOomLockConfigured,
              onChanged: (bool val) async {
                await KeepAliveService.applySettings(rootOomLock: val);
                _refreshStatus();
              },
            ),
            SwitchListTile(
              title: const Text('Doze 与待机限制底层放行'),
              subtitle: const Text(
                '通过 dumpsys 和 cmd appops 深度注放：放行后台运行限制、强制将应用设置为 Active 活跃桶并加入休眠白名单。',
              ),
              value: _status.rootWhitelistConfigured,
              onChanged: (bool val) async {
                await KeepAliveService.applySettings(rootWhitelist: val);
                _refreshStatus();
              },
            ),
            SwitchListTile(
              title: const Text('独立 Root 看门狗守护进程'),
              subtitle: Text(
                '在后台运行脱离前台的独立 nohup Shell 守护脚本，当 Traccar Client 遭遇意外退出或强杀时秒级自动重新拉起。\n看门狗当前状态: ${_status.isWatchdogRunning ? '运行中 (已防护)' : '未运行'}',
              ),
              value: _status.rootWatchdogConfigured,
              onChanged: (bool val) async {
                await KeepAliveService.applySettings(rootWatchdog: val);
                _refreshStatus();
              },
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildXposedSection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text(
                  'LSPosed / Xposed 系统级免死钩子',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: _status.isXposedActive ? Colors.deepPurple.withValues(alpha: 0.15) : Colors.grey.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    _status.isXposedActive ? '已激活' : '未激活',
                    style: TextStyle(
                      fontSize: 11,
                      color: _status.isXposedActive ? Colors.deepPurple : Colors.grey[700],
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _status.isXposedActive
                  ? '✅ LSPosed 模块已在作用域中生效！已挂载至 system_server，提供 OomAdjuster 优先级锁定与 AMS 杀进程信号拦截防护。'
                  : '如需启用终极防杀能力，请在 LSPosed 管理器中启用【Traccar Client】模块，并将作用域勾选为【系统框架 (android)】。重启后即可自动激活。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) {
      return Scaffold(
        appBar: AppBar(title: const Text('后台保活设置')),
        body: const Center(
          child: Text('后台保活功能仅在 Android 平台可用'),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('后台保活设置'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新状态',
            onPressed: _refreshStatus,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refreshStatus,
              child: ListView(
                padding: const EdgeInsets.all(12),
                children: [
                  _buildTierBanner(),
                  const SizedBox(height: 12),
                  _buildAppLayerSection(),
                  const SizedBox(height: 12),
                  _buildRootSection(),
                  const SizedBox(height: 12),
                  _buildXposedSection(),
                  const SizedBox(height: 24),
                ],
              ),
            ),
    );
  }
}
