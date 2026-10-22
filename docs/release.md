# 发布与签名

## 构建环境

- Gradle Daemon：JDK 21；源码目标：JVM 17。
- Android SDK Platform 37.0，Build Tools 36.0.0。
- 使用仓库 Gradle Wrapper、依赖版本目录与 lockfile。
- Wrapper JAR 与发行包具有 SHA-256 校验，CI Actions 固定到提交。

## 发布前验证

```sh
./gradlew test lint assembleDebug assemblePreview assembleRelease assembleDebugAndroidTest :benchmark:assembleDebug
python3 scripts/check-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
python3 scripts/check-repository.py --history
gitleaks git --redact --log-opts=--all .

# 专用测试模拟器；不要在个人数据所在的应用安装上运行
./gradlew :app:connectedDebugAndroidTest
```

无签名 Release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，不能直接安装。
Release 开启 R8 与资源裁剪；APK 必须小于等于 12 MiB，禁止 INTERNET、WAKE_LOCK、
READ_MEDIA_IMAGES 与 MANAGE_EXTERNAL_STORAGE 权限。

当前 Release 约 10.17 MiB，其中中文海报字体压缩后约 8.28 MiB。
最初版本的 10 MiB 预算已被现有字体功能超过，现将预算明确设为 12 MiB。
这保留现有字形覆盖与视觉效果，同时为后续增长提供可执行的上限；不以删字形来通过检查。

上述措施支持可复现验证流程，不宣称不同主机已实现逐字节一致的 APK。
Macrobenchmark 是独立的性能测量，应记录模拟器/真机、温度、动画与测量配置。

## 正式签名

构建脚本不生成密钥，不修改源码，也不存储签名口令。
从密码管理器或受保护的 CI 环境注入以下四项环境变量：

| 变量 | 内容 |
| --- | --- |
| `CLOCK_KEYSTORE_FILE` | 现有密钥库的绝对路径，或相对于仓库根目录的路径 |
| `CLOCK_KEYSTORE_PASSWORD` | 密钥库口令 |
| `CLOCK_KEY_ALIAS` | 原有签名密钥 alias |
| `CLOCK_KEY_PASSWORD` | 签名密钥口令 |

必须全部设置或全部不设置；部分设置会使配置失败。
设置后运行 `./build_apk.sh`，产物为 `app/build/outputs/apk/release/app-release.apk`。
正式签名完成后，同样使用 `scripts/check-apk.py` 检查该 APK。

保持既有签名私钥与证书，才能升级已有安装。修改密钥库口令不等于轮换签名证书；
不要为了清理仓库而删除或重建现有密钥。若私钥曾公开，需要单独评估证书迁移与升级兼容性。
曾写入源码的口令应在密码管理器中替换，并修改本地密钥库口令；不要在命令行参数、
工单、终端日志或聊天中粘贴真实凭据。

公开发行时保存 APK/AAB、SHA-256、Git commit、构建工具链与第三方依赖清单。
GitHub Actions 仅上传测试报告；未签名构建不视为正式发行。发布商店前须核对当时的
Exact Alarm 用途与隐私声明要求。
