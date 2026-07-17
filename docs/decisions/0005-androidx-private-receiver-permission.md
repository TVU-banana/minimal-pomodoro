# ADR 0005：保留 AndroidX 的应用私有 Receiver 权限

## 决策

保留合并 Manifest 中由 AndroidX Core 自动生成的：

- `com.akira.clock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- 对该应用私有签名权限的 `uses-permission`

源码 Manifest 仍只声明 `POST_NOTIFICATIONS`、`USE_EXACT_ALARM` 与 `RECEIVE_BOOT_COMPLETED` 三项 Android 系统权限。

## 原因

该权限的保护级别为 `signature`，仅本应用及相同签名应用可持有，用于兼容旧 Android 版本上的未导出动态 Receiver。它不会授予网络、唤醒锁、媒体读取、外部存储或其他系统能力。强制从合并 Manifest 删除它会削弱 AndroidX 的广播保护。

## 兼容性影响

权限审计应区分 Android 系统权限与应用包名下的私有签名权限。发布门禁仍严格拒绝 `INTERNET`、`WAKE_LOCK`、`READ_MEDIA_IMAGES` 和 `MANAGE_EXTERNAL_STORAGE`。
