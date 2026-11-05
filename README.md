<img src="assets/icon.svg" width="64" height="64" alt="">

# android-copyota

Copy on your Android phone, paste on your Mac, on any network. No app to open and no cable.

```
Android app  --HTTPS POST-->  relay  --WebSocket-->  Mac agent  -->  clipboard
```

Text and images are encrypted with AES-256-GCM on the phone and decrypted on the Mac. The relay is a Cloudflare Worker or [your own server](#self-hosting). It only passes on ciphertext and stores nothing.

Android blocks background apps from reading the clipboard, so the app is an accessibility service that watches Android's own "copied" preview overlay. Short text comes straight from the preview. Text of 500+ characters, hidden text like passwords from a password manager, and images need a real read, which briefly takes window focus. Images are sent in chunks of about 600 KB, so they arrive at full quality, and the Mac puts them back together.

Screenshots never reach Android's clipboard, so the app watches for new ones in the photo library instead. Tap **Send screenshots** in the app and allow access to all photos to turn this on. Every new screenshot then replaces your Mac's clipboard, including any of sensitive screens. To stop, remove the Photos permission in Settings > Apps > Copyota.

## Setup

Everyone runs their own relay on their own free Cloudflare account, or [on their own server](#self-hosting), so installs never see each other. Needs Android 13+, and macOS 13+ on the Mac, plus Node for the Cloudflare relay.

1. On the Mac, download the latest release and run setup. Keep the folder, the Mac agent runs from it.

   ```
   curl -L https://github.com/Errichamonda/copyota/releases/latest/download/copyota-mac.tar.gz | tar xz
   cd copyota && ./copyota setup
   ```

   Use curl and not your browser, because the agent isn't notarized and macOS blocks it after a browser download. From a git clone, `./copyota setup` builds the agent itself, which needs Xcode's command line tools. Setup walks you through signing in to Cloudflare with wrangler, deploys the relay, generates your private key, starts the Mac agent at login and shows a QR code.
2. Install the Copyota app on the phone. Download `copyota.apk` from [Releases](https://github.com/Errichamonda/copyota/releases), or build it with `./copyota apk`, which needs the Android SDK and a JDK and writes `build/copyota.apk`. The same APK works for everyone, it holds no key.
3. Scan the QR code with the phone's camera, open the link, tap **Open Copyota** and confirm. If you can't scan it, tap **Enter pairing link** in the app and paste the link printed under the code. Run `./copyota pair` to show both again.
4. On the phone, open Settings > Accessibility > Copyota and turn it on. If Android says the setting is restricted, open Settings > Apps > Copyota, tap the ⋮ menu, choose **Allow restricted settings**, and try again.

`./copyota status` shows whether the Mac agent is connected and the relay is up. `./copyota uninstall` stops the Mac agent. Its log is `/tmp/copyota.log`, and your settings are in `~/.config/copyota/env`.

## Self-hosting

To skip Cloudflare, run the relay on any server with a domain name pointing at it and ports 80 and 443 open. It needs Docker, and [Caddy](https://caddyserver.com) gets the HTTPS certificate for you.

```
git clone https://github.com/Errichamonda/copyota.git
cd copyota/worker && DOMAIN=relay.example.com docker compose up -d
```

Then on the Mac, run `./copyota setup https://relay.example.com` instead of plain `./copyota setup`, and carry on from step 2. It skips Cloudflare and Node entirely. If you already paired with another relay, scan the new code to move the phone over. To go back to Cloudflare, delete the `URL=` line from `~/.config/copyota/env` and run `./copyota setup`.

Without Docker, `deno run --allow-net worker/selfhost.js` serves plain HTTP on port 8080 for your own HTTPS proxy, such as nginx or `tailscale serve --bg 8080`. Keep port 8080 itself closed to the internet.

The relay needs a certificate the phone trusts, so a self-signed one won't work, and the phone refuses plain HTTP because anyone on the network could tamper with the pairing page and steal your key. A relay on a port other than 443 needs Copyota newer than v0.2.1. Anyone who finds a self-hosted relay can send messages through it, but they can't read or forge your clips without your room ID and key.

## Limits

- Text and images, and phone to Mac only. Images over 10 MB are skipped.
- Depends on Android's clipboard overlay. Phones that replace or disable it won't detect copies.
- Copies made while the Mac is asleep or either side is offline are dropped.
- Long or hidden copies and images show "Copyota pasted from your clipboard" and may close the keyboard. Turn off **Show clipboard access** in Settings > Privacy to hide the message, though that hides it for every app.
- Anyone with your QR code, pairing link or `~/.config/copyota/env` can read and send clips. To replace the key, delete `~/.config/copyota`, run `./copyota setup`, and scan the new code.
