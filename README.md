# Home Kiosk – dashboard app for Lenovo Tab M11

Shows one website fullscreen, hides the back/home/recents bar, dims the screen when nobody
touches it, and wakes up again when the front camera sees movement.

## 1. Build the APK (free, via GitHub, ~5 minutes)

1. Create a free account at github.com and make a **new private repository** (e.g. `home-kiosk`).
2. Upload everything in this folder to it (on the repo page: *Add file → Upload files*, drag the
   whole folder contents in, including the hidden `.github` folder, then *Commit*).
   - Tip: if your file browser hides `.github`, use GitHub Desktop or
     `git init && git add . && git commit -m kiosk && git push`.
3. Open the **Actions** tab. The "Build APK" job runs automatically (start it with *Run workflow* if not).
4. When it's green, open the run and download **HomeKiosk-apk** at the bottom. Unzip it → `HomeKiosk.apk`.

## 2. Install on the tablet

1. Copy `HomeKiosk.apk` to the tablet (USB, Google Drive, e-mail…) and open it.
2. Allow "Install unknown apps" for the app you opened it from when Android asks.
3. Start **Home Kiosk**. Enter your dashboard address, e.g. `http://192.168.1.10:8123/`, tap *Save*.
4. Allow camera access when asked.

## 3. Lock it down

There are two levels.

### A. Quick: screen pinning (no computer needed)
1. Tablet *Settings → Security & privacy → More security settings → App pinning* → **On**
   (wording varies slightly).
2. Open Home Kiosk → it asks "Pin app?" → *Got it*. Back/Home/Recents now do nothing.
3. Also set Home Kiosk as the **Home app**: *Settings → Apps → Default apps → Home app → Home Kiosk*.
   Then pressing Home always returns to the dashboard and it starts by itself after a restart.

To unpin: hold Back + Recents (or swipe up and hold with gesture navigation).

### B. Full kiosk: device owner (needs a computer once)
The nav bar and notifications are removed completely and nothing can escape the app.
Android only allows this when **no Google/other accounts are on the tablet**, so it's easiest
on a freshly reset tablet (skip adding a Google account during setup).

1. On the tablet: *Settings → About tablet* → tap *Build number* 7 times → developer options on.
   *Settings → System → Developer options → USB debugging* → **On**.
2. On the computer install "Android platform tools" (adb), connect the tablet by USB, then run:

   ```
   adb install HomeKiosk.apk
   adb shell dpm set-device-owner com.home.kiosk/.KioskAdminReceiver
   ```
   It should print `Success: Device owner set…`. If it complains about accounts, remove all
   accounts under *Settings → Passwords & accounts* and try again.
3. Open Home Kiosk. From now on it is the home screen, the lock screen is skipped, the camera
   permission is granted automatically and the screen stays on while charging.

To undo: open settings (see below) → **Remove full kiosk lock**. Then the app can be uninstalled.

## Menu and settings inside the app

**Swipe in from the left edge of the screen** (start right at the edge, around the middle
height) to slide out the kiosk menu. It's protected by the PIN if you set one. In the menu:

- Wake on movement (camera): on/off
- Black screen when dimmed: on/off
- Dim after: never / 15 s / 30 s / 1, 2, 5, 10, 30 min
- Dim now, Reload page, All settings…, Exit kiosk, Close

Backup way in: tap the top-left corner 5 times quickly. This also opens the menu.

All settings:

| Setting | Default |
|---|---|
| Website address | – |
| Dim after N seconds without touch/movement | 60 |
| Awake brightness | tablet's own setting |
| Dimmed backlight | 0 % |
| Black screen when dimmed (full dark) | on |
| Motion wake + sensitivity (1–10) | on, 6 |
| Reload page every N minutes | off |
| Accept self-signed HTTPS | off |
| Screen pinning when not device owner | on |
| Settings PIN | none |

"Exit kiosk" stops the lock and opens Android settings; pressing Home brings the dashboard back.

## How it behaves

- When dimmed, the first touch only wakes the screen; it doesn't click anything on the dashboard.
- Someone moving in front of the tablet wakes it, and keeps it awake while they're moving.
- The screen is never actually turned off (the camera can't run with the screen off), it's set to
  minimum backlight with a black overlay, which looks off on the M11's LCD.
- The camera can't see in a pitch-dark room; it needs a bit of light to detect movement.
- If the dashboard can't be reached (Wi-Fi drop, server restart) it retries every 15 seconds.
- The camera privacy dot will show while motion wake is on. That's normal.
- Keep the tablet on its charger.
