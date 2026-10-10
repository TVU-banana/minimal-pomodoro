# 报告周期精简与三个月分页热力图实施计划

## 1. 文档目的

本文档将报告页收敛为适合手机使用的三个周期，并把年度热力图改为固定三个月一页的分页
布局。它是面向 GPT-5.6 Terra 中等推理强度的执行计划，产品行为、日期边界、文件职责、
测试和真机验收均已锁定；实施 Agent 不应在执行时重新发明交互。

本次最终交付应满足：

- 报告页只保留“本年度”“本周”“本日”；
- 三个周期都能查看历史范围，并能从历史范围向右返回当前范围；
- 任何入口都不能进入未来年度、未来周或明天；
- 年度热力图每页固定三个月，使用简单左右箭头分页，不允许横向滑动；
- 所有统计、明细和海报使用当前选中的报告范围，不再固定使用今天；
- 不删除任何 Session、Segment、标签、每日收获或备份数据。

## 2. 范围与非目标

### 2.1 本次范围

1. 删除报告页的“有史以来”和“本月”入口及对应运行时代码。
2. 将 `ReportPeriod` 收敛为 `YEAR`、`WEEK`、`DAY`。
3. 新增可测试的报告选择状态与前后导航规则。
4. 年度热力图改为三个月分页，自适应手机可用宽度。
5. 同步报告汇总、明细列表、柱状图、海报数据和日期文案。
6. 补齐 JVM 单元测试、Lint、构建和真机视觉验收。

### 2.2 明确不做

- 不修改 Room schema，不做数据库迁移，不删除历史数据。
- 不修改计时器 reducer、Alarm、备份格式或恢复规则。
- 不把周视图或日视图改成热力图：周继续使用七日柱状图，日继续使用 24 小时柱状图。
- 不引入 Compose、第三方图表库、网络、持久 Service 或每秒数据库写入。
- 不增加手势横滑、循环分页、日期选择器、缩放或动画图表。
- 不重新设计海报；只让现有海报正确使用选中的周期和历史锚点。

## 3. 开工前基线保护

当前工作区包含大量未提交改动。实施 Agent 必须先执行：

```bash
git status --short
git diff -- app/src/main/java/com/akira/clock/ui/ReportsFragment.kt
git diff -- app/src/main/java/com/akira/clock/report
git diff -- app/src/main/java/com/akira/clock/ui/render
git diff -- app/src/main/res/layout/fragment_reports.xml
git diff -- app/src/main/res/layout/item_chart_heatmap.xml
git diff -- app/src/main/res/values/strings.xml
```

这些改动均视为用户资产。禁止使用 `git reset --hard`、`git checkout --`、覆盖式复制或删除
无关文件。只编辑本文档列出的报告相关文件；发现重叠修改时，基于当前工作区版本做最小补丁。

## 4. 冻结的产品行为

### 4.1 页面骨架

```text
┌──────────────────────────────────────┐
│      本年度       本周       本日       │  三段周期选择
└──────────────────────────────────────┘

       ‹        2026年        ›            报告范围导航
合计 600min
┌──────────────────────────────────────┐
│             预览专注海报               │
└──────────────────────────────────────┘

专注热力图
       ‹     2026年7月–9月     ›          三个月页导航，仅年度显示
周日  □ □ □ □ □ □ □ □ □ □ □ □ □
周一  □ □ □ □ □ □ □ □ □ □ □ □ □
 ...
周六  □ □ □ □ □ □ □ □ □ □ □ □ □
      7月          8月          9月

标签汇总
记录明细
```

两组箭头职责必须分开：

- 上方箭头切换整个报告范围，例如 2026 年与 2025 年；
- 热力图卡片内箭头只切换所选年份内的三个月页；
- 两组箭头使用不同的 `contentDescription`，不能共享同一点击监听器。

布局 ID 固定为：`report_range_previous`、`report_range_title`、`report_range_next`，以及
`heatmap_page_previous`、`heatmap_page_title`、`heatmap_page_next`。实施时沿用这些名称，降低
Fragment、XML 和测试之间的映射歧义。

### 4.2 周期选择

仅保留三个选项，顺序固定为：

1. 本年度；
2. 本周；
3. 本日。

首次进入报告页默认选择“本日”。切换周期时重置为当前范围：

