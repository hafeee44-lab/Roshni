# Roshni privacy policy

Effective 24 October 2026. Applies to the Roshni Android app (`io.github.hafeee44lab.solargrid`), on Google Play and on GitHub.

Short version: Roshni has no servers, accounts, ads or analytics. The developer never receives your data. The app talks to Solarman to read your own inverter. If you turn on the optional WhatsApp relay, it also talks to a bridge you run yourself. Nothing else.

## What the app stores, and where

Everything stays on your phone, in the app's private storage, which other apps cannot read.

| What | Why | How it is kept |
|---|---|---|
| Solarman app ID, account email, inverter serial number | To sign in to Solarman and find your inverter | Private app storage |
| Solarman app secret and password hash | To sign in to Solarman | Encrypted with an AES-256 key held in the Android Keystore. The key cannot be read out of the phone. |
| WhatsApp bridge address and shared secret (optional) | To forward alerts to your own bridge | Address in private storage. Secret encrypted with the same Keystore key. |
| Inverter readings, outage history and settings | Charts, the outage log, predictions and your preferences | Private app storage (SQLite and preferences) |

Your Solarman password is never stored. When you type it, the app turns it into a SHA-256 hash on the phone, because that hash is what Solarman's API expects. Only the hash is kept and sent.

In demo mode nothing is sent anywhere. The inverter and its history are simulated on the phone.

## What leaves the phone

**Solarman.** To sign in and read your inverter, the app sends your app ID, app secret, email and password hash to Solarman's OpenAPI (`globalapi.solarmanpv.com`) over HTTPS. It then asks for your inverter's current readings and recent history. Solarman handles that data under its own privacy policy. Roshni is not affiliated with Solarman.

**Your WhatsApp bridge (optional, off by default).** If you turn on the relay in Settings, the app sends short alert messages, such as "Grid outage. Mains power is down, running on battery. Battery: 82%" and the time, to the bridge address you enter, along with your shared secret. The bridge is a program you run on your own device (see [`tools/whatsapp-bridge`](../tools/whatsapp-bridge)). It does not belong to the developer, and nothing goes to any server of ours.

**Things you choose to do.** If you export or share your outage history, Android's share sheet sends the file to the app you pick. Links in the app, such as Source code, Privacy policy and the Solarman help page, open in your browser.

There is no analytics SDK, no crash reporting, no advertising and no tracking of any kind.

## Android backup

If Android backup is on for your Google account, Android may back up Roshni's settings and reading history to your account and restore them on a new phone. Your Solarman sign-in details are left out of the backup on purpose, so after a restore you sign in again. Backups are handled by Google under your Google account, not by the developer.

## Permissions

- **Notifications, full-screen alarm and alarm sound:** to wake you when the mains go off.
- **Foreground service:** to keep checking the inverter while the app is closed.
- **Exact alarms:** optional, to check right after each inverter upload.
- **Run at startup:** to resume monitoring after the phone restarts.
- **Internet:** to reach Solarman and, if you set one up, your bridge.

Roshni does not ask for location, contacts, camera, microphone, storage or phone permissions.

## Deleting your data

Because the data exists only on your phone, you control it completely:

- **Settings → Data** in the app clears history for the current source.
- **Sign out** in Settings removes the stored Solarman credentials.
- Uninstalling the app, or **Android Settings → Apps → Roshni → Storage → Clear storage**, deletes everything.

The developer holds no copy, so there is nothing to request from us.

## Children

Roshni is a utility for households with solar inverters. It is not directed at children and collects nothing from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new effective date. The full history is in this file's git log.

## Contact

Open an issue at <https://github.com/hafeee44-lab/Roshni/issues>, or use the developer email shown on Roshni's Google Play listing.
