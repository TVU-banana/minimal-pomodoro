# Clock Android Design System

## Character

Quiet, precise and personal. Warm neutral surfaces, consistent monochrome timer controls and
compact alignment. Indigo remains available for reports and active-timer states. Product quality
is the reference; no brand style is copied.
The existing native XML Views, Canvas renderer, navigation and feature set remain in place.

## Tokens

| Role | Light | Dark |
|---|---|---|
| Background | `#F8F8F5` | `#171A18` |
| Raised surface | `#FFFFFF` | `#202421` |
| Grouped surface | `#EDEEEA` | `#232724` |
| Main text | `#202322` | `#EEF1EB` |
| Secondary text | `#646965` | `#AFB7AF` |
| Primary | `#315BCB` | `#ABC2FF` |
| On primary | `#FFFFFF` | `#14254D` |
| Outline | `#969B95` | `#667167` |
| Quiet separator | `#E0E3DD` | `#343C35` |

Use semantic pairs, including icon tints. Never assume white works on the dark theme's pale accent.
Filled primary buttons retain readable foreground colors when pressed. Selected segments use a
raised surface, a boundary and accent text; selection is also exposed by native checked semantics.

## Typography and spacing

- Screen titles: system sans medium, 30sp; body: 14–16sp; card titles: 16sp.
- General labels: 13sp; supporting metadata: 12–14sp. Timer-row metadata: 12sp, tags: 11sp.
  Tags are intentionally secondary; retain readable muted text instead of lowering view opacity.
- Timer: bundled DSEG7 Classic Regular, fitted at state/size changes. Preserve the font's
  seven-segment geometry and fixed advances. Draw only the actual digits in the main text color.
  No LCD panel, tinted backdrop or inactive-segment ghost layer.
- Keep bundled serif fonts for the existing poster, not everyday interactive controls.
- Timer gutters: 20dp. Other pages retain 24dp. Use a 4/8dp rhythm.
- Timer rows: 64dp at normal font scale, no outer gaps, raised tiles or enclosing borders.
  Use 1dp separators. Other grouped panels retain 8dp corners.
- Toolbar controls: 28dp visual height, 48dp touch height, 1.25dp effective neutral strokes.
  Battery and theme tracks share the same surface, outline, radius and vertical center.
- No elevation, gradients, blur or decorative motion.
- All actions have at least 48dp touch targets. Inputs use a 52dp minimum, not a fixed height.

## Screen rules

- Timer setup: fixed toolbar with a battery-shaped Today summary on the left, a sliding sun/moon
  theme capsule, and a plus icon at the far right. Tap the battery to open Today details.
  Omit all setup headings and the separate Today button. Separate the toolbar from the list with
  the same 1dp rule used above the bottom navigation. Keep the progress label small and muted.
  Center a regular 12sp percentage in the capsule. Use muted text over the track and on-primary
  text over the actual rounded fill, including when the fill edge crosses the label.
  Row text edits; a larger unframed outline play icon starts. Rows have two lines: title and quiet
  tags together, then duration/mode and warmup value without the redundant warmup label.
  Truncate long titles without moving the play action.
  Tags retain 48dp touch targets; a horizontal strip exposes extra tags within the first row.
  Tap a capsule to edit its tag and color. Blend its custom color into the background at 6% in
  light mode or 10% in dark mode; use muted neutral text. A 20dp visible pill sits inside a 48dp
  touch target aligned to the title. Do not restore saturated tag fills or thick tag borders.
- Active timer: neutral background, thin phase ring, task name, one dominant LCD time value,
  elapsed/remaining caption and stage. Pause/resume and end remain in the bottom dock.
- Reports: title, year/week/day segments, range navigation, summary, poster action and detail list.
  Preserve historical range boundaries and three-month paged year heatmaps.
- Habits: independent daily list with the same neutral surfaces, 24dp gutters and thin separators.
  Begin with the date controls; omit the screen title and toolbar add action. A single 56dp
  monochrome add button sits at bottom-end with 24dp/20dp margins and a 20dp corner radius.
  Reserve 96dp list bottom padding so the final row can scroll clear of the floating action.
  Use a seven-day date strip with 48dp targets and horizontal scrolling on narrow screens.
  In short landscape windows with enlarged text, use the date picker and range arrows instead of
  the secondary date strip so the list retains full-size action targets.
  One 28dp outlined/completed circle inside a 48dp target records the selected day. Show only the
  name; a 13sp muted remaining number follows a 17sp name with 12dp spacing for multi-count goals.
  Hide the number for single-count goals and completed rows; no multiplication sign or status subtitle.
  Each tap records one repetition; the check appears only at the daily target and undoes one on tap.
  Center check, name and menu vertically in compact 64dp-minimum rows, including wrapped names.
  Place rename, order and archive in the row menu. Hide habits on dates before their creation.
  New habits use a labeled editor and start today. Archiving hides the current list entry but keeps
  its inclusive archive-day history. Accepted writes survive tab replacement and theme recreation.
- Settings: segmented theme selector, grouped data actions, separate long-term archive actions.
  Destructive deletion remains labeled and uses its existing confirmation flow.
- Navigation: 57dp including its top separator at normal font scale. Place 20dp outline icons
  above 12sp labels. A shared rounded primary-soft highlight slides behind the selected item;
  selected icons and labels use primary text. Keep native selected/button accessibility semantics.
  Use a restrained 180ms transition; snap when system animations are disabled or bounds change.
  Use only this shared highlight for touch feedback; do not overlay per-item fading ripples.
  Settings uses a simple six-tooth gear and central hole.
  Keep the order Timer, Habits, Reports, Settings; each target remains at least 48dp.
- Editors: visible labels, scrollable content with the keyboard, focus borders, native validation.

## Invariants

Habits extend Room with independent tables through migration 7→8, count migration 9→10 and complete open backup v4.
Keep TimerCoordinator, reducer, alarms and poster data independent of habit commands.
No new network permission, background work, dependencies, runtime font downloads or per-second writes.
No allocations, text formatting or persistence access in the timer's `onDraw` path.
The Today summary reads Room invalidations; timer UI ticks do not query the database.
Count each eligible session once across overlapping active tags and sum active tag goals.
Use the existing Today completion-credit rules. Without a goal, show 0% with no fill; accessibility
still announces credited minutes and the missing goal.
Check normal-font light/dark and narrow screens in a running Android build for this revision.

## Design references

- [Apple HIG: Color](https://developer.apple.com/design/human-interface-guidelines/color)
- [Apple HIG: Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)
- [Apple HIG: Charts](https://developer.apple.com/design/human-interface-guidelines/charts)
- [Android: Accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)
- [DSEG font family and SIL Open Font License](https://github.com/keshikan/DSEG)
- [Seiko: the first six-digit LCD quartz watch](https://www.seiko-design.com/140th/en/topic/39.html)

These guide hierarchy, native interaction, semantic color, text scaling and touch targets.
UI/UX Pro Max searches informed icon consistency, secondary hierarchy and touch geometry.
Generic landing-page presets with oversized headings and generous empty space do not apply here.
The LCD uses the unmodified 23KB DSEG7 Classic Regular v0.46 font, bundled locally with its
full license in `app/src/main/assets/licenses/DSEG-LICENSE.txt`.
