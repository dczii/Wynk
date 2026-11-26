# Wynk

A personal Android app that draws on Meta Ray-Ban Display glasses using the Meta Wearables
Device Access Toolkit (`mwdat` 1.1.0). It is a minimal hello-world based on Meta's
[DisplayAccess sample](https://github.com/meta/meta-wearables-dat-android/tree/main/samples/DisplayAccess).

On the glasses it shows a card with the time and a tap counter, plus two buttons. **Tap me**
bumps the counter on the phone and re-sends the screen.

## One-time setup

1. **Update.** Update the Meta AI app and the glasses firmware. Version minimums are on
   [Version Dependencies](https://wearables.developer.meta.com/docs/develop/dat/version-dependencies/).
2. **Enable Developer Mode.** In the Meta AI app, go to **Settings > App Info** and tap the
   **App version** number five times, then turn on **Developer Mode**.
3. **Install the toolkit on the glasses.** Put the glasses on. Then, on the same Developer Mode
   screen, tap **install** to put the Device Access Toolkit on them. Keep the Meta AI app open
   for about 10 seconds, and charge the glasses above 10% first.
4. **Turn on USB debugging.** On your Android phone, enable Developer options and USB debugging.

No Wearables Developer Center account is needed for personal use: Developer Mode skips app
attestation, so `APPLICATION_ID` and `CLIENT_TOKEN` stay `0`.

## Build and install

With the phone plugged in:

```bash
cd ~/Projects/glasses-app && ./gradlew installDebug
```

Or open the folder in Android Studio and press Run.

## Grok key

Wynk chats with Grok through the xAI API. Add your key to `local.properties` (gitignored):

```properties
xai_api_key=xai-...
```

The key is built into the APK, so don't share the APK.

## Use it

1. **Grant Bluetooth.** Open **Wynk** and allow the Bluetooth permission.
2. **Connect.** Tap **Connect to glasses**. The Meta AI app opens; approve, and it returns
   here. The app then appears under Meta AI **App connections > Developer mode apps**.
3. **Show it.** Tap **Show on glasses**. Swipe on the glasses touchpad or Neural Band to pick
   a button, and tap to press it.
4. **End it.** A two-finger tap on the temple (Back), or **Stop session**, ends the session.

**If it doesn't connect:**
- *Meta AI says "Error opening link"*: turn on Developer Mode in Meta AI, then force-close Meta AI.
- *"Registration process failed"*: check that Bluetooth is on and that Meta AI shows the glasses as connected.
- *"No eligible device available"*: the glasses link is still coming up. Wait a few seconds and try
  again; the `Wynk` logcat tag prints each device's link state.

**Without the glasses:** turn on **Use simulated glasses**, then tap **Show on glasses**. The
600×600 display renders inside the phone app.

## Where to change things

- `app/src/main/java/com/personal/wynk/GlassesViewModel.kt` holds the session flow:
  `createSession`, then `addDisplay`, then `sendContent`. Edit `showHello()` to change what
  appears on the glasses.
- The display components are `flexBox`, `text`, `image`, `button`/`buttonGroup`, `icon` and
  `video`. Each `sendContent` replaces the whole screen, and the glasses keep no state, so the
  phone is the source of truth.
- The display dims after 20 s and sleeps at 25 s. The session survives sleep.

## Docs

- Display on Android: https://wearables.developer.meta.com/docs/develop/dat/display-android/
- All docs, LLM-friendly: https://wearables.developer.meta.com/llms.txt
- Known issues: https://wearables.developer.meta.com/docs/develop/dat/knownissues/

Use is governed by the Meta Wearables Developer Terms and Acceptable Use Policy
(see `META-SAMPLE-LICENSE`).