- 切到本年度：选中当前年度，并显示包含今天的三个月页；
- 切到本周：选中包含今天的 ISO 周，周一为一周开始；
- 切到本日：选中今天。

切换周期不保留另一个周期的历史锚点。这样用户从“2024 年”切到“本日”时不会意外进入
2024 年的某一天。

### 4.3 报告范围前后切换

“不能切换到下一年、下一周和明天”的精确定义是“不能越过当前范围”，不是永久禁用右箭头：

| 当前选择 | 左箭头 | 右箭头 |
|---|---|---|
| 当前年度 | 前一年 | 禁用 |
| 历史年度 | 前一年 | 后一年，最多回到当前年度 |
| 当前周 | 前一周 | 禁用 |
| 历史周 | 前一周 | 后一周，最多回到当前周 |
| 今天 | 昨天 | 禁用 |
| 历史日期 | 前一天 | 后一天，最多回到今天 |

右箭头禁用时仍保留 48dp 占位，避免标题左右跳动。禁止通过连续点击、旋转恢复、异步竞态或
三个月页切换绕过未来边界。

范围标题格式固定为：

- 年：`2026年`；
- 同年周：`8月10日–8月16日`；
- 跨年周：`2025年12月29日–2026年1月4日`；
- 今天：`今天 · 8月11日`；
- 昨天：`昨天 · 8月10日`；
- 更早日期：`2026年8月9日`。

所有用户可见格式放入资源或专门 formatter，禁止在 Fragment 中散落中文字符串拼接。

### 4.4 三个月分页规则

年度热力图按自然季度分为四页，页边界固定：

- 第 0 页：1 月 1 日至 4 月 1 日，不含结束日；
- 第 1 页：4 月 1 日至 7 月 1 日，不含结束日；
- 第 2 页：7 月 1 日至 10 月 1 日，不含结束日；
- 第 3 页：10 月 1 日至下一年 1 月 1 日，不含结束日。

初始页为包含选中锚点的季度。切换年份时保留季度页索引，例如从 2026 年 7–9 月切到
2025 年后仍显示 2025 年 7–9 月。

页导航边界：

- 历史年份允许查看全部四页；
- 当前年份只允许查看包含今天的页及其之前页面；
- 当前页的右箭头只有在下一页起始日不晚于今天时才启用；
- 当前季度中晚于今天的日期保留网格位置，但不绘制空色块，不能把未来误表示为零专注；
- 页边界外的前导/尾随格不绘制色块；
- 三个月页面不可循环，第一页左箭头和最后一个可用页右箭头禁用。

### 4.5 热力图视觉契约

年度热力图仍使用 Sunday-first 的七行日历周布局，禁止横向滚动：

- 左侧显示完整 `周日` 至 `周六`，优先保证日历可读性；
- 底部显示三个自然月标签；
- 颜色级别继续表达每日专注分钟，不按当前页数据动态缩放；
- 级别固定为：0、1–30、31–90、91–180、181 分钟及以上；
- 0 分钟使用空格色；未来日期和页外日期透明，不得共用 0 分钟颜色；
- 保留浅色与深色两套有对比度的蓝色色阶；
- 方格圆角、间距、文字颜色使用项目资源，不在 Canvas 中硬编码颜色；
- 宽度由父容器约束决定，计算可容纳 13–14 个周列的方格尺寸；
- 方格视觉尺寸建议 12dp，最低不得小于 10dp；多余宽度用于列间距或整体居中；
- 不再使用 `HorizontalScrollView`，也不响应横向滑动。

三个月跨 13 或 14 个日历周。测量逻辑必须基于实际列数和可用宽度，不能假设每页固定
13 列。若 360dp 级别设备仍无法容纳，优先把方格缩到 10dp，再缩小间距；不得恢复横滑。

### 4.6 简单箭头规范

所有切换按钮使用同一对简单 chevron VectorDrawable：

- 左：`ic_chevron_left.xml`；
- 右：`ic_chevron_right.xml`；
- 透明背景，不使用圆形、胶囊、文字按钮或阴影；
- 视觉图形建议 20dp，点击区域固定至少 48dp × 48dp；
- 使用 `clock_on_surface` tint；禁用态 alpha 建议 0.28；
- 必须有 `contentDescription`，如“上一年”“下一周”“上一组月份”；
- 禁用时设置 `isEnabled = false`，不能只降低透明度后仍响应点击。

