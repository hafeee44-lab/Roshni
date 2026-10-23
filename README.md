<h1 align="center">Roshni</h1>

<p align="center"><strong>Know the moment the mains go off.</strong><br>
An open-source Android app that watches your hybrid solar inverter and rings like an alarm clock when the mains fail, then tells you exactly how long your battery will last.</p>

<p align="center">
  <img src="docs/screenshots/live.png" width="220" alt="Home: mains on, battery and power flow">
  <img src="docs/screenshots/outage.png" width="220" alt="Home during a cut, with the outage clock">
  <img src="docs/screenshots/alarm.png" width="220" alt="Full-screen outage alarm with slide to stop">
</p>
<p align="center">
  <img src="docs/screenshots/insights.png" width="220" alt="History: learned load-shedding schedule and outage map">
  <img src="docs/screenshots/settings.png" width="220" alt="Settings: reliability checklist and alarm options">
  <img src="docs/screenshots/dark.png" width="220" alt="Dark theme">
</p>

---

Built for places where load-shedding is part of the day. Your inverter already switches to the battery when the mains drop; what it doesn't do is tell you. Roshni (روشنی, "light") reads the same data your inverter's Wi-Fi logger sends to the Solarman cloud, decides carefully whether the mains are really gone, and wakes you only when it matters.

## Features

**Alerts that actually reach you**
- **Full-screen outage alarm** over the lock screen, with a gentle volume ramp and a deliberate slide-to-stop. It stops on its own when the mains come back.
- **Optional ring in silent mode** using the alarm volume, like an alarm clock. **Quiet hours** turn outages into silent notifications at night, while a critical battery still rings.
- **Battery alerts**: low and critical thresholds during an outage, heavy load on battery, and an optional "battery full" nudge to run heavy appliances on spare solar.
- **System health**: inverter gone quiet, inverter fault, running hot.
- **Every alert kind has its own notification channel**, so Android's settings can silence one without silencing outages.
- **Android 16 Live Update** during an outage: a status-bar chip with the battery level, a segmented charge bar and the outage clock.
- **WhatsApp relay** (optional) so the whole household hears about it. See [`tools/whatsapp-bridge`](tools/whatsapp-bridge).

