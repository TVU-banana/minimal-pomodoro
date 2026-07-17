# ADR 0003：增加仅测试用途的 benchmark 模块

## 决策

生产应用仍保持单一 `app` 模块；增加不进入发布包的 `benchmark` 测试模块，用于在参考设备上执行 Macrobenchmark 冷启动测量。

## 原因

Macrobenchmark 必须从独立测试 APK 驱动目标应用，无法在目标应用自身的生产模块中可靠执行。测试模块不会增加 Clock APK 的代码、权限或体积。

## 兼容性影响

普通开发者仍只安装 `app`。CI 可以编译 benchmark，但冷启动 p95 门禁需要连接 API 26 以上的真实设备或模拟器后执行 `connectedCheck`。
