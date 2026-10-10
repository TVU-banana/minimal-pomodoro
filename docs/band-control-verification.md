# 手环遥控开发验证

日期：2026-10-06。实现契约见 [band-control.md](band-control.md)。

## 已完成的证据

- Clock 控制入口、令牌、阶段投影与 UTF-8 codec 已实现；所有命令经 TimerCoordinator。
- 输入保护、重复请求、ID 冲突、缓存淘汰、跨进程令牌失效及预热暂停投影有 JVM 测试。
- Kotlin 请求/响应 fixture 与手环 JS fixture 相同，编码与解码通过。
- 手环 Node 测试 19/19；模拟开始/暂停/继续/终止、断连、延迟、过期和重连读取通过。
- JVM 单元测试 116/116；普通 Debug Lint 0 errors / 100 warnings，BandPilot Lint
  0 errors / 99 warnings；未把 Lint 无错误称为无警告。
- APK 签名验证通过，RPK 内嵌证书与 APK 证书 SHA-256 相同：
  `1f9555611f754b0dcf96f69b131bd5b6603f68d137f591038f60659763d04871`。
- 普通 Debug 与带 SDK 的独立 BandPilot APK 编译通过；SDK 默认关闭。
- Wear SDK 二进制接口已核对，可选适配器编译通过；联调页面有显式授权连接入口。
- 模拟与真实 interconnect 模式 RPK 编译通过，默认源码还原为模拟模式。
- 联调 RPK 包名为 com.akira.clock.bandpilot，采用本机 Android debug 证书。
- 临时 PEM 清理完成；AAR 和生成产物不进入 Git。未创建提交，原有工作区修改保留。

## 未完成的证据

- ADB 设备列表为空，未安装 APK，未运行 Android 仪器测试或手机交互验收。
- 当前应用目录/电脑控制列表未找到 AIoT IDE；未运行模拟器，未验证布局和真实 Vela 执行。
- 未在 9 Pro 验证 interconnect 编码、可用性、授权、容量、锁屏、进程退出和重连。
- 手机退出后 SDK 无自动唤醒保证，当前实现需重新进入设置连接。
- JVM/Node 测试不证明真实 Room 控制事务失败恢复、系统闹钟或后台接收已验收。

## 下一步

USB 连接手机并授权调试；保留 9 Pro 与小米运动健康配对。提供手机/固件/运动健康版本，
以及 AIoT IDE 的位置。安装独立联调 APK 与同签名 RPK，创建测试任务，先验证 STATE
往返，再验证开始/暂停/继续/结束和手机熄屏。任何失败先留证，不覆盖正式 Clock。
