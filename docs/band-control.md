# 手环遥控计时：开发契约

状态：2026-10-05 开发版，真机通信未验证。手机 Room 为唯一事实来源。
独立于 smartband-proj 的历史同步 v1；本协议不上传手环本地会话。

## 范围与决策

手环选择手机的 countdown preset，发送 START/PAUSE/RESUME/ABORT，显示手机状态。
保留手机任务的预热设置，阶段必须准确显示，不在手环另建计时会话。
STOPWATCH 暂不提供遥控。通信经 system.interconnect；不开放网络或公开广播入口。
官方文档：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html
核验日期：2026-10-05；9 Pro 无明确支持表，SDK、编码和后台行为待真机探针。

## JSON 契约

请求：`{protocol:"clock-control",v:1,id:"request-id",action:"STATE"}`。
action 为 STATE / PRESETS / START / PAUSE / RESUME / ABORT。
写命令必带 expectedToken；START 必带 presetId；其他写命令必带 sessionId。
ID/token 为非空、最多 128 字符字符串。UTF-8 单请求最多 4096 字节，此为
应用输入保护预算，不是平台容量声明。PRESETS 按 offset 分页，每页最多 4 项。
响应携带相同 protocol/v/id、code、state，PRESETS 另含 presets 和 nextOffset。
code 为 OK / STALE / INVALID_STATE / NOT_FOUND / ALARM_UNAVAILABLE / CONFLICT。
无效 JSON、版本、UTF-8、字段或超限输入拒绝且不得执行，传输层必须报告协议错误。
state：token、sessionId（可空）、stage（IDLE/WARMUP/FOCUS/PAUSED/COMPLETED/ABORTED）、
remainingMs、running、taskName。剩余时间由手机单调时钟计算，不传 elapsedRealtime 原值。

## 并发和重试

控制令牌是进程内、不透明的乐观锁，每次手机状态发布时更新；所有来源的命令
在同一个 TimerCoordinator mutex 下检查令牌并执行。启动、恢复、进程重启后旧令牌失效。
相同 ID/内容在进程内缓存中返回原执行结果；同 ID 不同内容返回 CONFLICT。
缓存丢失或淘汰后旧令牌返回 STALE，禁止自动换新令牌重发写命令；先拉取状态并由
用户明确再次操作。这保证防重复执行，但不承诺跨进程返回原 ACK，也不提供离线命令队列。
写入或系统效果异常时先使旧令牌失效，避免不确定结果被重试执行。
OK 必须在 Room 提交后返回。令牌只用于并发，不是认证凭据。

手环一次仅允许一个请求；等待超时或断连后标记结果未知，重连先 STATE。
收到响应时以请求发送到接收的 RTT/2 近似补偿，仅显示短时推算。
倒计时到 0 时不得自行宣告完成；STATE 确认手机阶段。
超过校准有效期或重开页面后显示未同步并禁止控制，直到 STATE 成功。

## 验证与交付

1. Kotlin/JS 使用同一 JSON fixture 验证协议和阶段投影。
2. FakeTransport 验证断连、超时、重复、过期响应和时钟跳变。
3. AIoT IDE 模拟器只证明 UI/模拟逻辑；真实手机接收必须用正式 SDK。
4. 真机验证 HELLO/ECHO、同签名、锁屏、进程退出、重连和计时差。
5. Android 提交前运行完整测试和 Lint；不自动提交当前脏工作区。

手机暂不需要连接。真机阶段需 USB 调试授权；手环保持与小米运动健康配对。
缺少正式 Xiaomi Wear SDK 时不能验收蓝牙控制，禁止以 FakeTransport 成功替代。

## 2026-10-06 联调构建

SDK 1.4 已从官方 interconnect 页面附录的测试包取得：
https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip
`python3 scripts/setup-band-sdk.py` 只提取并校验 AAR，不提取官方 demo 的签名材料。
AAR SHA-256：`9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195`。
二进制 API 与 PDF 存在差异：实际 sendMessage 返回 Task<Void>，removeListener 只接受 nodeId。
本适配层按实际 AAR 编译验证。归档未发现许可声明，当前仅用于本地实验；发布前核对分发条件。

普通构建不含 SDK。联调：

```sh
./gradlew :app:assembleBandPilot :app:testDebugUnitTest :app:lintBandPilot -PbandSdk=true
```

APK 位于 `app/build/outputs/apk/bandPilot/app-bandPilot.apk`，包名
`com.akira.clock.bandpilot`，使用 Android debug 签名和独立数据库。首次需创建测试任务。
设置页点击“连接手环（实验功能）”申请授权并注册 SDK listener；注册成功仅说明 SDK
回调成功，不是手环命令已执行。监听存活于应用进程，退出/进程重启后需再次连接。
当前没有自动后台唤醒承诺，手机熄屏和系统杀进程行为必须单独验证。

手环仓库运行 `node scripts/build-remote.mjs --device` 生成同包名 RPK，构建时临时
转换本机 Android debug 证书；结束后清理并还原配置。不接触正式 release 私钥。
模拟/真机产物分别保存于 `smartband-proj/artifacts/remote-control/`，避免构建清理相互覆盖。
默认源码仍启用模拟器模式；真实模式适配 `system.interconnect`，通过 STATE 往返探针
开始验收。正式 Clock 与手环应用升级及签名另行处理，不自动覆盖现有安装。
