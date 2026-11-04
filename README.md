<img src="assets/icon.svg" width="64" height="64" alt="">

# android-copyota

Copy on your Android phone, paste on your Mac, on any network. No app to open and no cable.

```
Android app  --HTTPS POST-->  Cloudflare Worker  --WebSocket-->  Mac agent  -->  clipboard
```

Text and images are encrypted with AES-256-GCM on the phone and decrypted on the Mac. The Worker only relays ciphertext and stores nothing.

Android blocks background apps from reading the clipboard, so the app is an accessibility service that watches Android's own "copied" preview overlay. Short text comes straight from the preview. Text of 500+ characters, hidden text like passwords from a password manager, and images need a real read, which briefly takes window focus. Images are sent in chunks of about 600 KB, so they arrive at full quality, and the Mac puts them back together.

## Setup

Everyone runs their own relay on their own free Cloudflare account, so installs never see each other. Needs Android 13+, and Node on the Mac to deploy the relay. No Java, Xcode or Android SDK.

1. On the Mac, download the agent. Use `curl` rather than a browser, because macOS blocks unsigned downloads from browsers.
   ```sh
   mkdir -p ~/.local/bin
   curl -fL https://github.com/Errichamonda/copyota/releases/latest/download/copyota -o ~/.local/bin/copyota
   chmod +x ~/.local/bin/copyota
   ```
2. Run `~/.local/bin/copyota setup`. It walks you through signing in to Cloudflare with wrangler, deploys the relay, generates your private key, starts the Mac agent at login and shows a QR code. The agent runs from wherever the file is, so run `setup` again if you move it.
3. Install [`copyota.apk`](https://github.com/Errichamonda/copyota/releases/latest/download/copyota.apk) on the phone. The same APK works for everyone, it holds no key.
4. Scan the QR code with the phone's camera, open the link, tap **Open Copyota** and confirm. Run `~/.local/bin/copyota pair` to show the code again.
5. On the phone, open Settings > Accessibility > Copyota and turn it on. If Android says the setting is restricted, open Settings > Apps > Copyota, tap the ⋮ menu, choose **Allow restricted settings**, and try again.

`~/.local/bin/copyota uninstall` stops the Mac agent. Its log is `/tmp/copyota.log`, and your settings are in `~/.config/copyota/env`.

## Building

GitHub Actions builds both files, and pushing a `v*` tag publishes them as a release. To build locally, run `cargo build --release` in `mac/` (needs Rust), or `android/build.sh` for the APK (needs a JDK and the Android SDK).

## Limits

- Text and images, and phone to Mac only. Images over 10 MB are skipped.
- Depends on Android's clipboard overlay. Phones that replace or disable it won't detect copies.
- Copies made while the Mac is asleep or either side is offline are dropped.
- Long or hidden copies and images show "Copyota pasted from your clipboard" and may close the keyboard. Turn off **Show clipboard access** in Settings > Privacy to hide the message, though that hides it for every app.
- Anyone with your QR code or `~/.config/copyota/env` can read and send clips. To replace the key, delete `~/.config/copyota`, run `copyota setup`, and scan the new code.
