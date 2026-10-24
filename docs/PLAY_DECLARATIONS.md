# Google Play Console answers

Ready-to-paste answers for Roshni's Play Console forms. They match the app as of v2.1.0 and [PRIVACY.md](PRIVACY.md). If the app starts sending anything new, update both files.

Privacy policy URL, wherever Play asks for it:

```
https://github.com/hafeee44-lab/Roshni/blob/main/docs/PRIVACY.md
```

---

## 1. App access (Policy → App content → App access)

Choose **All or some functionality is restricted** and add one set of instructions:

- **Name:** Demo mode (no login needed)
- **Username / password:** leave empty
- **Any other information:**

```
Roshni reads a hybrid solar inverter through the user's own Solarman OpenAPI account. Reviewers do not need one: every feature works in demo mode, which simulates an inverter and two weeks of history on the device.

1. Open the app. On the first screen tap "Try with demo data".
2. On "Let it wake you", tap Allow on each row (or "Continue anyway"), then Continue.
3. Tap "Start watching". The foreground service starts and an ongoing status notification appears.
4. On Home, scroll to the bottom card, "Demo controls", and tap "Simulate outage". Within a few seconds the outage alarm rings and shows full screen over the lock screen (or as a heads-up notification if full-screen notifications are off). Slide or tap "Stop alarm" to stop it, and tap "Restore" to bring the simulated mains back.
5. Settings → "Test the alarm" rings it once on demand. Settings → Reliability shows each permission's state and links to the system settings page for it.

Demo mode sends no network traffic.
```

---

## 2. Data safety (Policy → App content → Data safety)

### Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (Solarman is only reached over HTTPS) |
| Which of the following methods of account creation does your app support? | **My app does not allow users to create an account** (users sign in to their existing Solarman account; Roshni has no accounts of its own) |
| Do you provide a way for users to request that their data is deleted? | **Yes.** Sign out in Settings removes the Solarman credentials, Settings → Data → Clear history removes readings, and uninstalling removes everything. The developer holds no copy. |

### Data types

Declare these two, and nothing else:

**Personal info → Email address**
- Collected: **Yes**. Shared: **No**
- Processed ephemerally: **No**
- Required or optional: **Required** (needed to sign in to Solarman. Demo mode works without it.)
- Purpose: **App functionality**

**Personal info → User IDs** (the Solarman OpenAPI app ID and account)
- Collected: **Yes**. Shared: **No**
- Processed ephemerally: **No**
- Required or optional: **Required**
- Purpose: **App functionality**

Why the answers look like this:

- *Collected* in Play's sense means the data leaves the device. The email and app ID go to Solarman's API when the user signs in, so they count, even though the developer never receives them.
- *Not shared:* the user signs in to their own Solarman account, which is a user-initiated transfer to the service they chose. That falls under Play's exemption for "user-initiated action". No third party gets the data from the developer.
- The **password** is not a Play data type. It is hashed on the device and sent only as a SHA-256 hash, as part of the same sign-in. If a reviewer asks, it falls under the same answers as the email.
- **Inverter readings** come from Solarman to the phone. They are not personal data sent off the device.
- **WhatsApp relay** (optional, off by default): short alert texts ("Grid outage, battery 82%") go to a bridge address the user types in and runs themselves. They contain no personal data from Play's list, and the user chooses the destination, so nothing is declared. If you'd rather be extra careful, add **App activity → Other actions**, collected, not shared, optional, purpose *App functionality*.
- Android Auto Backup of settings and history goes to the user's own Google account and is not collection by the app.
- No analytics, crash reporting, advertising ID, location, contacts, files or device IDs.