**Numbers you can trust**
- **Careful detection.** It reads the grid relay first, then grid frequency, then the voltage on every phase, then the power balance. A guess based on the power balance has to be confirmed by a second, genuinely new upload before anything rings.
- **Never claims a state from stale data.** If the logger stops uploading (often because the router isn't on backup power), the app says *Not reporting* instead of showing the last reading as current.
- **Runtime that learns your battery.** It starts from the rated capacity, then recalibrates from real discharges. Your inverter's reserve is configurable.
- **Charge time**: "full in 1h 20m" while charging.

**Insights from your own history**
- **Learned load-shedding schedule.** When outages cluster around a time of day, it predicts the next one ("~20:00, in 2h 10m, 12 of the last 14 days, usually 1h 30m") and warns you beforehand, including whether your battery should cover it.
- **Outage map**: a 14-day hour-by-hour heatmap where the schedule jumps out as columns.
- **Grid uptime**, outage count, time on battery, longest and typical outage, for today, 7 days or 30 days.
- **Day charts** of solar, home use and battery level with outages shaded. Press and drag to read values.
- **Solar production and self-sufficiency** per day.
- **CSV export** of every outage, for a spreadsheet or a complaint to your utility.

**Everywhere you look**
- Home-screen widget, Quick Settings tile and an ongoing notification.
- A **demo mode** with a simulated system and two weeks of history, so you can try everything without an inverter.
- Dark and light themes, honouring "Remove animations" and large font sizes.

<p align="center">
  <img src="docs/screenshots/widget.png" width="180" alt="Home-screen widget">
  &nbsp;&nbsp;
  <img src="docs/screenshots/live-update.png" width="260" alt="Ongoing notification during a cut, with time left and battery bar">
</p>

## Does it work with my inverter?

If your inverter reports to **Solarman** (SOLARMAN Smart / SOLARMAN Business), it probably works. That covers many Deye, Sunsynk, Sol-Ark and other hybrids with a Solarman Wi-Fi stick.

It has been tested on a **Deye three-phase low-voltage hybrid (SG05)**. Metrics are found by their known key first and by their human-readable name second, so other models usually work out of the box. If yours doesn't, open **Settings → Inverter data**, copy the dump, and attach it to an issue.

## Getting started

### 1. Get Solarman OpenAPI credentials

The app talks to Solarman's official OpenAPI, which needs an **App ID** and **App Secret**. Solarman issues these free to plant owners on request. Contact Solarman support, say it's for monitoring your own plant, and include the email you sign in with. See Solarman's [OpenAPI FAQ](https://helpcenter.solarmanpv.com/portal/en/kb/articles/open-api-faq-summary).

Your **email and password** are the ones you already use in the Solarman app. The password is hashed with SHA-256 the moment you type it (that's what the API takes) and is stored encrypted with an Android Keystore key. It never leaves your phone except to Solarman.

### 2. Install

Build it yourself (see below). Release builds will follow.

### 3. Set up

The first launch walks you through it: sign in, pick your inverter from the list, then allow the three things Android needs before an alarm can reliably reach you:

| Permission | Why |
|---|---|
| Notifications | Every alert is a notification. |
| Full-screen alarm | Lets the alarm take over a locked screen. |
| Run in background | Stops Android pausing checks while the phone sleeps. |
| Precise timing *(optional)* | Checks land right after each inverter upload. |

Xiaomi, Oppo, Vivo, Huawei and Samsung phones often also need **Autostart** or **Never sleeping** turned on. [dontkillmyapp.com](https://dontkillmyapp.com) has per-brand steps.

## Building

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with platform 37.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

**Skip onboarding in your own builds.** Put your credentials in `local.properties`, which is gitignored and never committed. Only the password's SHA-256 is stored:

```properties
solarman.appId=...
solarman.appSecret=...
solarman.email=you@example.com
solarman.passwordSha256=...   # printf '%s' 'your-password' | shasum -a 256
solarman.deviceSn=...
```

## How it works

```
Solarman OpenAPI ──► MonitorService (foreground, alarm-chained polling, wake lock per poll)
                        │
                        ├─ GridAnalyzer      metrics → Reading → Verdict (relay / Hz / V / power balance)
                        ├─ AlertPlanner      pure: state + observation → new state, alerts, events
                        ├─ OutagePredictor   learned schedule from local history
                        └─ RuntimeEstimator  self-calibrating battery model
                        │
                        ▼
      StateStore (prefs) + HistoryDb (SQLite) ──► screens, widget, tile, notifications
```

- **Polling adapts.** It aims just after the inverter's learned upload cadence (usually every 5 minutes), polls every 90 seconds during an outage, and backs off exponentially when Solarman is unreachable.
- **All the decision-making is pure Kotlin with no Android dependencies**, in `domain/`, and covered by unit tests. Start there if you want to change when or how the app alerts.
- **Data stays on the phone.** There are no analytics and no accounts of our own. The only network traffic is to Solarman and, if you set one up, your own WhatsApp bridge.

```
app/src/main/java/io/github/hafeee44lab/solargrid/
├── domain/    detection, alert planning, prediction, insights (pure + tested)
├── data/      Solarman API, encrypted account store, settings, SQLite history, demo inverter
├── service/   foreground monitor, notifications, alarm player, boot receiver, QS tile
├── widget/    Glance home-screen widget
└── ui/        Compose screens, design system, charts and the power-flow diagram
```

Who it's for is in [PRODUCT.md](PRODUCT.md). Colours, type, layout and component rules are in [DESIGN.md](DESIGN.md).

## Contributing

Bug reports with an inverter dump, translations, support for more inverters and UI polish are all welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

[MIT](LICENSE).

*Not affiliated with Solarman or any inverter manufacturer.*
