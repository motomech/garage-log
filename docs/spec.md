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

## Proposal: trip route map (2026-10-23, not agreed yet)

Ron asked to open a completed trip and see the exact route on a map. Mock-up: `docs/route-map.html` (https://motomech.github.io/garage-log/route-map.html once merged; working copy https://claude.ai/artifact/W89KADaU9av6EhJQV9PWQ2).

- **Today (v0.1):** routes are not kept. TripService reads GPS every 2 to 4 seconds, adds up the distance and discards the points; a saved trip has only date, miles and odometer. Keeping the points is a small change. Trips recorded before it have no map.
- **Screens:** the trip list gets a small route preview per trip; tapping a trip opens a trip map with the route, start and end pins named after saved places or the gas station (same labels as the gas station and saved places proposal), start and end times, minutes, average and top speed, odometer before and after, "Log fill-up here" when the trip ended at a station, "Save this spot as a place" for unsaved end spots, and "Delete route (keep the miles)". Settings: Save trip routes on/off, keep routes Forever / 1 year / 90 days, space used, Delete all routes, clear downloaded map images.
- **Recommended map:** MapLibre Native for Android with OpenFreeMap tiles (OpenStreetMap data). Free, no account, no API key, no usage limit, nothing secret in the public repo. Adds about 8 MB to the APK.
- **Alternatives:** osmdroid (free, no key, but older raster look and in maintenance mode; OpenStreetMap's own tile servers ask apps to keep use light). Google Maps SDK (map views in Android apps cost nothing, but it needs a Google Cloud account with a card and an API key; the key ships inside the APK and restricting it to the app is weak because the signing key is in the public repo). Worth it only if satellite view matters.
- **Storage:** keep a point only after the car has moved, simplify straight stretches at trip end (about 10 to 20 points per mile, roughly 200 bytes per mile, about 3 MB per 15,000 miles). Routes are stored in their own file(s), not in the main data JSON, and are included in Android backup (25 MB app limit). Map images are cached separately, capped, and can be cleared.
- **Battery:** no extra cost while driving, since GPS already runs for every trip. Opening a map downloads map images for that area (a few hundred KB the first time). Nothing runs between trips.
- **Privacy:** routes stay on the phone. The tile server only sees which map area is requested when a map is opened.
- **Effort:** about two days of app work. Shares the trip end point with the gas station feature.
- **Later options:** color the route by speed, GPX export per trip, monthly map of all trips for a vehicle.

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
