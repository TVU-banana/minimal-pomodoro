# 海报动态收缩验证

- `PosterLayoutPlanner` 按可见模块顺序计算位置和最终画布高度，模块间保持 32px 间距，
  底边距保持 112px。隐藏模块不会扩大其他模块或保留占位。
- PNG renderer 使用计算出的高度创建 Bitmap 和绘制背景；预览 ImageView 使用
  wrap_content 和 adjustViewBounds，不再固定 320dp 高度。预览与导出共用同一布局。
- `:app:assembleDebug :app:test :app:lint` 成功。121 项单元测试通过，Lint 0 errors、
  103 warnings；测试覆盖全部 8 种组合、每次删除模块严格缩短、连续排布和底边距。
- RMX3562 真机使用 `adb install -r` 覆盖安装。在相同 882px 宽度下，预览高度为：
  完整 1052px → 取消激励语 993px → 再取消分布图 575px → 再取消收获 347px。
  真机截图确认分布图移除后收获上移，全部取消后只剩标题、日期和专注总时长。
- 真机测试仅点击预览复选框并取消弹窗，没有保存收获、导出图片或生成测试记录。
- 最新完整备份：`/tmp/clock-poster-compact-20261007/before.tar`；独立数据库快照：
  同目录 `before.sqlite`。安装前后及真机测试后，schema 与所有表的全部行逐项一致，
  shared_prefs 一致，数据库 integrity_check 与 foreign_key_check 通过。
- 安装前及测试后包含 sessions 11、session_segments 19、categories 3、task_presets 3、
  habits 5、habit_check_ins 2、habit_targets 4。运行后唯一应用文件变化为 AndroidX
  ProfileInstaller 的 profileInstalled 优化标记，不属于用户数据。没有卸载或清空应用，
  未运行 connected instrumentation 测试。检查生成的手机临时 XML 已清理。
