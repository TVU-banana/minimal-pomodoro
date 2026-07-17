# 发布与可复现构建

## 前置条件

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0

## 验证

```shell
./gradlew test lint assembleRelease :benchmark:assembleDebug
```

Release 构建必须开启 R8 与资源裁剪；CI 会拒绝超过 10 MiB 的通用 APK，并检查 INTERNET、WAKE_LOCK 和 MANAGE_EXTERNAL_STORAGE 等禁用权限。

签名密钥不得提交仓库。公开发行时分别保存 AAB/APK、SHA-256、Git commit 和第三方依赖清单。首选 GitHub Releases 与 F-Droid；Google Play 发布前完成 Exact Alarm 权限用途声明和 Data Safety 表单。
