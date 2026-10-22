# 正式专注、标签管理和设置页调整

## 行为与兼容性

- 时间块 `totalDurationMs` 表示正式专注，预热为额外阶段。60 分钟正式专注和 5 分钟
  状态预热会运行 65 分钟，只累计 60 分钟正式专注。
- `TimerCommand.Start.forTimeBlock` 在启动边界将两个时长相加。reducer、checkpoint 及
  session 的计划总时长继续保留原有的含预热语义，旧 checkpoint 不重新解释、不迁移。
- 记录、海报和每日目标按正式专注计算。折线图与海报共享按 FOCUS segments 分桶的分布，
  排除预热和暂停；没有 segments 的旧导入数据按实际正式专注时长回退。
- 标签仅编辑名称、颜色和每日目标。旧标签时长字段保留以兼容备份，不再用于填充时间块；
  编辑标签不会改写这些旧字段或现有时间块。
- 整小时显示 `1 h`，有零头才显示分钟。预热数字和 `min` 之间保留空格。
- 时间块标签到输入框间距统一为 8dp；标签管理使用齿轮和红色线条垃圾桶。
- 设置页首项采用 16dp 顶部内边距，去除首项原有 32dp 上边距。

## 验证

- `:app:assembleDebug :app:test :app:lint` 成功，119 项单元测试通过；Lint 0 errors、
  103 warnings。未执行可能卸载手机应用的 connected instrumentation 测试。
- 新增覆盖 5 分钟预热 + 完整 60 分钟正式专注、暂停恢复、预热长于正式专注、
  stopwatch 限制以及跨小时 FOCUS segments 分布的回归测试。
- RMX3562使用 `adb install -r` 覆盖安装成功。
- 手机正在运行计时，测试未暂停、结束、修改该计时。真机仅浏览设置、记录页面，最后
  返回计时页面。编辑弹窗在只读 Pixel_10_Pro 模拟器检查，模拟器随后关闭且不保存状态。
- 真机设置页首项 y=144，内容区起点 y=96，相差 48px（16dp）。模拟器中时间块名称、
  正式专注、状态预热、新标签的标签到输入框均相差 24px（8dp）；卡片显示 `1 h · 5 min`。
- 模拟器确认齿轮打开标签编辑，存在 `tag_daily_goal_input`，没有 `tag_total_input` 或
  `tag_warmup_input`。

## 数据保护证据

安装前备份目录：`/tmp/clock-focus-update-20261007/`，含 `before.tar` 和独立
`before.sqlite`。备份包含数据库、WAL、设置和应用文件。安装前数据库完整性通过，
foreign key 检查无异常。

安装前后及真机浏览后逐表比较 schema 和全部行内容，全部一致：categories 3、
task_presets 3、sessions 9、session_segments 12、timer_checkpoint 1、habits 5、
habit_check_ins 1、habit_targets 4，其他表也全部一致。shared_prefs 内容完全一致。

安装后、启动前应用文件完全一致。启动后唯一文件变化为 AndroidX ProfileInstaller 的
`files/profileInstalled` 安装优化标记；它不是用户记录。没有清空应用、卸载应用或写入
用户数据库，没有在手机创建测试记录。检查创建的临时界面 XML 已删除。