### 4.7 统计、图表、明细和海报一致性

一个 `ReportSelection(period, anchor)` 是页面的唯一选择状态：

- 汇总与明细使用整个选中年、选中周或选中日；
- 周柱状图使用选中周，而不是当前周；
- 日柱状图使用选中日期，而不是今天；
- 年度屏幕热力图只查询当前三个月页；
- 年度汇总、明细和海报仍代表完整选中年度，不缩成三个月；
- 海报日期标题、文件名和数据必须反映选中的历史范围；
- `reflectionDate` 随选中锚点更新，不能一直固定为今天；
- 快速连续切换时，旧请求不能覆盖新选择的 UI 或 `pendingPoster`。

年度“屏幕显示三个月、海报显示完整年度”是刻意设计。海报画布有足够宽度，年度海报继续
承担年度总结职责；屏幕分页只解决手机可读性。

## 5. 纯 Kotlin 状态与日期契约

在 `report` 包中新增纯 Kotlin 状态，名称可微调但职责不得移回 Fragment：

```kotlin
internal data class ReportSelection(
    val period: ReportPeriod,
    val anchor: LocalDate,
)

internal data class HeatmapPage(
    val year: Int,
    val pageIndex: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
)
```

推荐新增 `ReportNavigation` 或同等纯对象，集中提供：

```kotlin
fun currentSelection(period: ReportPeriod, today: LocalDate): ReportSelection
fun previous(selection: ReportSelection): ReportSelection
fun nextOrNull(selection: ReportSelection, today: LocalDate): ReportSelection?
fun rangeLabel(selection: ReportSelection, today: LocalDate): ReportRangeLabel
fun heatmapPage(selection: ReportSelection, pageIndex: Int): HeatmapPage
fun nextHeatmapPageOrNull(page: HeatmapPage, today: LocalDate): HeatmapPage?
fun previousHeatmapPageOrNull(page: HeatmapPage): HeatmapPage?
```

约束：

- 以上计算不依赖 `Context`、`View`、Room 或系统当前时间；
- `today` 和 `ZoneId` 从调用方注入；
- `ReportPeriod` 只保留 `YEAR`、`WEEK`、`DAY`；
- `reportRange()` 保持 UTC 半开区间 `[from, to)`；
- 周一为周起点；跨 DST 的一天仍以本地午夜边界计算，不能固定写成 24 小时毫秒数；
- `HeatmapPage` 必须验证 `pageIndex in 0..3`；
- UI 不自行做 `minusYears()`、`minusWeeks()` 或 `minusDays()`。

`ReportRangeLabel` 建议保存资源参数或结构化日期，不要让纯 Kotlin 层依赖 Android 字符串资源。
Fragment 或独立 formatter 最终把它映射为资源文案。

## 6. 代码改动清单

| 文件 | 必须完成的动作 |
|---|---|
| `report/ReportModels.kt` | 删除 `ALL_TIME`、`MONTH`；保留三种范围；更新 KDoc |
| `report/ReportNavigation.kt` | 新增纯选择、前后导航、未来边界和季度页计算 |
| `report/ReportService.kt` | 继续按 `ReportSelection` 对应范围加载汇总和明细 |
| `report/PosterModels.kt` | 删除两种周期分支；使用历史 anchor 生成三种海报数据 |
| `ui/ReportsFragment.kt` | 持有选择状态、页索引和 load Job；只编排 UI，不承载日期算法 |
| `ui/PosterCustomizeDialogFragment.kt` | 安全解析周期参数，废弃或未知值回退到 DAY |
| `ui/render/HeatmapGrid.kt` | 支持三个月页、未来/页外单元格和动态列数的纯计算 |
| `ui/render/HeatmapView.kt` | 改为受约束宽度、无横滑绘制，并区分 0/未来/页外状态 |
| `res/layout/fragment_reports.xml` | 五段改三段；新增报告范围导航行 |
| `res/layout/item_chart_heatmap.xml` | 删除 `HorizontalScrollView`；新增三个月页导航行 |
| `res/drawable/ic_chevron_*.xml` | 新增简单左右箭头 VectorDrawable |
| `res/values/strings.xml` | 删除废弃入口，增加范围标题、箭头无障碍文案和页标题 |
| `export/FocusPosterRenderer.kt` | 删除废弃周期分支；确认历史范围标题正确 |
| `report/*Test.kt` | 更新周期测试并覆盖历史导航和时区边界 |
| `ui/render/HeatmapGridTest.kt` | 覆盖四页、13/14 周、未来空白和闰年 |

