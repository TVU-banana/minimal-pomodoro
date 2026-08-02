# Clock 数据格式 v1

Clock 以 Room 数据库作为本机事实来源；SQLite 文件不是迁移协议。开放备份使用 UTF-8、稳定排序和显式版本号，因此可被 Git、网盘或其他实现长期保存。

## 导出类型

| 类型 | 用途 | 格式 |
|---|---|---|
| Session CSV | 表格分析 | RFC 4180；CRLF；按 `startedAt,id` 升序 |
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
└── media/<sha256>.webp
```

## 通用约定

- `schemaVersion` 当前为整数 `1`；其他版本拒绝导入。
- ID 为稳定 UUID 字符串；时长为整数毫秒。
- 时间为 ISO-8601 UTC，例如 `2026-07-15T10:30:00Z`。
- JSON 对象字段顺序固定；类别和模板按 ID 排序，Session 按 `startedAt,id` 排序。
- 解析器忽略未知 JSON 字段，缺少必需字段或字段类型错误则拒绝。
- JSON 数组内部不允许重复 ID；`BackupMerger` 与现有数据合并时，模板和 Session 根据 `updatedAt` 保留较新记录。Category v1 尚无 `updatedAt`，同 ID 时暂以 `createdAt` 作为修订时间。
- 导入先读取、校验所有文件和引用，再产生 `ValidatedBackup`。数据库适配器必须在一个 Room `withTransaction` 中实现 `AtomicBackupWriter`；解析失败、校验失败或写事务失败均不得留下部分数据。

## manifest.json

```json
{"format":"clock-backup","schemaVersion":1,"appVersion":"0.3.0","exportedAt":"2026-07-15T10:30:00Z","timeUnit":"milliseconds","files":{"categories.json":{"sha256":"..."},"presets.json":{"sha256":"..."},"sessions.ndjson":{"sha256":"..."}}}
```

`files` 必须精确列出归档中除 manifest 外的所有文件。每个 SHA-256 使用 64 位小写十六进制；任何遗漏、多余文件或校验失败都会拒绝整个归档。ZIP 条目还会检查重复名称、路径穿越、单文件和总大小上限。

## 实体 JSON

`categories.json` 和 `presets.json` 是 JSON 数组。`sessions.ndjson` 每行是一个完整 JSON 对象，`segments.ndjson` 保存用于跨午夜精确统计的 WARMUP/FOCUS 活动区间。为兼容早期 schema-v1 备份，导入时允许缺少 `segments.ndjson`。空记录对应零字节文件。示例：

```json
{"id":"bd58d6b5-5019-4d3c-a601-3b05fcc88191","presetId":"33bf0148-e2d0-46da-aed6-b1ca34c56cf7","categoryId":"1bd29857-5503-4f9a-b811-0f5591f0d245","categoryNameSnapshot":"学习","plannedTotalMs":3600000,"plannedWarmupMs":900000,"actualWarmupMs":900000,"actualFocusMs":2700000,"startedAt":"2026-07-15T01:00:00Z","endedAt":"2026-07-15T02:00:00Z","timezoneId":"Asia/Shanghai","status":"COMPLETED","createdAt":"2026-07-15T01:00:00Z","updatedAt":"2026-07-15T02:00:00Z"}
```

媒体文件名的哈希必须同时等于文件内容的 SHA-256。v1 仅允许 `media/<sha256>.webp`，并且背景媒体是可选项。
