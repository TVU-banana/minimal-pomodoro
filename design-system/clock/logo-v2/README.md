# Clock · 三套 SVG 图标提案

每套包含应用标志、四个导航入口、十五个常用操作图标。文件集中在本目录，供最终筛选；当前 APP 资源未被替换。

打开 `index.html` 可切换深浅主题、20/24/32px 导航尺寸、隐藏标注和导航选中状态。
`overview.svg` 为矢量总览，各方案的 `sheet.svg` 为完整矢量画板；PNG 仅用于预览。

| 方案 | 视觉语言 | 功能表达 | 选择理由 |
|---|---|---|---|
| A · 精密刻度 | 1.8 单位圆头线条，复杂齿轮微调至 1.7 | 秒表 / 勾选框 / 趋势图 / 六齿齿轮 | 最贴近 Clock 现有的安静、精确、克制气质；我的首选 |
| B · 温润日序 | 2.0 单位线条，柔和曲线与圆角 | 沙漏 / 每日勾选 / 柱图 / 调节滑杆 | 时间流动、每日积累的感觉更强；亲和度更高 |
| C · 实心留白 | 清晰实心轮廓，关键含义通过镂空呈现 | 实心秒表 / 勾选方块 / 三柱图 / 实心齿轮 | 视觉分量更强，适合优先考虑小图标辨识的方向 |

三套沿用项目当前颜色：浅色 `#F8F8F5`、主文字 `#202322`、靛蓝 `#315BCB`；
深色 `#1B1B1D`、主文字 `#F2F2F3`、强调 `#ABC2FF`。
应用图标保留稳定的靛蓝品牌底色，系统 themed icons 使用独立 monochrome 层。

## 细节处理

- 每枚交互图标均在 24 × 24 网格上绘制。设计和检查以当前导航实际显示的 20dp 为基准。
- 秒表的按钮、连接杆、表盘与指针各自保留可辨识的轮廓。打卡强调完成动作，记录强调数据图表，减少两个入口都像日历的歧义。
- A 的开口时环留出开始与推进的方向感；B 的沙漏、沙堆和圆角线条共享曲率；C 的镂空指针与勾选直接使用透明留白，深浅主题中无需固定白色补丁。
- 齿轮的六个齿等角分布，并对转角做曲线修整。A 的齿轮线宽略减，平衡其较多轮廓造成的视觉密度。
- 播放三角形以视觉重心校准位置，并轻微修整三个转角。暂停与结束保持熟悉形状，不增加装饰性外框。
- 未完成圆环与已完成圆形拥有相同外径，切换状态时视觉大小不跳变。
- 核心 SVG 仅使用 `path`，不包含位图、字体、滤镜或外部引用；使用 `currentColor` 支持统一着色。
- 彩色 launcher 图标提供 108 × 108 前景与背景，主体位于中心安全区；不在资源内预制圆角 mask 或外部阴影。

“无需文字也能看懂”是设计目标。预览页的隐藏标注模式可用于直觉筛选；当前未做用户盲测，不把作者判断视为识别率数据。
正式接入仍保留现有导航标签与 accessibility 名称。

## 文件结构

每套目录下包含：

```text
svg/                         21 个独立 SVG，包括透明品牌标志和应用图标
adaptive/                    background / foreground / monochrome 三个 SVG 层
android-res/drawable/         32 个同名 VectorDrawable XML，包括兼容旧资源名的版本
android-res/drawable-anydpi-v26/ic_clock.xml
android-res/drawable-anydpi-v33/ic_clock.xml
sheet.svg                    整套矢量画板
sheet.png                    画板的渲染预览
```

`downloads/` 提供 A、B、C 的独立 ZIP，以及包含比较预览、源文件与验证记录的完整 ZIP。
`manifest.json` 提供机器可读的 SVG → Android 资源对应关系。

## 入选后替换

