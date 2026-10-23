# Roshni design system

Roshni is a calm utility. It should feel like a well-made appliance app (the bar is SOLARMAN Smart), not a dashboard trying to impress. A family member who glances at it should know in a second whether the mains are on and how long the battery will last.

Why the product exists and who it's for are in [PRODUCT.md](PRODUCT.md). The code for everything here is in `ui/theme/Theme.kt` and `ui/components/`.

## Principles

- **Red means a cut, and nothing else.** No red for errors-in-general, forecasts or decoration. When the mains are on, the screen is quiet.
- **Colour names an energy source.** Amber is solar, slate is the grid, cobalt is the home and the brand. The battery takes the colour of its own level.
- **Numbers lead, words explain.** Large tabular figures, each with a plain-language caption underneath ("backup if the mains go").
- **No glow, glass, gradients or particles.** These are what the previous version was rejected for. Depth comes from white cards on a grey ground, not from shadows or blur.
- **Plain words.** "Mains on / Mains off", "cut", "time left". Never "grid status: OFFLINE".

## Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#F2F4F7` | `#111318` | Screen ground |
| `surface` | `#FFFFFF` | `#1B1F26` | Cards |
| `surfaceVariant` | `#EDF0F4` | `#262B34` | Tracks, idle nodes |
| `outline` / `outlineStrong` | `#E3E7ED` / `#C9CFD8` | `#2C313B` / `#3B414D` | Dividers / idle wires |
| `ink` / `ink2` / `ink3` | `#16191F` / `#4F5663` / `#6A7180` | `#E7E9EE` / `#B4BAC5` / `#959CA9` | Text, three levels |
| `brand`, `home` | `#2457C5` | `#9DB9F5` | Buttons, selection, home node |
| `solar` | `#F2A007` | `#F5B847` | Solar node, wires and bars |
| `grid` | `#5F6E86` | `#9AA8BF` | Grid node and wires |
| `ok` | `#1B8A4E` | `#5CC98C` | Mains on, healthy battery |
| `down` | `#C62828` | `#F2726A` | Cuts only |
| `downContainer` | `#FCEBEA` | `#3A1D1D` | Status card during a cut |
| `warn` | `#8F5A00` | `#E6B45C` | Stale data |

Battery colour comes from `SolarColors.battery(soc, lowPercent)`. It is green above the low threshold, amber near it and red below it.

The alarm screen has its own deep red (`#8E1B1B`), which "breathes" slowly to `#9E2525`. When the mains come back it turns green (`#1B6B43`).

## Type

Roboto (the system font), with tabular numbers on every figure.

| Style | Size / weight | Use |
|---|---|---|
| `figureXL` | 57 / Regular | Battery %, outage clock |
| `figureL` | 32 / Regular | Secondary hero numbers |
| `figure` | 24 / Regular | Tile values |
| `figureS` | 16 / Medium | Flow values, inline numbers |
| `headline` | 28 / Regular | Onboarding headings |
| `title` | 20 / Medium | Screen titles, "Mains on" |
| `cardTitle` | 16 / Medium | Card headers |
| `body` / `bodyStrong` | 14 / Regular, Medium | Text |
| `caption` | 12 / Regular | Labels under figures, hints |

Units are set smaller next to their value and joined with a non-breaking space (`String.nb()`), so "5h 20m" never splits across lines.

## Layout

- 16 dp screen gutters and 12 dp gaps between cards. Cards have 16 dp corners and 16 dp padding (20 dp on the status card).
- Home, from top to bottom: status card, power flow, today tiles (2×2 plus self-powered), next likely cut, day chart, grid facts.
- Everything has to work at 130% font size. The flow diagram grows with font scale, and tile values use compact forms ("2 min", "1h 20m").

## Components

- **SCard**: a white (or dark) rounded card with no shadow.
- **Figure**: a value, a unit and a caption.
- **BatteryMeter**: ten segments filled in the battery colour.
- **PowerFlow**: modelled on Solarman's layout. Solar sits on top, then the inverter hub, then battery, home and grid. Wires bend at rounded elbows. A live wire is 2.5 dp with dashes marching in the direction of flow; an idle wire is a 1 dp outline. A dead grid gets a red dashed wire and a red ring, and it is the only node with a ring.
- **Charts**: the day chart (solar area, home line, dashed battery line on a 0–100% scale, hatched bands for cuts, long-press to scrub), the outage map (14 days × 24 hours; tap or drag), and the schedule ribbon (forecast slots in a lighter tint).
- **Icons**: 1.6 stroke line icons. Small sizes use the simple `Pylon`; the alarm screen uses the detailed `Tower`.
- Settings use M3 `Switch`, `Slider` and `SegmentedButton`, grouped with `ListGroup`.

## Motion

Motion explains a change; it is never decoration. Numbers roll when they change, the status card cross-fades (600 ms) between on and off, wires march, and the alarm background breathes. When "Remove animations" is on (`LocalReduceMotion`), every one of these stops.

## Identity

The name is Roshni (روشنی, "light"). The icon is a white house with an amber lit window on cobalt (`#2457C5`). In the status bar it's a house (`ic_stat_home`) or a crossed-out house (`ic_stat_home_off`).
