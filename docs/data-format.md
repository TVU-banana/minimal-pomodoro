# Clock 数据格式 v4

Clock 以 Room 数据库作为本机事实来源；SQLite 文件不是迁移协议。开放备份使用 UTF-8、稳定排序和显式版本号，因此可被 Git、网盘或其他实现长期保存。

## 导出类型

| 类型 | 用途 | 格式 |
|---|---|---|
| Session CSV | 专注记录表格分析；不包含习惯 | RFC 4180；CRLF；按 `startedAt,id` 升序 |
| 开放目录 | Git 或其他工具读取 | JSON + NDJSON；文件内容稳定排序 |
| `.clockbackup` | 完整迁移/恢复 | 确定性 ZIP；开放目录、清单及可选 WebP 媒体 |

长期归档也使用 `.clockbackup`。用户通过系统文件选择器将它存入 `Documents`、云盘或其他
用户可见位置；它不属于应用沙盒，因此卸载 Clock 不会删除。Android 的卸载界面不能由应用
拦截或注入确认框，删除长期归档只能在 Clock 的确认对话框中主动进行，或由用户在文件管理器中删除。

目录结构：

```text
clock-backup/
├── manifest.json
├── categories.json
├── presets.json
├── sessions.ndjson
├── segments.ndjson
├── assets.json
├── reflections.ndjson
├── habits.json
├── habit_check_ins.ndjson
├── habit_targets.json
└── media/<sha256>.webp
```

## 通用约定

- `schemaVersion` 当前为整数 `4`。读取器兼容 v1/v2；它们的习惯定义和打卡记录视为空集合，
  合并导入不会删除本机已有习惯。v1 可以没有 `reflections.ndjson`，写出只生成 v4。v3 的完成记录迁移为 1 次，未完成记录迁移为 0 次，目标默认为 1。
- ID 为稳定 UUID 字符串；时长为整数毫秒。
- 时间为 ISO-8601 UTC，例如 `2026-07-15T10:30:00Z`。
- JSON 对象字段顺序固定；类别和模板按 ID 排序，Session 按 `startedAt,id` 排序。
- 习惯按 ID 排序，每日打卡按 `habitId,localDate` 排序；显示顺序由习惯的 `sortOrder` 保存。
- 解析器忽略未知 JSON 字段，缺少必需字段或字段类型错误则拒绝。
- JSON 数组内部不允许重复 ID；`BackupMerger` 与现有数据合并时，模板和 Session 根据 `updatedAt` 保留较新记录。Category v1 尚无 `updatedAt`，同 ID 时暂以 `createdAt` 作为修订时间。
- 习惯定义按 `id`、打卡按 `(habitId,localDate)` 合并，较新 `updatedAt` 胜出。
  更新时间相同则保留本机值，与既有合并策略一致。撤销记录 `completed=false` 仍参与合并，
  因此恢复旧备份不会重新完成已撤销的日期。
- 同 ID 习惯的 `createdLocalDate` 和 `createdAt` 为不可变创建元数据。若备份与本机冲突，
  即使备份更新时间更新也拒绝整个导入，避免原有历史日期因创建边界改变而消失。
- 导入先读取、校验所有文件和引用，再产生 `ValidatedBackup`。数据库适配器必须在一个 Room `withTransaction` 中实现 `AtomicBackupWriter`；解析失败、校验失败或写事务失败均不得留下部分数据。

## manifest.json

```json
{"format":"clock-backup","schemaVersion":4,"appVersion":"1.0.1","exportedAt":"2026-07-15T10:30:00Z","timeUnit":"milliseconds","files":{"categories.json":{"sha256":"..."},"presets.json":{"sha256":"..."},"sessions.ndjson":{"sha256":"..."},"segments.ndjson":{"sha256":"..."},"assets.json":{"sha256":"..."},"reflections.ndjson":{"sha256":"..."},"habits.json":{"sha256":"..."},"habit_check_ins.ndjson":{"sha256":"..."},"habit_targets.json":{"sha256":"..."}}}
```

`files` 必须精确列出归档中除 manifest 外的所有文件。每个 SHA-256 使用 64 位小写十六进制；任何遗漏、多余文件或校验失败都会拒绝整个归档。ZIP 条目还会检查重复名称、路径穿越、单文件和总大小上限。

v4 的上述九个数据文件必须全部存在。没有习惯时 `habits.json` 为 `[]`，没有打卡时
`habit_check_ins.ndjson` 为零字节，`habit_targets.json` 为 `[]`。媒体始终是可选文件。

## 实体 JSON

`categories.json` 和 `presets.json` 是 JSON 数组。`sessions.ndjson` 每行是一个完整 JSON 对象，`segments.ndjson` 保存用于跨午夜精确统计的 WARMUP/FOCUS 活动区间。`reflections.ndjson` 保存每个本地日期的一条用户总结；v2 中该文件即使为空也必须存在。为兼容早期 schema-v1 备份，导入时允许缺少 `segments.ndjson` 和 `reflections.ndjson`。空记录对应零字节文件。示例：

