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

- **Gas station detection and saved places (agreed 2026-10-23):** the stop where a trip ends is checked against OpenStreetMap gas stations (Overpass, no API key) and saved places. A station match offers "Tap to log a fill-up" with the station filled in. Saved places (Home, Work...) label trips and count visits. Details in the section below. Not built yet.

## Features wanted

1. Mileage and trips
2. Fuel log (fill-ups, MPG, cost per mile)
3. Service records with reminders (by miles or date)
4. Cost totals (by category, by vehicle, per mile)
5. CSV import and export (column mapping, skip duplicates)
6. Parts log: parts bought outside a service (part, part number, store, qty, cost). Marked installed (with odometer) or "on the shelf" for spares. Has its own tab in the bottom bar (Garage, Fuel, Service, Parts, Costs), counts as its own category in Costs, and is included in CSV export (added 2026-10-23; own tab per Ron).

## Gas station detection and saved places (agreed 2026-10-23)

Ron asked for the app to know which gas station he filled up at, and to save places like Home and Work so it knows when he has been there. Shown in the simulator (Where you stop panel, Places screen behind the pin button on Garage, and the Log fill-up sheet).

- **How it works:** use the spot where the trip ended (the last GPS point when the car's Bluetooth disconnects). The car is off at the pump, so that spot is the station. If it is not a saved place, the app asks for gas stations within about 100 m. On a match it shows "At Murphy USA? Tap to log a fill-up", which opens the fill-up form with the station filled in. Opening Log fill-up by hand does the same lookup from the last stop (same day) or from where the phone is now, picks the closest station within about 0.1 mi as the best match, and lists others within 1.5 mi plus "Not listed or skip" with a typed name.
- **Saved places:** name a spot (Home, Work, Gym...) with a radius (100 to 500 m). Trips are labeled "Home → Work", the Places screen shows last visit and visits in 30 days, and unsaved spots you stop at are listed with a Save button. No station lookup at saved places.
- **Station data:** OpenStreetMap through the Overpass API. Free, no account or API key (important because the repo is public), good coverage of US stations with brand names. Fallback if coverage disappoints: Google Places (better data, but needs a Google Cloud account with a card on file and an API key; personal use stays inside the free monthly allowance).
- **Battery:** no extra cost. GPS already runs during trips; this adds one small web request per trip end that is not at a saved place. Nothing runs between trips.
- **Privacy:** places, trips and stations stay on the phone. Only the stop's coordinates go to the station lookup, with no account.
- **Data:** fill-ups get station name and address (shown in the fuel list, added to CSV export). Trips get start and end spots.
- **Engine left running at the pump (proposed 2026-10-23, waiting on Ron):** opening Log fill-up while a trip is running ends the trip right there, so the stop gets the same station lookup. If the car's Bluetooth is still connected, the next trip starts when the fill-up is saved, or as soon as the phone is moving over 5 mph (if the form was closed without saving). The 5 mph start only applies while the linked Bluetooth is still connected, so walking or riding in another car never starts a trip. If the engine is shut off instead, Bluetooth disconnects and the next connect starts the trip as usual. Shown in the simulator (Drive off button in the Where you stop panel).
- **Effort:** moderate, about two to three days of app work. No server needed.

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
