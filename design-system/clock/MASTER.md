# Clock Android Design System

> Anthropic-inspired visual source of truth. This is an interpretation for Clock, not an Anthropic product or brand asset.

## Product character

- Restrained, editorial, technical and quiet.
- Warm paper surfaces, near-black typography and one terracotta-orange accent.
- Large whitespace and strong type hierarchy replace shadows, gradients and decorative effects.
- The timer remains the visual center; every screen has one clearly dominant action.

## Semantic colors

The light palette is sampled from the current Anthropic website and adjusted where needed for Android accessibility.

| Token | Light | Dark | Usage |
|---|---:|---:|---|
| Primary | `#D97757` | `#E58A6A` | CTA, active state and progress |
| On primary | `#141413` | `#141413` | Text on orange; contrast 5.9:1 in light mode |
| Primary soft | `#F2DFD7` | `#3B2821` | Selected navigation and notes |
| Background | `#FAF9F5` | `#141413` | Window canvas |
| Surface | `#F0EEE6` | `#23231F` | Timer canvas and grouped controls |
| Raised surface | `#FFFDF8` | `#2B2B26` | Inputs and data cards |
| On surface | `#141413` | `#FAF9F5` | Primary text |
| Muted text | `#65645F` | `#B0AEA5` | Supporting copy; AA contrast |
| Outline | `#C6C4BA` | `#4A4944` | Input and component boundaries |
| Soft outline | `#E8E6DC` | `#32322E` | Dividers and quiet cards |
| Danger | `#A43E32` | `#F09A8E` | Abort and validation errors |

Orange is an accent, not a background theme. Never place white text on `#D97757`; it does not meet normal-text contrast.

## Typography

- Display and screen titles: Android system serif, regular weight, 32–34sp, slightly tight tracking.
- Body and controls: system sans, 14–16sp.
- Eyebrows and field labels: sans medium, 12–13sp, expanded tracking.
- Timer digits: monospace, 32–68sp, tabular and allocation-free.
- No downloadable fonts or runtime font provider.

## Layout and components

- 4/8dp rhythm with 24dp phone gutters and 24–40dp section gaps.
- Cards use 4dp radius, 1dp hairline and no elevation.
- Timer canvas uses 8dp radius; avoid pill-shaped containers.
- Primary button is solid terracotta orange with near-black text.
- Secondary button is warm raised paper with a 1dp outline.
- Inputs are 52dp high; all actions remain at least 48dp.
- Bottom navigation uses a soft-orange selected plane plus text, never color alone.

## Motion and accessibility

- Only timer progress is animated; no decorative screen entrances.
- Honor system animation scale and the app Reduce Motion setting.
- Primary text contrast is at least 4.5:1 in both themes.
- Selected, pressed and disabled states remain distinguishable without movement.
- Every field keeps a visible label and every renderer exposes a content description.

## Forbidden patterns

- Gray-on-gray body text, white-on-orange text, gradients, glow, glassmorphism and heavy shadows.
- Large soft 16–24dp cards, playful pills or saturated secondary accent colors.
- Remote fonts, brand logos, copied Anthropic illustrations or claims of affiliation.
- Per-frame allocations, image decoding in `onDraw`, or animation while hidden.
