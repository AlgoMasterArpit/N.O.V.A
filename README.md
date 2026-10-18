# N.O.V.A

A JARVIS-style voice assistant for Android. Say **"Wake up"** or **"Hey Nova"**, give an order in Hinglish or English, and NOVA does it. Then it asks "Aur kuch, boss?" and goes back to sleep.

## What it can do

| Say | NOVA |
|---|---|
| "subah 7 baje ka alarm laga do" | sets the alarm (works on a locked phone; nothing else does) |
| "Sahil ko call karo" | asks "Sahil ko call karun, boss?" and dials on "haan" |
| "Sahil ko WhatsApp karo ki kal milte hai" | reads the message back, sends it on "haan" |
| "Gemini se pucho / ChatGPT se pucho / anything else" | asks Claude (with web search) and reads the answer aloud |
| "Nova…" while it's talking | stops mid-sentence and listens for a follow-up |
| "aaj meri meetings kya hai" / "kal 5 baje Ravi ke saath meeting add karo" | reads or adds calendar events; speaks up 5 minutes before each meeting |
| "note karo kal doodh lana hai" / "mere notes padho" | private notes on the phone |
| "India Gate le chalo" / "Play Store pe Spotify" / "Gemini kholo" | Maps navigation, Play Store search, opens allowed apps |
| "agle 2 ghante shaant raho" | mic off for 2 hours |
| "nahi" / "bas" / "thank you" | back to sleep |

## Modes

- **Sleep** (default): only a small offline wake-word engine ([Vosk](https://alphacephei.com/vosk/)) listens. It only processes audio when it's loud enough to be a voice, so it's light on battery.
- **Awake**: Google speech recognition (offline when the language pack is downloaded) for your command.
- **Killed**: by hand only, from the notification or the app. No mic, no reminders, nothing, until you tap WAKE NOVA again. Voice can't kill it or wake it.

## Privacy and safety

- Only your questions leave the phone, and only to Claude (`api.anthropic.com`). Alarms, calls, notes, contacts and calendar stay on the device.
- Your Claude API key is typed into the app and kept in app-private storage. It's never in the code or in git. Backup and device transfer are disabled.
- Fixed allow-list of actions. Calls and WhatsApp messages always need a spoken "haan".
- The WhatsApp accessibility service only sees WhatsApp, and only taps Send within 15 seconds of a confirm.
- Locked phone: alarms only.

## Build

Needs JDK 17 and the Android SDK (platform 34).

```
gradlew assembleRelease
```

The offline wake-word model (55 MB) isn't in the repo. The first build downloads it automatically (`downloadVoskModel`, SHA-256 checked) into `app/src/main/assets/model-en-in`, so that one build needs internet. Nothing to install by hand.

Release signing reads `keystore.properties` (git-ignored):

```
storeFile=C:/path/to/nova-release.jks
password=...
```

## Phone setup

1. Install the APK (`adb install app-release.apk` avoids Android's "restricted settings" block on accessibility).
2. In the app: give permissions, allow display over other apps, paste a Claude API key, turn on the WhatsApp sender.
3. Settings → Apps → N.O.V.A → Battery → **Unrestricted**.
4. Optional: download the offline **English (India)** speech pack in Google settings.
5. Tap **WAKE NOVA**.
