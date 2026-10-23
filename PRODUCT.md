# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Primary: the owner's household.** One family in Pakistan with a Deye three-phase hybrid inverter, a battery bank and rooftop solar, living with daily scheduled load-shedding and unscheduled outages. The app exists first for them.
- **Readers in the house:** the owner, who is technical and wants the detail; and non-technical family members, who need two answers without reading anything: *is the mains on?* and *how long will the battery last?*
- **Secondary:** other solar owners with Solarman-connected hybrids in load-shedding countries, through the open-source release. Not a target to design for at the household's expense.

## Product Purpose

Watch the inverter around the clock and make a grid outage impossible to miss, then make the battery's remaining time obvious so the household can decide what to switch off. Success: nobody in the house is surprised by the inverter shutting down, and nobody has to open the Solarman app or read raw inverter values.

## Positioning

A Solarman app shows production and charts. This product exists for the outage: it decides carefully whether the mains are really gone, rings like an alarm clock (over the lock screen, through a banner, or via WhatsApp to the family), counts how long the outage has lasted, learns when load-shedding usually happens, and estimates runtime from the battery's real behaviour rather than its label.

## Operating Context

- Polls the Solarman OpenAPI. The inverter's Wi-Fi logger uploads about every 5 minutes; during an outage the app checks every 90 seconds.
- Outages cluster at scheduled times (in the demo and the owner's data, around 08:00 and 20:00, sometimes 14:00) and last 1 to 2 hours.
- The app is opened in two modes: a quick glance during the day, and urgently at night when the alarm wakes someone up. The ongoing notification, home-screen widget and Quick Settings tile are glanced at far more often than the app itself.
- Optional WhatsApp relay to a family group via a bridge on a spare phone (`tools/whatsapp-bridge`).

## Capabilities and Constraints

- Jetpack Compose and Material 3, minSdk 26, targetSdk 36. Glance widget, Quick Settings tile, Android 16 Live Update notification.
- Every feature and all detection, alert, forecast and runtime logic stays as built. Only the visual layer is being redesigned. The logic is covered by 154 unit tests in `domain/`.
- Readings can be stale, missing or contradictory. The UI must never present a stale reading as current (*Last known*, *Not reporting*) and must show missing values as "—", never 0.
- A demo mode with a simulated inverter exists for trying the app and for screenshots.
- **Open decision:** the product name and launcher icon are being replaced in the redesign. "Solar Grid" and the sun-with-bolt icon are not binding.

## Brand Commitments

- **Standing preference (2026-10-23): conventional and subtle.** After two direction rounds the owner chose the category standard: "draw inspiration from Solarman's own app and just make it subtle, cool, well cooked, well thought". **SOLARMAN Smart** is the craft bar and sibling product: light cool-grey ground, white cards, thin connecting lines in the flow diagram, regular-weight figures with small units, line icons, and standard segmented controls.
- **Rejected:** a near-black ground, neon accents, glowing edges, glass cards and particle comets ("AI slop").
- **Name and icon:** replaced. The new name is **Roshni** (روشنی, "light"), after the household's own phrase for an outage, *light chali gayi*. The icon is a house with its window lit.

## Evidence on Hand

- Real fixture from the owner's inverter, captured during a real outage: `app/src/test/.../DeyeDumpTest.kt`.
- Demo-mode screenshots of the previous design: `docs/screenshots/` (anti-reference).
- No testimonials, user counts or press. Do not invent any.

## Product Principles

1. **Two answers before anything else:** is the mains on, and how long the battery will last. Everything else is for the person who wants more.
2. **Never claim what isn't known.** Freshness and uncertainty are part of the content.
3. **The outage is the product's moment.** The calm days should be quiet; the outage should be unmistakable.
4. **Readable by anyone in the house,** including family who don't read graphs and someone who has just been woken up.

## Accessibility & Inclusion

- Non-technical readers: plain words, no jargon on the main screen, and numbers given with their meaning ("lasts about 2h 50m", not "SoC 64%" alone).
- Large font sizes (tested at 1.3×), TalkBack labels, 48 dp touch targets, "Remove animations" honoured.
- Urdu or right-to-left support is not a requirement now, but the layout shouldn't make it hard later.