```json
{"id":"bd58d6b5-5019-4d3c-a601-3b05fcc88191","presetId":"33bf0148-e2d0-46da-aed6-b1ca34c56cf7","categoryId":"1bd29857-5503-4f9a-b811-0f5591f0d245","categoryNameSnapshot":"学习","plannedTotalMs":3600000,"plannedWarmupMs":900000,"actualWarmupMs":900000,"actualFocusMs":2700000,"startedAt":"2026-07-15T01:00:00Z","endedAt":"2026-07-15T02:00:00Z","timezoneId":"Asia/Shanghai","status":"COMPLETED","createdAt":"2026-07-15T01:00:00Z","updatedAt":"2026-07-15T02:00:00Z"}
```

媒体文件名的哈希必须同时等于文件内容的 SHA-256。v1 仅允许 `media/<sha256>.webp`，并且背景媒体是可选项。

## 习惯与每日打卡

习惯与计时 Tags、模板、Session 均无外键关联。`habits.json` 保存 JSON 数组，字段如下：

| 字段 | 类型与约束 |
|---|---|
| `id` | 小写、标准 `8-4-4-4-12` UUID；数组内唯一 |
| `name` | 去除首尾空白后的 1–120 个字符 |
| `note` | 可选备注；去除首尾空白，最多 1000 个字符，可含换行；旧备份缺失时为 `""` |
| `sortOrder` | 非负 32 位整数 |
| `createdLocalDate` | 严格 `yyyy-MM-dd` 的有效本地日历日期 |
| `createdAt`、`updatedAt` | ISO-8601 UTC；非负毫秒，更新时间不得早于创建时间 |
| `archivedLocalDate` | 可空；不得早于创建日期 |

习惯适用日期为 `createdLocalDate <= date <= archivedLocalDate`；未归档时没有上限。
归档习惯从今天的列表移除，历史日期仍可查看归档当天及此前的有效记录。
同日创建并归档合法。归档不会删除此前写入的打卡，包括归档当天已有的记录。

```json
[{"id":"10289410-c0d8-4d10-b480-b1b575355c03","name":"阅读","sortOrder":0,"createdLocalDate":"2026-07-13","createdAt":"2026-07-13T08:00:00Z","updatedAt":"2026-07-13T08:00:00Z","archivedLocalDate":null}]
```

`habit_check_ins.ndjson` 每行保存一个日期的状态：

```json
{"habitId":"10289410-c0d8-4d10-b480-b1b575355c03","localDate":"2026-07-14","completed":true,"completedCount":1,"completedAt":"2026-07-14T08:00:00Z","updatedAt":"2026-07-14T08:00:00Z"}
{"habitId":"10289410-c0d8-4d10-b480-b1b575355c03","localDate":"2026-07-15","completed":false,"completedCount":0,"completedAt":null,"updatedAt":"2026-07-15T08:00:00Z"}
```

- `(habitId,localDate)` 必须唯一，`habitId` 必须引用本备份中的习惯。
- `localDate` 是严格 `yyyy-MM-dd` 的有效日期；完成时间只用于审计，不决定日期归属。
- `completed` 必须是 JSON 布尔值；字符串、整数及 `null` 均拒绝。
- 完成状态必须含非负 `completedAt`，且不得晚于 `updatedAt`；撤销状态必须为空。
  `updatedAt` 同样必须为非负 ISO-8601 UTC 时间。
- 导出在同一 Room 事务中读取快照；导入先完成文件、时间、日期、重复键及外键校验，
  再在同一 Room 事务中读取本机数据、合并、再次校验并写入所有实体。

## 每日次数与历史目标（v4）

- `completedCount` 是当天已记录次数，整数范围 0–99；部分进度也必须导出。
- `habit_targets.json` 保存目标变更：`habitId`、`effectiveLocalDate`、`targetCount`、
  `updatedAt`。目标为 1–99 的整数，生效日期不得早于习惯创建日期。
- `(habitId,effectiveLocalDate)` 唯一；按习惯和生效日期稳定排序。
- 指定日期使用不晚于该日的最后一次目标；缺失历史默认 1 次。修改从今天生效，
  不改变之前日期，也不清除今天已记录次数。无打卡记录的历史日期也保留当时目标。
- 完成由当天次数达到对应目标决定。`completed` 是本地命令维护的兼容缓存；
  导入分别合并计数和目标后，以计数及目标计算界面状态。
- 目标按复合键和 `updatedAt` 合并，打卡次数按原每日键和 `updatedAt` 合并。
  同版本保留本机值。撤销到零也保留记录，防止旧备份复活进度。