若 `MONTH` 或 `ALL_TIME` 在当前工作区新增的其他文件中仍有引用，使用 `rg` 全量找出并逐个
处理。不得为了编译而保留不可达的旧分支，也不得删除历史 Session 数据或备份字段。

`PosterCustomizeDialogFragment` 当前通过 `ReportPeriod.valueOf()` 读取 Bundle 字符串。删除枚举值
后必须改为安全解析：旧进程状态若携带 `MONTH`、`ALL_TIME` 或未知值，回退到 `DAY` 并重新
加载当前范围，不能在 Activity 恢复时崩溃。该兼容只处理临时 Bundle，不要求保留旧报告入口。

## 7. Fragment 加载与竞态处理

当前 `ReportsFragment.load()` 每次启动新的 lifecycle coroutine，快速点按可能让旧结果覆盖新
选择。实施时增加一个受 View 生命周期约束的 `loadJob`：

1. 用户切换周期、范围或三个月页；
2. 先计算并提交新的纯选择状态；
3. 取消旧 `loadJob`；
4. 显示轻量加载态，并禁用海报按钮；
5. IO 中加载选中范围数据和对应图表；
6. 回到主线程前确认 selection token 仍匹配；
7. 一次性提交 summary、items、empty、pendingPoster 和箭头状态；
8. 失败时保留当前选择，显示错误，禁止展示旧周期的海报。

三个月页切换只需要重新加载年度热力图数据；整年 summary、details 和 poster 可以复用同一
年度结果。中等强度模型若难以安全拆分缓存，可先重新加载整页保证正确性，但不能出现旧请求
覆盖新页面。完成正确性后再做局部复用，不把优化与首个可用版本捆绑。

在 `onSaveInstanceState()` 保存：

- 当前 `ReportPeriod`；
- anchor 的 ISO 日期；
- 年度 pageIndex。

恢复时再次用 `today` 校验，未来或非法值回落到当前范围。不得把该临时 UI 状态写入 Room 或
`SettingsStore`。

## 8. 分阶段实施顺序

每一阶段结束后先编译或运行聚焦测试，再进入下一阶段。不要一次修改所有文件后统一排错。

### 阶段 0：记录基线

- 运行第 3 节的状态与差异检查；
- 记录现有 `testDebugUnitTest`、Lint 和 Debug build 结果；
- 确认本任务不覆盖用户在海报、Room、计时器和主题上的未提交工作。

完成条件：已知基线和重叠文件，无文件被恢复或删除。

### 阶段 1：纯日期与导航模型

- 新增 `ReportSelection`、`ReportNavigation` 和 `HeatmapPage`；
- 为三个周期、前后导航、未来上界、跨年周、闰年和四个季度页写测试；
- 暂不改 UI。

完成条件：新增 JVM 测试通过，纯模型不引用 Android API。

### 阶段 2：周期收敛

- 将 `ReportPeriod` 收敛为 `YEAR`、`WEEK`、`DAY`；
- 更新 `reportRange()`、ReportService、PosterModels、Renderer 和所有 exhaustive `when`；
- 删除废弃测试期待和无引用字符串；
- 保留默认 `DAY`。

完成条件：项目可编译，三种周期的 report/poster 单元测试通过。

### 阶段 3：三段选择与范围导航

- 五段 RadioGroup 改为三段；
- 增加报告范围标题和简单箭头；
- Fragment 改为 selection 驱动，周/日柱状图使用历史 anchor；
- 增加 load cancellation 和恢复状态。

完成条件：本日、昨天、历史周、历史年都能显示正确汇总、柱状图与明细；未来箭头禁用。

### 阶段 4：三个月分页热力图

- 删除热力图 `HorizontalScrollView`；
- 增加页标题与页箭头；
- Grid 纯计算区分已发生日、0 分钟、未来和页外格；
- View 根据父宽计算方格与间距；
- 当前年禁止进入未来季度，历史年允许四页。

完成条件：360dp 宽设备完整显示三个自然月，无横滑，月标尺和星期标签不裁切。

### 阶段 5：海报和资源清理

