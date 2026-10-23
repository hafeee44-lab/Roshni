---
version: 1
slug: "n-java-io-github-hafeee44lab-solargrid-ui-c9eb0480"
primary_target: "app/src/main/java/io/github/hafeee44lab/solargrid/ui"
related_targets: ["app/src/main/java/io/github/hafeee44lab/solargrid/widget"]
---

# Surface: the Roshni app (Home, History, Settings, onboarding, alarm, widget)

Mode: Operate. Native Android, Jetpack Compose with Material 3.

Audience and job: the household checks whether the mains are on and how long the battery will last. The owner digs into history, the forecast and settings. Used by day in daylight and by night during outages, about equally.

Constraints: keep every feature and all logic. Material 3 navigation and controls. Both themes first-class. Readable at 1.3× font size; TalkBack; 48 dp touch targets.

Memorable moment: the outage card. The home screen's single status card turns from a quiet white "Mains on" to a red-tinted "Mains off" with a live clock and the battery's time left.

## Direction contract

THESIS: the category standard, played straight at SOLARMAN Smart's craft level, and better at the one thing Solarman ignores: the outage. It refuses the dark dashboard with glowing nodes and neon comets.

OWN-WORLD:
- Light: a cool grey ground (#F2F4F7), white cards with 16 dp radius, no shadow or glow.
- Dark: a slate ground (#111318) with #1B1F26 cards.
- Energy colours: amber solar, slate grid, the battery coloured by its own level (green, amber, red, like a phone), a cobalt brand for selection. Red is used only for an outage.
- Type: Roboto (the Material scale). Figures in regular weight with small grey units and grey labels beneath. Thin line icons.

STORY: in one second you know whether the mains are on and how long the battery lasts. Then you see where power is flowing, today's totals, and when the next cut is likely.

FIRST VIEWPORT: from top to bottom:
1. Top bar: home name, Demo chip, refresh.
2. Status card: a coloured dot with "Mains on · since 09:40", a 57 sp "64%", a ten-cell battery meter and "Lasts about 2h 50m if the mains go". During an outage the card turns red with the live clock.
3. Flow diagram: Solarman's orthogonal routing (solar above the inverter hub, then battery, home and grid below). Active wires carry slow marching dashes in their source colour.
4. The beginning of the 2×2 tiles for today.
5. A standard Material NavigationBar.

FORM: canon (the standing exit), chosen by the owner after re-roll 1 ("choose by yourself… inspiration from Solarman… subtle"). seed:49d41d2c5a67a317

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
