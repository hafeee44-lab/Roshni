# Roshni privacy policy

Effective 24 October 2026. Applies to the Roshni Android app (`io.github.hafeee44lab.solargrid`), on Google Play and on GitHub. Roshni is developed by Muhammad Hafi and published by Havlen, referred to below as "the developer".

Short version: Roshni has no servers, accounts, ads or analytics. The developer never receives your data. The app reads your own inverter, either directly from its Wi-Fi logger on your home network or through the cloud service of your inverter's maker (Solarman, Growatt, Huawei FusionSolar, SolisCloud or DESS Monitor), whichever you choose. If you turn on the optional WhatsApp relay, it also talks to a bridge you run yourself. Nothing else.

## What the app stores, and where

Everything stays on your phone, in the app's private storage, which other apps cannot read.

| What | Why | How it is kept |
|---|---|---|
| Solarman app ID, account email, inverter serial number | To sign in to Solarman and find your inverter | Private app storage |
| Solarman app secret and password hash | To sign in to Solarman | Encrypted with an AES-256 key held in the Android Keystore. The key cannot be read out of the phone. |
| Sign-in details for Growatt, Huawei FusionSolar, SolisCloud or DESS Monitor (only the one you pick) | To read your inverter from that maker's cloud | The whole record is encrypted with the same Keystore key. Growatt and DESS keep only the hash their API expects, not your password. Huawei's API user password and Solis's KeySecret are kept, encrypted, because those APIs need them. |
| Wi-Fi logger address, logger serial number and inverter model (local network mode) | To read the inverter directly on your home network | Private app storage |
| WhatsApp bridge address and shared secret (optional) | To forward alerts to your own bridge | Address in private storage. Secret encrypted with the same Keystore key. |
| Inverter readings, outage history and settings | Charts, the outage log, predictions and your preferences | Private app storage (SQLite and preferences) |

Your Solarman, Growatt and DESS passwords are never stored. When you type one, the app turns it into the hash that service's API expects (SHA-256 for Solarman, Growatt's MD5 variant, SHA-1 for DESS) on the phone. Only the hash is kept and sent.

In demo mode nothing is sent anywhere. The inverter and its history are simulated on the phone.

## What leaves the phone

**Local network mode.** If you choose it, the app talks only to your inverter's Wi-Fi logger at the address you give, on your home network (TCP port 8899). It only reads values and never changes inverter settings. The "Find logger" button sends a discovery message to devices on your Wi-Fi. None of this leaves your home network.

**Solarman.** To sign in and read your inverter, the app sends your app ID, app secret, email and password hash to Solarman's OpenAPI (`globalapi.solarmanpv.com`) over HTTPS. It then asks for your inverter's current readings and recent history. Solarman handles that data under its own privacy policy. Roshni is not affiliated with Solarman.

**Growatt, Huawei FusionSolar, SolisCloud or DESS Monitor.** If you pick one of these instead, the app sends your sign-in details for that service, as described above, to that maker's official or app API over HTTPS (for example `openapi.growatt.com`, your regional FusionSolar server, `www.soliscloud.com` or `api.dessmonitor.com`), then asks for your inverter's readings. Only the service you picked is contacted. Each maker handles that data under its own privacy policy. Roshni is not affiliated with any of them.

**Your WhatsApp bridge (optional, off by default).** If you turn on the relay in Settings, the app sends short alert messages, such as "Grid outage. Mains power is down, running on battery. Battery: 82%" and the time, to the bridge address you enter, along with your shared secret. The bridge is a program you run on your own device (see [`tools/whatsapp-bridge`](../tools/whatsapp-bridge)). It does not belong to the developer, and nothing goes to any server of ours.

**Things you choose to do.** If you export or share your outage history, Android's share sheet sends the file to the app you pick. Links in the app, such as Source code, Privacy policy and the Solarman help page, open in your browser.

There is no analytics SDK, no crash reporting, no advertising and no tracking of any kind.

## Android backup

If Android backup is on for your Google account, Android may back up Roshni's settings and reading history to your account and restore them on a new phone. Your cloud sign-in details are left out of the backup on purpose, so after a restore you sign in again. Backups are handled by Google under your Google account, not by the developer.

## Permissions

- **Notifications, full-screen alarm and alarm sound:** to wake you when the mains go off.
- **Foreground service:** to keep checking the inverter while the app is closed.
- **Exact alarms:** optional, to check right after each inverter upload.
- **Run at startup:** to resume monitoring after the phone restarts.
- **Internet:** to reach your inverter's logger on your home network or your chosen cloud service and, if you set one up, your bridge.

Roshni does not ask for location, contacts, camera, microphone, storage or phone permissions.

## Deleting your data

Because the data exists only on your phone, you control it completely:

- **Settings → Data** in the app clears history for the current source.
- **Sign out** in Settings removes the stored sign-in details for your cloud service.
- Uninstalling the app, or **Android Settings → Apps → Roshni → Storage → Clear storage**, deletes everything.

The developer holds no copy, so there is nothing to request from us.

## Children

Roshni is a utility for households with solar inverters. It is not directed at children and collects nothing from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new effective date. The full history is in this file's git log.

## Contact

Open an issue at <https://github.com/hafeee44-lab/Roshni/issues>, or use the developer email shown on Roshni's Google Play listing.
