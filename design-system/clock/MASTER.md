# Clock Android Design System

> UI source of truth. Page-specific files may override this document only when they record the reason.

## Product character

- Calm, minimal, local-first productivity tool.
- One primary action per screen; no gamification, streak pressure, ads, social rooms, or decorative motion.
- The timer is the visual center. Configuration is progressively disclosed below it.

## Semantic colors

| Token | Light | Dark | Usage |
|---|---:|---:|---|
| Primary | `#D95D39` | `#FFB59D` | Start action and active progress |
| On primary | `#FFFFFF` | `#5B1A07` | Text on primary |
| Accent | `#2E7D6B` | `#8ED3BF` | Secondary progress and positive data |
| Background | `#FFF9F3` | `#17130F` | Window background |
| Surface | `#FFFFFF` | `#211C17` | Cards and bottom navigation |
| On surface | `#241F1B` | `#F2E9E1` | Primary text |
| Muted text | `#655D56` | `#C8BDB4` | Supporting text |
| Outline | `#D7CEC5` | `#544B44` | Tracks, dividers and cards |
| Danger | `#B3261E` | `#FFB4AB` | Abort and validation errors |

Never communicate stage or status with color alone; pair color with text and progress geometry.

## Type and numbers

- Use the Android system sans font; do not bundle remote fonts.
- Timer digits use tabular/monospaced figures where available and never shift layout.
- Scale: helper 14sp, body 16sp, title 24–28sp, timer 42–64sp.
- Respect system font scaling; labels wrap instead of truncating.

## Layout

- 4/8dp spacing system: 4, 8, 16, 24, 32dp.
- Interactive targets are at least 48x48dp with at least 8dp separation.
- Main content is a single readable column, max logical width 640dp on large screens.
- System bars and gesture navigation insets must not cover controls.
- Four top-level destinations: Timer, History, Reports, Settings.

## Components

- Native Android buttons, text fields, switches and RecyclerView are preferred.
- Cards use a 16dp radius, 1dp semantic outline and no heavy shadow.
- Destructive Abort is visually separated from the primary Pause/Resume action.
- Form fields always have visible labels and inline cause-and-recovery error messages.
- Empty history/report states explain what will appear and how to create it.

## Timer renderers

- Digital: one update per second while visible.
- Ring: outer ring is total progress; inner ring is current phase progress.
- Hourglass: at most 30fps, preallocated particles, no animation off-screen or while paused.
- All renderers consume the same `TimerUiState`; no renderer owns timer state.
- Custom backgrounds are sampled, WebP encoded and covered by a contrast scrim.

## Motion and accessibility

- Honor both the system animation scale and the app Reduce Motion setting.
- Motion must communicate progress; no decorative entrance animations.
- Every interactive control has a semantic label and pressed/disabled state.
- Renderers expose a content description containing task, category, phase and remaining time.
- Light and dark color pairs must maintain readable contrast.

## Forbidden patterns

- Emoji used as structural icons.
- Gesture-only critical actions.
- Background services or animations used merely to keep the timer alive.
- Per-frame allocation, Bitmap decoding in `onDraw`, or database reads from a View.
- Online fonts, analytics scripts, advertising SDKs and remote theme assets.
