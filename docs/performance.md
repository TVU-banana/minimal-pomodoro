# 性能预算

- Release 通用 APK：目标 8 MiB，10 MiB 为 CI 硬上限。
- 无前台 Service、WakeLock、网络请求和每秒数据库写入。
- 数字/圆环界面每秒刷新一次；沙漏最高 30 FPS，离屏、暂停或减少动画时停止。
- 背景图最大按 1440px 边界采样并转 WebP；不缓存原始大图。
- 使用 Macrobenchmark 测量参考设备冷启动 p95，目标不超过 600ms。

在连接的 API 26+ 参考设备上运行：

```shell
./gradlew :benchmark:connectedDebugAndroidTest
```

`StartupBenchmark` 执行 10 次冷启动采样；`BaselineProfileGenerator` 采集启动路径 Profile。公开发布前应把参考设备型号、系统版本、p95 和 `dumpsys meminfo` 结果记录到发布说明，不能用放宽预算替代定位。
