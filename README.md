<img src="assets/icon.svg" width="64" height="64" alt="">

# android-copyota

Copy on your Android phone, paste on your Mac, on any network. No app to open and no cable.

```
Android app  --HTTPS POST-->  Cloudflare Worker  --WebSocket-->  Mac agent  -->  clipboard
```

Text is encrypted with AES-256-GCM on the phone and decrypted on the Mac. The Worker only relays ciphertext and stores nothing.

Android blocks background apps from reading the clipboard, so the app is an accessibility service that watches Android's own "copied" preview overlay. Short text comes straight from the preview. Text of 500+ characters and hidden text, like passwords from a password manager, need a real read, which briefly takes window focus.

## Setup

Needs Android 13+, a free Cloudflare account, and the Android SDK, Xcode tools and Node on the Mac.

1. Run `npx wrangler@4 login`.
2. Run `./copyota setup`. It generates your key, deploys the Worker, builds both sides and starts the Mac agent at login.
3. Install `build/copyota.apk` on the phone, by USB file transfer or `adb install`. It contains your key, so keep it private.
4. On the phone, open Settings > Accessibility > Copyota and turn it on. If Android says the setting is restricted, open Settings > Apps > Copyota, tap the ⋮ menu, choose **Allow restricted settings**, and try again.

`./copyota uninstall` stops the Mac agent. Its log is `/tmp/copyota.log`.

## Limits

- Text only, and phone to Mac only. Images are skipped.
- Depends on Android's clipboard overlay. Phones that replace or disable it won't detect copies.
- Copies made while the Mac is asleep or either side is offline are dropped.
- Long or hidden copies show "Copyota pasted from your clipboard" and may close the keyboard. Turn off **Show clipboard access** in Settings > Privacy to hide the message, though that hides it for every app.
- Anyone with your APK or `.env` can read and send clips. To replace the key, delete `.env`, run `./copyota setup`, and reinstall the APK.
