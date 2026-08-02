# 架构约束

Room 是唯一事实来源。所有计时状态变化都经过纯 Kotlin 状态机，再以事务写入 Session 与唯一 Checkpoint。UI、通知和 BroadcastReceiver 只能发送命令，不能自行修改状态。

前台显示按需从 `elapsedRealtime()` 计算剩余时间；后台只在阶段边界设置 Exact Alarm，不运行常驻 Service，不进行每秒数据库写入。

导出格式是长期迁移契约，SQLite 文件不是公开兼容接口。

## 职责边界

- `TimerCoordinator` 串行分发命令并协调 Alarm、通知和持久化副作用。
- `TimerTransitionStore` 在单个 Room 事务中维护 Session、Segment 和 Checkpoint。
- `TimerFragment` 仅处理 Android 生命周期和用户输入；显示计算由 `TimerUiStateFactory` 完成。
- `TimerRendererController` 管理统一的圆环渲染器及其可见性和动画生命周期。
- `TimerDisplayMetadataLoader` 隔离 UI 和底层 DAO 操作，仅向 Fragment 提供任务与分类名称。
- `OpenBackupCodec` 只负责确定性编解码，`BackupValidator` 负责完整性和兼容性校验。

这些边界让状态机保持纯 Kotlin、让持久化保持事务一致，并避免 Fragment 或 Receiver
成为新的业务逻辑入口。
