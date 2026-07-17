# Clock

Clock 是一款本地优先、完全开源、无广告的 Android 专注倒计时工具。

默认任务总时长为 60 分钟，其中前 15 分钟是启动阶段，之后自动进入 45 分钟正式专注。预热时长可以修改或关闭。15 分钟只是帮助降低开始阻力的默认行为设计，并不是适用于所有人的神经科学定律。

## 原则

- 总时长包含预热时间。
- 不监测或判断用户是否专注。
- 不申请网络权限，不集成广告或分析 SDK。
- 倒计时由时间戳推导，不在后台每秒运行任务。
- 本地数据可以导出为稳定、带版本号的开放格式。

## 技术栈

- Kotlin、XML Views、自定义 Canvas
- Android API 26–37
- Room / SQLite
- AlarmManager 精确阶段提醒
- CSV、JSON/NDJSON、`.clockbackup`
- R8、Android Lint、JUnit、Espresso、Macrobenchmark

## 构建

要求 JDK 17、Android SDK 37 和 Gradle Wrapper。运行：

```shell
./gradlew test lint assembleRelease :benchmark:assembleDebug
```

## 许可证

GPL-3.0-or-later。详见 `LICENSE`。