### Security practices section
- Data is encrypted in transit: **Yes**
- Users can request data deletion: **Yes** (as above)
- Committed to Play Families Policy: **No** (not a children's app)
- Independent security review: **No**

---

## 3. Foreground service permissions (Policy → App content → Foreground service permissions)

Roshni declares one foreground service type: **specialUse**.

- **Which feature uses it?** Choose **Other** and use this description:

```
Roshni's core feature is a power-outage alarm. A foreground service (MonitorService) polls the user's solar inverter through the Solarman API just after each inverter upload (usually every 5 minutes, every 90 seconds during an outage), and when it sees that the mains grid has failed it immediately rings an alarm and posts a full-screen notification. Outages are unpredictable and often happen at night, so the check has to keep running indefinitely while the app is closed and the phone is asleep. A user-visible ongoing notification shows the live grid and battery state the whole time, and the user can stop monitoring at any time with Settings → "Watch for outages".

No other foreground service type fits:
- dataSync is limited to 6 hours a day on Android 15+, and monitoring has to run 24 hours a day.
- WorkManager periodic work runs at most every 15 minutes and is deferred in Doze, so an outage alarm would arrive up to an hour late.
- Firebase Cloud Messaging is not possible: Solarman offers no push or webhook API, and Roshni has no server of its own.
- The service does not sync, upload or back up data, play media, use location or the camera, or connect to a companion device.
```

- **Impact if the task is deferred or interrupted:**

```
The user is not told that the mains have failed. They do not get time to cut back the load before the battery runs out, and the house goes dark without warning, often at night.
```

- **Video link:** see the script in section 4. Upload it as an unlisted YouTube video and paste the link.

The manifest already carries the matching subtype text in `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.

---

## 4. Demo video script (about 60 seconds)

Record on a phone or the emulator with screen recording on (`adb shell screenrecord /sdcard/roshni.mp4`, or the emulator's own recorder). Use English. No voice-over is needed, but short on-screen captions help the reviewer.

1. **0:00** Fresh install. Open Roshni. Caption: *"Roshni rings an alarm when the mains power fails."*
2. **0:05** Tap **Try with demo data**. Caption: *"Demo mode simulates an inverter, so no account is needed."*
3. **0:10** On **Let it wake you**, allow notifications and show the Full-screen alarm row, then **Continue** → **Start watching**.
4. **0:20** Pull down the notification shade and show the ongoing **status notification** (grid state and battery). Caption: *"The foreground service checks the inverter around the clock and shows its state here."*
5. **0:28** Back in the app, scroll to **Demo controls** and tap **Simulate outage**. Straight away press the power button to lock the phone.
6. **0:35** The alarm turns the screen on, rings and shows full screen over the lock screen. Caption: *"When the mains fail, the service raises a full-screen alarm, even with the app closed and the phone locked."*
7. **0:45** Slide to stop the alarm. Unlock, tap **Restore**.
8. **0:50** Open Settings and switch off **Watch for outages**. The status notification goes away. Caption: *"The user can stop monitoring at any time."*

---

## 5. Full-screen intent (Policy → App content → Full-screen intent)

- **Does your app use the full-screen intent permission?** **Yes**
- **Core functionality:** choose **Alarm** ("Alarm clock or timer" or the closest wording the form shows).
- **Justification:**

```
Roshni is an alarm app for power cuts. When the household's mains supply fails it raises an alarm, with sound and a full-screen alarm screen over the lock screen that the user stops with a slide, so the user is woken up, the same way an alarm clock wakes them. It only fires for a detected grid outage, a critical battery level, or a test the user starts. It is never used for marketing, reminders or ordinary notifications.
```

If Google does not pre-grant the permission, the app keeps working. On Android 14+ the alarm still rings and appears as a high-priority, ongoing heads-up notification with a Stop action. Settings → Reliability shows "Full-screen alarm: Off" and a Fix button that opens the system page for it. This was checked on a Pixel emulator (API 37) with the permission denied.

---

## 6. Exact alarms

Roshni uses **SCHEDULE_EXACT_ALARM** (granted by the user, optional), not USE_EXACT_ALARM. Play does not require a declaration for SCHEDULE_EXACT_ALARM, and the app falls back to inexact `setAndAllowWhileIdle` when it is not granted. If the Console or a reviewer asks anyway:

```
Roshni schedules its next inverter check with setExactAndAllowWhileIdle so that the check lands just after the inverter's own upload (usually every 5 minutes, every 90 seconds during an outage). That keeps outage alarms within seconds of the mains failing. The permission is optional: the user grants it under Settings → Reliability → Precise timing, and without it the app uses inexact alarms and the alarm may arrive a few minutes later. It is not used for calendar events, reminders or anything user-facing besides the outage check.
```

---

## 7. Content rating (Policy → App content → Content ratings)

- **Category:** *All other app types* (a utility; not a game, not social, not news)
- Answer **No** to every question: violence, fear, sexuality, language, controlled substances, gambling, crude humour, user-generated content, user-to-user communication, sharing location with others, digital purchases, and unrestricted internet access through a browser.
  - The WhatsApp relay is not in-app user-to-user communication. It forwards the app's own alert texts to the user's own bridge.
- Expected result: **Everyone / PEGI 3 / USK 0**.

---

## 8. The other App content forms

| Form | Answer |
|---|---|
| Ads | **No, my app does not contain ads** |
| Target audience and content | **18 and over** (a home-energy utility for adults; avoids the Families programme) |
| News app | **No** |
| Health apps | **None** of the listed features |
| Financial features | **None** |
| Government app | **No** |
| Advertising ID | **No**, the app does not use an advertising ID |
| COVID-19 contact tracing / status | **No** |

Store listing category: **Tools** (or **House & home**). The title, short and full descriptions and screenshots are in `fastlane/metadata/android/en-US/`.

---

## 9. What the developer has to do by hand

1. Create a Google Play developer account (personal, US$25 one-off) and complete identity verification.
2. Create the app: name *Roshni*, default language English (en-US), App, Free.
3. Fill in every App content form above, and the store listing from `fastlane/metadata`.
4. **Closed test first** (required for personal accounts created after November 2023): upload `Roshni-vX.Y.Z.aab` from the GitHub release to a closed testing track, invite at least **12 testers** (a Google Group or email list), and keep at least 12 of them opted in for **14 consecutive days**.
5. Enrol in **Play App Signing** on the first upload. The key in `~/.android-keys/roshni-release.jks` becomes the *upload key*, and Google holds the app signing key.
6. After the 14 days, apply for production access from the Dashboard, answer the questions about the closed test, and then promote the release to production.