- `pendingPoster` 使用当前 selection 的 anchor；
- 历史年/周/日海报标题、范围和文件名正确；
- 年度海报仍展示完整年度；
- 删除所有 `MONTH`、`ALL_TIME`、旧 heatmap scroller 和废弃资源引用；
- 用 `rg` 证明无旧周期引用残留。

完成条件：屏幕、明细、海报和导出文件名属于同一选择范围。

### 阶段 6：完整验证和真机验收

- 运行完整单元测试、Lint 和 Debug assemble；
- 安装到真实设备；
- 按第 10 节矩阵检查浅色与深色主题；
- 截图记录年度四页、历史导航、未来禁用和最窄目标设备。

完成条件：所有验收项有命令结果或真机截图，构建证据与视觉证据分开报告。

## 9. 必须新增或更新的测试

### 9.1 `ReportNavigationTest`

- DAY：今天右箭头为空；昨天能向右回到今天；不能产生明天。
- WEEK：周一为起点；当前周右箭头为空；历史周能回到当前周。
- WEEK：跨年周标题结构包含两个年份。
- YEAR：当前年度右箭头为空；历史年度能回到当前年度。
- 切换周期时 anchor 重置为 today。
- 恢复未来 anchor 时被规范化到当前范围。

### 9.2 `ReportModelsTest`

- YEAR、WEEK、DAY 的 UTC 半开区间正确。
- DST 前后的一天使用本地午夜，不假设 24 小时。
- 删除 ALL_TIME、MONTH 后没有旧测试或不可达分支。
- 旧 Bundle 周期字符串 `MONTH`、`ALL_TIME` 和未知值安全回退到 DAY。

### 9.3 `HeatmapGridTest`

- Q1、Q2、Q3、Q4 的起止日期正确。
- 闰年 Q1 包含 2 月 29 日。
- 不同季度产生实际 13 或 14 周列。
- Sunday-first 行列定位正确。
- 页外前导/尾随格透明。
- 过去的零分钟日为 empty level。
- 当前季度今天之后为 future，不使用 empty level。
- 当前 7–9 月页在 8 月时不能进入 10–12 月页。
- 历史年份可以浏览到 10–12 月页。

### 9.4 `PosterModelsTest` 与 Renderer 测试

- 历史 DAY、WEEK、YEAR 使用传入 anchor。
- YEAR 海报范围仍是一整年，不受当前三个月 pageIndex 影响。
- 不再存在 MONTH、ALL_TIME 标题和文件标签。

### 9.5 Fragment/UI 验证

项目当前没有完整 UI instrumentation 基线，不为本任务强行引入新框架。用纯状态测试覆盖逻辑，
再用 ADB 真机覆盖 View 层。若已有可用 instrumentation 配置，则补一个旋转恢复和快速切换
测试，但不能让测试框架扩张阻塞核心交付。

## 10. 真机验收矩阵

至少在一个 360dp 级宽度的真实设备或等效模拟器上执行：

| 场景 | 操作 | 必须看到 |
|---|---|---|
| 默认入口 | 打开记录页 | 本日选中、今天标题、右箭头禁用 |
| 昨天 | 点日范围左箭头 | 昨天数据；右箭头可回今天 |
| 历史日 | 连续向左两次 | 日期递减，无明天入口 |
| 当前周 | 选本周 | 正确周区间、七日柱状图、右箭头禁用 |
| 跨年周 | 导航到跨年周 | 标题含两个年份，数据边界正确 |
| 当前年度 | 选本年度 | 显示包含今天的三个月页 |
| 当前季度 | 点页右箭头 | 若下一页在未来则无响应且禁用 |
| 当前季度未来日 | 查看当前页 | 今天之后不画空色块 |
| 历史年度 | 点年度左箭头 | 同一季度页索引，右箭头可回当前年 |
| 历史年度四页 | 在历史年切页 | 四个三个月页都可访问，不循环 |
| 最窄宽度 | 查看任意季度 | 三个月完整可见，无横滑或裁切 |
| 快速切换 | 快速点年/周/日和箭头 | 最终 UI、明细和海报均属于最后选择 |
| 旋转/重建 | 旋转设备或重建 Activity | 周期、anchor、pageIndex 恢复且不越未来 |
| 空数据 | 查看无记录历史范围 | 空态与图表不矛盾，不显示旧范围数据 |
| 海报 | 导出历史年/周/日 | 标题、范围、总时长和文件名一致 |
| 深色主题 | 重复年度关键路径 | 方格、箭头、标签均清晰可见 |

