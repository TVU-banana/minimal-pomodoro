# Change log

## 2026-07-30 — Reports heatmap calendar layout

- Replaced sparse GitHub-style weekday labels with the full `周日` through `周六` sequence.
  GitHub's sparse `Mon` / `Wed` / `Fri` labels conserve horizontal space, but seven explicit rows
  are more immediately understandable in this calendar-focused product.
- Aligned daily cells to Sunday-first calendar weeks, including leading blank cells before a
  month, year, or all-time range starts.
- Added month labels and horizontal scrolling so all-time and yearly histories are not clipped.
- Changed month and year heatmaps to stop at today instead of rendering future dates as empty.
- Added unit coverage for calendar-week cell placement.

## 2026-07-30 — Daily chart density

- Reduced daily bar width from 28dp to 12dp and gap from 12dp to 2dp, allowing the 24-hour
  chart to fit on a typical phone width.
- Suppressed overlapping per-hour value labels only for charts with more than 12 bars; the bar
  height and hour labels remain visible.
- Restored the weekly chart as a separate layout: its seven daily bars now divide the visible
  viewport evenly, so it has no unused space on the right.

## 2026-07-30 — Early-stop dialog layout

- Removed the explanatory paragraph below the `提前结束` title.
- Moved the guidance into the input hint as `记录一下提前结束的原因吧。`.
- Applied equal 16dp padding on every side of the multiline input.

## Verification

- `testDebugUnitTest` passed after the heatmap, chart, and early-stop dialog changes, including
  the two new `HeatmapGridTest` cases.
- `lintDebug` was executed but remains blocked by pre-existing project errors: the existing
  `LocalDate.ofInstant` call in `ReportsFragment` requires API 34 while `minSdk` is 26, and the
  existing navigation-bar theme attributes require API 27. This heatmap change adds no lint error.