Android 原生资源使用随附的 XML。SVG 是可编辑设计源文件，两者的路径已经逐一核对一致。

| 用途 | SVG 文件 | 当前 Android 资源名 |
|---|---|---|
| 应用标志 | `svg/brand-mark.svg` | 设计与导出使用的透明标志 |
| 桌面应用图标 | `svg/ic_clock.svg`、`adaptive/*.svg` | `ic_clock` 及其新增的 adaptive 分层资源 |
| 计时 / 打卡 / 记录 / 设置 | `svg/ic_nav_*.svg` | `ic_nav_timer` / `ic_nav_habits` / `ic_nav_reports` / `ic_nav_settings` |
| 开始 / 继续 / 暂停 / 结束 | `svg/ic_play_outline.svg` 等 | `ic_play_outline` / `ic_play_primary` / `ic_pause_primary` / `ic_stop_primary` |
| 添加 / 标签 / 删除 / 更多 | 对应 `svg/ic_*.svg` | `ic_add_preset_thin` / `ic_manage_tags` / `ic_delete_outline` / `ic_more` |
| 主题 / 日期翻页 / 完成状态 / 通知 | 对应 `svg/ic_*.svg` | 保留各自现有 `ic_*` 名称 |

只替换导航时：复制入选方案的四个 `android-res/drawable/ic_nav_*.xml` 到
`app/src/main/res/drawable/` 即可，无需改 Kotlin 或布局。

整套替换时：将入选方案的 `android-res/` 按目录合并到 `app/src/main/res/`。
应用图标另需备份并移走当前 `app/src/main/res/drawable-nodpi/ic_clock.png`，避免同名旧位图继续参与资源选择。
Manifest 现有 `@drawable/ic_clock` 引用可保留。
UI 图标保留现有尺寸；导航继续由 `nav_text` 着色，其他图标使用现有语义颜色，
notification 图标保持系统要求的单色透明形式。

入选资源接入之后再运行应用构建、Android Lint 和相关测试，并在真机确认深浅主题与实际导航大小。
本次 AAPT2 资源检查没有代替整款 APP 的构建或真机验收。

## 已完成验证

- SVG XML 解析、可用元素检查；无位图、脚本、滤镜及 `foreignObject`。
- SVG 与 VectorDrawable 的 path、stroke width、even-odd 留白规则逐项一致。
- 三套 Android 资源分别通过 AAPT2 compile/link；包含 API 26 adaptive 与 API 33 monochrome 资源，使用项目现有深浅主题颜色。
- 480 次实际渲染：三套 × 20 个图形 × 16/20/24/32px × 深浅主题；所有图形非空且未触及画布边缘。
- 当前主题的普通、次要、强调图标对背景的对比度均至少 5.26:1。这里只验证这些图标颜色组合。
- 浏览器验证记录与截图在 `qa/`；资源检查详情见 `resource-checks.json`，小尺寸检查见 `contact-sheet.png`。

## 设计依据与来源

- [Lucide 官方：图标选择、熟悉符号与一致性](https://github.com/lucide-icons/lucide/blob/main/docs/how-to/icon-guide.md)
- [Google 官方：Material Symbols 的 fill、weight、optical size](https://developers.google.com/fonts/docs/material_symbols)
- [Android 官方：adaptive icon 分层、安全区与 monochrome](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- 项目当前 `design-system/clock/MASTER.md`、深浅主题颜色、导航布局和资源名。

以上资料用于设计原则和平台要求。图形路径为本次绘制；未直接复制社区图标路径。
本目录作品随项目采用 GPL-3.0-or-later，参见仓库根目录 `LICENSE`。

## 重新生成

```shell
python3 design-system/clock/logo-v2/generate.py
```

`render.cjs` 使用本机 Codex 提供的 Node.js/Sharp 渲染预览及像素检查。
`validate.py` 使用现有 Android SDK 的 AAPT2 检查资源；均不向应用添加运行时依赖。
