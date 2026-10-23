# Solar → WhatsApp bridge

Relays grid-outage alerts from the Solar Grid Android app into a WhatsApp group.

It links to WhatsApp the same way WhatsApp Web does — as a *linked device* on your own
number. This is not an official Meta API and it is against WhatsApp's terms of service.
Volume here is tiny (a handful of messages a day at most), which is the low-risk end of
this, but the number you link can in principle be banned. Link a number you can afford
to lose, not your primary one, if that matters to you.

## What runs where

- **Main phone** — the Solar Grid app. Polls Solarman, decides when the grid is
  down, POSTs the alert text to this bridge.
- **Spare phone** — this bridge, plugged in, on WiFi, running under Termux. Holds the
  WhatsApp session and posts into the group.

They talk over Tailscale, so alerts still go out when you are not home.

## 1. Termux

Install **Termux** and **Termux:Boot** from F-Droid. Not the Play Store version — that
one is abandoned and too old to run this.

```bash
pkg update && pkg upgrade
pkg install nodejs git unzip
termux-setup-storage
```

`git` is not optional — Baileys pulls `libsignal` straight from GitHub.

## 2. Get the files across

Copy this `tools/whatsapp-bridge` folder to the spare phone however is easiest — zip it and
send it to yourself, or clone the repository in Termux. Once it is in Downloads as a zip:

```bash
cd ~
unzip /sdcard/Download/whatsapp-bridge.zip
cd whatsapp-bridge
npm install --omit=optional
```

`--omit=optional` matters. Without it npm pulls in `sharp`, which has no Termux build and
fails. The bridge only sends text, so nothing is lost.

## 3. Config

```bash
cp config.example.json config.json
nano config.json
```

- `sharedSecret` — a long random string. The app must use the same one.
- `pairWithPhoneNumber` — your **main** phone's WhatsApp number, digits only, with country
  code and no `+` (e.g. `923001234567`). Leave it out to pair by QR instead.
- `groupJid` — leave empty for now.

## 4. Pair with WhatsApp

```bash
node index.js
```

After a few seconds it prints a pairing code:

```
==============================
  PAIRING CODE:  EHB9LHTJ
==============================
```

On your **main** phone: WhatsApp → Settings → Linked devices → Link a device →
**Link with phone number instead** → type the code.

(If you left `pairWithPhoneNumber` out, it prints a QR instead — scan it with the same
menu. The pairing code is easier on a small screen.)

## 5. Pick the group

Once connected it prints every group you are in:

```
  120363041234567890@g.us   Family
  120363099876543210@g.us   Neighbours
```

Put the right JID into `config.json` as `groupJid`, then `Ctrl+C` and `node index.js` again.

## 6. Tailscale

Install Tailscale on both phones, sign into the same tailnet. Get the spare phone's
`100.x.y.z` address from the Tailscale app. Then in the app, open
**Settings → WhatsApp relay**:

- turn on **Forward alerts to WhatsApp**
- **Bridge URL**: `http://100.x.y.z:8787`
- **Shared secret**: the same string as `sharedSecret` in `config.json` (it is stored
  encrypted on the phone)

Press **Send test**. That message should land in the group. Until the relay is switched on
the WhatsApp path stays dormant and the app behaves exactly as before.

## 7. Keep it alive

```bash
termux-wake-lock
```

Auto-start after reboot — create `~/.termux/boot/start-bridge.sh`:

```sh
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/whatsapp-bridge && node index.js >> ~/bridge.log 2>&1
```

```bash
chmod +x ~/.termux/boot/start-bridge.sh
```

Then in Android settings, exempt Termux from battery optimisation. Without that Android
will eventually freeze it and alerts stop silently.

## Endpoints

| Method | Path      | Notes                                                  |
|--------|-----------|--------------------------------------------------------|
| GET    | `/health` | connection state, queue depth, hourly budget left      |
| GET    | `/groups` | list group JIDs — needs `x-bridge-key`                 |
| POST   | `/send`   | `{"text": "...", "key": "..."}` — needs `x-bridge-key` |

```bash
curl localhost:8787/health
```

## Safety rails

So a flapping grid can never spam the family group:

- **Dedupe** — the same `key` is delivered once per 24h.
- **Rate limit** — 20 messages/hour max, 5s apart minimum.
- **Offline queue** — up to 20 messages held while disconnected, flushed on reconnect.
  Anything older than 30 minutes is dropped rather than sent late.
- **Auto-reconnect** — every disconnect except an explicit unlink retries after 5s.

If the spare phone gets unlinked from WhatsApp the bridge exits with a clear message.
Delete `auth/` and pair again.

## Failure modes worth knowing

- **The app queues too.** If the bridge is unreachable the Android app holds up to 10
  alerts and retries on its next poll, so a bridge restart does not lose alerts.
- **Shared power.** If the spare phone and router run off the same backup as the inverter,
  a long outage that drains everything takes alerting down with it. The first alert still
  goes out; later ones may not.
