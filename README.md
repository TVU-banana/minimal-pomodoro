# Clock

Clock 是一款本地优先、完全开源、无广告的 Android 专注倒计时工具。

当前首个可用版本为 `1.0.0`。

默认任务总时长为 60 分钟，其中前 15 分钟是启动阶段，之后自动进入 45 分钟正式专注。预热时长可以修改或关闭。15 分钟只是帮助降低开始阻力的默认行为设计，并不是适用于所有人的神经科学定律。

## 原则

- 总时长包含预热时间。
- 不监测或判断用户是否专注。
- 不申请网络权限，不集成广告或分析 SDK。
- 倒计时由时间戳推导，不在后台每秒运行任务。
- 本地数据可以导出为稳定、带版本号的开放格式。

## 每日习惯

底栏「打卡」位于计时与记录之间。习惯拥有独立数据，不关联计时 Tags 或 Session。
支持创建、改名、排序、归档，以及按本地日期完成、撤销与历史补打；未来日期禁止打卡。
创建前日期显示「不适用」，不计入完成总数。归档隐藏当前入口并保留有效日期的历史。
每项习惯可设置每日 1–99 次；名称后的浅色小数字表示剩余次数，达标后才勾选。
支持逐次记录、撤销一次及历史补记；修改目标保留已记录次数和之前日期的目标。
完整 `.clockbackup` v4 包含次数与目标历史，仍可导入 v1/v2/v3；计时 CSV 保持原有内容。
实现规则、验证结果与界面截图见 [验收记录](docs/habits-implementation-and-verification.md)。

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

贡献代码前请同时阅读 `CONTRIBUTING.md`。版本变化记录见 `CHANGELOG.md`。

## 一键真机预览

手机首次开启“开发者选项”和“USB 调试”并授权这台 Mac 后，后续每次修改只需运行：

```shell
./scripts/run-preview.sh
```

脚本会自动执行 R8 Preview 构建、覆盖安装并启动 Clock，不再需要手动传输 APK。若连接了多个设备，可先设置 `ANDROID_SERIAL` 指定设备。

Android 11 及以上也可以使用系统“无线调试”，首次配对后无需数据线：

```shell
adb pair <手机显示的IP:配对端口>
adb connect <手机显示的IP:调试端口>
./scripts/run-preview.sh
```

Clock 是原生 Android 应用，浏览器不能直接运行 APK。电脑预览需要 Android 模拟器或一台通过 ADB 连接的安卓设备。

## 许可证

GPL-3.0-or-later。详见 `LICENSE`。
