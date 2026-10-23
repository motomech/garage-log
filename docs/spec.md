# Garage Log: spec

Working name. Process: talk ideas through, review mock-ups, refine until agreed, and only then build.

**Status (2026-10-23):** first real Android build (version 0.1) made from the simulator's scope.

## Decisions so far

- **Platform:** native Android app (needs background Bluetooth and location handling). Data stored on the phone.
- **Vehicles:** multiple vehicles.
- **Vehicle detection:** each vehicle is linked to a Bluetooth device it connects to (car radio or hands-free). Connect starts a trip, disconnect ends it. Trip distance is measured by phone GPS and added to that vehicle's odometer.
- **Active vehicle auto-switch:** whichever linked vehicle is connected becomes the active profile. All new entries (fuel, service, expenses) default to it. The user can override manually when entering something for a vehicle they are not in.
- **No-Bluetooth vehicles:** manual mileage entry. A small plug-in Bluetooth adapter that powers on with the ignition is an option.
- **Odometer drift:** a "Correct odometer" action to set the true value.
- **OBD2:** not required. Possible optional extra later.
- **Repo:** `motomech/garage-log`, public (needed for free GitHub Pages). The simulator is published from `docs/index.html` (2026-10-23).
- **MotoLog sections:** Revenues and Licenses are not carried over for now (deferred, 2026-10-23).
- **Tech stack (2026-10-23):** native Kotlin with Jetpack Compose, min Android 8.0 (API 26), target Android 15 (API 35). Data is one JSON file in the app's private storage, included in Android's own backup.
- **Install route (2026-10-23):** GitHub Actions builds a signed APK on every push. Each push to `main` publishes a GitHub release; the newest APK is always at https://github.com/motomech/garage-log/releases/latest/download/garage-log.apk. Ron downloads it on the phone and sideloads it (allow "install unknown apps" once). No Play Store.
- **Signing key:** a fixed key is kept in the repo (`keystore/`) so every new APK installs as an update and keeps the data. Fine for a personal sideloaded app; a Play Store release would need a private key.
- **Version 0.1 scope:** vehicles (add, edit, delete), Bluetooth-linked trips measured by GPS, active vehicle with manual override on every entry form, Fuel (MPG, cost per mile), Service with reminders by miles or months (notifications), Parts tab, Costs (by category, by vehicle, per mile), Correct odometer, manual mileage readings, CSV export. Entries can be edited or deleted by tapping them. CSV import waits for MotoLog's format.

## Features wanted

1. Mileage and trips
2. Fuel log (fill-ups, MPG, cost per mile)
3. Service records with reminders (by miles or date)
4. Cost totals (by category, by vehicle, per mile)
5. CSV import and export (column mapping, skip duplicates)
6. Parts log: parts bought outside a service (part, part number, store, qty, cost). Marked installed (with odometer) or "on the shelf" for spares. Has its own tab in the bottom bar (Garage, Fuel, Service, Parts, Costs), counts as its own category in Costs, and is included in CSV export (added 2026-10-23; own tab per Ron).
7. Vehicle details and documents (agreed 2026-10-23, built in PR #4). Mock-up: https://claude.ai/artifact/WfBXxa5noe9FpdRXyybduu
   - Details card on the vehicle screen: plate and state, VIN (tap to copy), color, engine, tire size, oil, insurance company and policy number, purchase date and price, notes, plus fields the user names (like a gate code). All optional; only filled ones show.
   - Documents per vehicle: take a photo (more photos add pages, e.g. front and back), pick from photos, or attach a PDF. Type (Registration, Insurance card, Title, Smog / inspection, Warranty, Receipt, Other), optional name, optional expiry date. Tiles show an expiry badge (warns 30 days ahead, red once expired). Viewer shows every page with tap-to-zoom (pinch), plus Share, Edit (add or remove pages, which covers "Replace") and Delete.
   - **Expiry reminders:** a notification 30 days before a document expires and again once it has expired, on the same reminder channel as service ("Service and document reminders"). Can be turned off per document.
   - Storage: photos are turned upright and shrunk to 2000 px JPEG; PDFs are copied as-is. Files live in the app's private `docs` folder and are left out of Android's own backup (its 25 MB limit would make it skip everything); the Google Drive backup below carries them.
   - **Automatic backup to Google Drive (decided 2026-10-23):** Ron wants automatic, not manual. One zip (garage.json plus all document files). The user picks the file once with Android's own "Save to" picker (Google Drive, a folder); the app keeps permission to that file and saves over it every night around 3 AM when anything changed (battery not low). "Back up now", "Change file", an on/off switch, and "Restore from a backup" (replaces everything, after a confirm). A failed nightly backup posts one notification. No Google Cloud project or sign-in. If Drive proves unreliable with background saves on Ron's phone, switch to the Google Drive API with Google sign-in (Ron would create a free Google Cloud project).
   - Not in CSV export (the backup covers them).

## Interactive simulator

Clickable prototype with sample data and simulated Bluetooth trips (review only, not the real app). Public copy: https://motomech.github.io/garage-log/ (GitHub Pages, from docs/index.html). Working copy: https://claude.ai/artifact/M3nJafop3ksKF29B9PwC9S

## Mock-up screens (sample data)

1. Garage (vehicle list)
2. Vehicle (odometer, Bluetooth link, trips)
3. Service
4. Fuel
5. Costs
6. Import and export
7. Link a vehicle (pick the Bluetooth device)

## Open questions

- MotoLog's CSV export format has not been reviewed yet (needed for import mapping).
- Active-vehicle indicator and manual override on the Fuel, Service, and Costs screens are not in the mock-ups yet.

- Background trip tracking reliability on Ron's phone (battery optimization). The app has a setup card that asks for each needed permission, including "Battery: unrestricted". Test on real drives.
- Short Bluetooth drop-outs currently end a trip; a grace period may be needed if this happens in practice.

## Technical notes

- Bluetooth connect and disconnect events need the Bluetooth connect permission on Android 12 and later.
- Trip tracking needs location access, including in the background, and a foreground service.
- How a trip works in the app: the system's Bluetooth connect/disconnect broadcast wakes the app; if the device is linked to a vehicle, a foreground service ("Trip in progress" notification, with an End trip button) measures GPS distance (fused location, points with accuracy worse than 30 m or jumps faster than ~155 mph ignored). On disconnect the miles go onto that vehicle's odometer and into its trip list. If Android refuses to start tracking from the background, a "Tap to track this trip" notification appears instead.
- Reminders are checked after every trip, on app open, and once a day in the background.