真机截图至少包含：当前年度当前页、历史年度 Q4、当前周、昨天、禁用的未来箭头，以及浅/深
主题各一张年度热力图。安装成功不等于视觉通过；必须打开对应界面检查。

## 11. 验证命令

以仓库当前 Gradle/JDK 配置为准，至少运行：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test lintDebug assembleDebug --no-daemon --no-configuration-cache

git diff --check

rg -n "ALL_TIME|MONTH|report_all|report_month|chart_heatmap_scroller" \
  app/src/main app/src/test
```

最后一条搜索应只命中确实需要保留的历史文档；生产代码和测试不得残留旧周期或横滑容器。

若 Lint 存在基线问题，报告必须区分：

- 本任务新增错误；
- 实施前已存在且未被本任务触碰的问题。

不得把“构建成功”“单元测试通过”“真机视觉通过”和“业务数据正确”混写成一个结论。

## 12. 验收标准

只有同时满足以下条件才算完成：

- 页面只显示本年度、本周、本日，默认本日；
- 三种范围均可向过去导航，历史范围可向右返回当前范围；
- 所有通路都无法进入未来范围；
- 年度热力图恰好三个月一页，无 `HorizontalScrollView` 和横向手势；
- 箭头是透明背景的简单 chevron，48dp 点击区域，禁用态不可点击；
- 当前季度未来日期透明，过去零记录日期使用 empty 色；
- 周/日柱状图和明细使用选中历史 anchor；
- 年度汇总与海报仍覆盖完整选中年度；
- 快速操作不会出现旧请求回写；
- 旋转恢复不会丢状态或越过未来；
- 无 Room、备份或计时器行为变化；
- 聚焦测试、完整测试、Lint、Debug build、diff check 和真机矩阵有明确结果；
- 未覆盖工作区无关改动。

## 13. 风险与回滚边界

### 13.1 主要风险

- 两组箭头监听器接错，导致季度页箭头切换年份；
- 继续使用固定 `today`，让历史标题变了但数据没变；
- 快速点击产生协程竞态，旧结果覆盖新结果；
- 当前季度未来日期被画成 0 分钟，误导用户；
- 三个月实际占 14 周，固定 13 列导致裁切；
- 屏幕分页三个月后错误地把年度海报也缩成季度；
- 删除 enum 值时漏掉 Poster 或资源分支；
- 恢复旧 Bundle 时 `valueOf()` 因已删除周期值崩溃；
- 修改重叠文件时覆盖用户未提交的海报和 UI 工作。

### 13.2 回滚

本功能不含数据迁移，回滚只涉及报告 UI、纯日期模型、Renderer 分支和资源：

- 保留每阶段可编译的最小差异，便于逐阶段撤回；
- 不通过删除数据库、清空应用数据或恢复整个工作区回滚；
- 若分页 View 无法在最窄设备通过，先回滚阶段 4，保留阶段 1–3 的周期精简和历史导航；
- 回滚后重新运行 Report、Poster 和构建验证，证明历史数据仍可读。

## 14. 给 GPT-5.6 Terra 中等强度的执行指令

执行时遵守以下顺序和限制：

1. 先读 `AGENTS.md`、本计划和所有待改文件的当前内容。
2. 先保存 `git status --short` 与相关 diff，不整理或恢复无关改动。
3. 严格按阶段 0–6 实施；每阶段完成后运行聚焦测试并报告结果。
4. 日期、未来边界和季度页必须先写成纯 Kotlin，再接 UI。
5. 不把计算塞进 Fragment，不在 Kotlin 中硬编码用户可见文案。
6. 使用现有 XML Views 与 Canvas，不引入 Compose 或第三方图表库。
7. 箭头只用 VectorDrawable + 至少 48dp 点击区域，不自行增加按钮装饰。
8. 遇到产品歧义，以第 4 节冻结行为为准；不要扩展为月份选择器或无限历史流。
9. 任何阶段编译失败，先修复该阶段，不继续堆叠后续改动。
10. 完成后逐项核对第 12 节，不以 APK 安装成功代替真机视觉验收。

建议每次工作汇报只包含：本阶段改动、验证证据、现存阻塞、下一阶段。不要重复整份计划，
不要在没有真机截图时声称视觉完成。
