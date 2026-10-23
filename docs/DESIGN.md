# Design

Solar Grid is used in two very different moments: a glance at the numbers on an ordinary afternoon, and being woken at 2 a.m. because the mains just failed. The design serves both. It stays calm and precise when nothing is wrong, and becomes unmistakable when something is.

## Principles

1. **The colour of the screen is the answer.** The background glow takes on the grid state (mint for online, coral for down, slate for unknown), so you know before you read anything.
2. **Never claim what you don't know.** Stale or failed readings show *Last known* or *Not reporting* and hide live numbers. Missing values are "—", never 0.
3. **Motion carries meaning.** Comets on the flow diagram move at a speed set by the watts on that wire, the sun turns faster with more output, and a low battery breathes. Nothing moves just to decorate.
4. **Energy has one colour everywhere**: in the diagram, the charts, the widget and the notifications.
5. **Respect the phone.** The app honours edge-to-edge insets, system Back, "Remove animations" and large font sizes, and every touch target is at least 48 dp.

## Colour

Tokens live in `ui/theme/Theme.kt` (`SolarColors`) and are mapped onto Material 3 colour roles. All text colours meet WCAG AA (4.5:1) on both `bg` and `surface` in their theme.

| Token | Meaning | Dark | Light |
|---|---|---|---|
| `solar` | Solar production | `#FFB23F` | `#A86400` |
| `grid` | Mains / grid import and export | `#4CC9FF` | `#0675AD` |
| `battery` | Battery and state of charge | `#9B8CFF` | `#6A55F2` |
| `home` | House load | `#EEF2F8` | `#12161D` |
| `ok` | Grid online, charging, all good | `#2BD99F` | `#08875D` |
| `down` | Grid down, critical, destructive | `#FF4D6A` | `#D9203F` |
| `warn` | Needs attention, not an emergency | `#FFC547` | `#93600F` |
| `ink` / `ink2` / `ink3` | Text: primary, secondary, tertiary | `#EEF2F8` / `#A6B0C0` / `#8590A2` | `#12161D` / `#4A5262` / `#626A79` |
| `bg` / `surface` / `surfaceHigh` | Window, cards, raised elements | `#070A10` / `#0F141D` / `#161D29` | `#F5F2ED` / `#FFFFFF` / `#F7F5F2` |

`ok` and `down` are reserved for state. Nothing decorative uses them.

The dark theme is designed first, because outages happen at night. The light theme is a warm paper white, not an inversion.

## Type

- **Space Grotesk** (variable, weights 300–700, bundled under the OFL) for headings and every number. Numbers use tabular figures (`tnum`), so a ticking value never shifts sideways.
- The system sans (Roboto) for body text and controls.
- The scale is in `SolarType`: `display` 40, `hero` 34, `title` 20, `number` 26, `numberSmall` 15, `body` 14, `caption` 12, and `label` 11 (uppercase, tracked, used only to title a group).
- Everything is in sp. Layouts are checked at a 1.3× system font size.

## Motion

- **Rolling digits** (`RollingText`): each changed digit slides in the direction of the change, in 320 ms with FastOutSlowIn easing. Screen readers hear the whole value once.
- **Power flow**: the time comes from the frame clock and is read only in the draw phase or graphics layer, so the diagram animates without recomposing.
- **Tabs**: fade-through with a slight scale-in. The nav indicator springs (damping 0.72).
- **Alarm**: shock-wave rings and a breathing glow, never a strobe.
- With "Remove animations" on, decorative motion stops. State changes still crossfade.

## Components

| Component | Where | Notes |
|---|---|---|
| `Panel` | every card | 26 dp corners, hairline edge, optional accent glow. No nesting. |
| `Atmosphere` | screen background | Two slow radial glows tinted by grid state. |
| `PowerFlow` | Live, onboarding | Solar / grid / battery / home around an inverter hub. |
| `BatteryRing` | Live | A 270° arc with a reserve notch, a shimmer while charging and a breath when low. |
| `DayChart` | Live, Insights | Solar area, home line, state-of-charge line and shaded outages. Tap or long-press-drag to read values. |
| `OutageHeatmap` | Insights | 14 days × 24 hours. Tap a cell for details. |
| `ScheduleRibbon` | Live forecast | The day as a bar, with learned slots and a marker for now. |
| `Segmented`, `SliderRow`, `ToggleRow`, `SettingRow` | Settings | Built on Material 3 switches and sliders. |

Icons are a small hand-drawn set in `ui/components/Icons.kt`: 24 px viewport, 1.8 px rounded stroke. Notification and launcher icons are vector drawables in `res/drawable`.

## Copy

- Say what happened and what it means: "Mains are back after 1h 12m. Battery at 64%."
- Name the recovery in errors: "Sign-in rejected — check the App ID, secret, email and password."
- Use plain time words: "about 2h 50m", "empty around 21:40".
