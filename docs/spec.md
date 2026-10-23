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

- **Gas station detection and saved places (agreed 2026-10-23):** the stop where a trip ends is checked against OpenStreetMap gas stations (Overpass, no API key) and saved places. A station match offers "Tap to log a fill-up" with the station filled in. Saved places (Home, Work...) label trips and count visits. Details in the section below. Built in the app (PR #2, 2026-10-23).

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
8. Vehicle type icons (agreed 2026-10-23, built in PR #5). Mock-up: https://claude.ai/artifact/JizQ7giPdsyFFyx18XyMLD
   - Each vehicle gets a type picked on its Edit screen: Car, SUV, Pickup, Van, Motorcycle, Scooter, RV / camper, Boat, Trailer, ATV. New vehicles start as Car.
   - The icon shows in a small tile left of the name on Garage cards, on the vehicle screen, and in the vehicle picker on entry forms.
   - Plain line icons, no per-vehicle color. Boats still log miles for now (engine hours not handled).

## Gas station detection and saved places (agreed and built 2026-10-23)

Ron asked for the app to know which gas station he filled up at, and to save places like Home and Work so it knows when he has been there. Shown in the simulator (Where you stop panel, Places screen behind the pin button on Garage, and the Log fill-up sheet).

- **How it works:** use the spot where the trip ended (the last GPS point when the car's Bluetooth disconnects). The car is off at the pump, so that spot is the station. If it is not a saved place, the app asks for gas stations within about 100 m. On a match it shows "At Murphy USA? Tap to log a fill-up", which opens the fill-up form with the station filled in. Opening Log fill-up by hand does the same lookup from the last stop (same day) or from where the phone is now, picks the closest station within about 0.1 mi as the best match, and lists others within 1.5 mi plus "Not listed or skip" with a typed name.
- **Saved places:** name a spot (Home, Work, Gym...) with a radius (100 to 500 m). Trips are labeled "Home → Work", the Places screen shows last visit and visits in 30 days, and unsaved spots you stop at are listed with a Save button. No station lookup at saved places.
- **Station data:** OpenStreetMap through the Overpass API. Free, no account or API key (important because the repo is public), good coverage of US stations with brand names. Fallback if coverage disappoints: Google Places (better data, but needs a Google Cloud account with a card on file and an API key; personal use stays inside the free monthly allowance).
- **Battery:** no extra cost. GPS already runs during trips; this adds one small web request per trip end that is not at a saved place. Nothing runs between trips.
- **Privacy:** places, trips and stations stay on the phone. Only the stop's coordinates go to the station lookup, with no account.
- **Data:** fill-ups get station name and address (shown in the fuel list, added to CSV export). Trips get start and end spots.
- **Engine left running at the pump (built 2026-10-23; 5 mph scope confirmed by Ron):** opening Log fill-up while a trip is running ends the trip right there, so the stop gets the same station lookup. If the car's Bluetooth is still connected, the next trip starts when the fill-up is saved, or as soon as the phone is moving over 5 mph (if the form was closed without saving). The 5 mph start only applies while the linked Bluetooth is still connected, so walking or riding in another car never starts a trip. If the engine is shut off instead, Bluetooth disconnects and the next connect starts the trip as usual. Shown in the simulator (Drive off button in the Where you stop panel).
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

## Trip route map (agreed 2026-10-23)

Ron asked to open a completed trip and see the exact route on a map, and approved this design with OpenStreetMap maps (no satellite view). Mock-up: `docs/route-map.html` (https://motomech.github.io/garage-log/route-map.html once merged; working copy https://claude.ai/artifact/W89KADaU9av6EhJQV9PWQ2).

- **Today (v0.1):** routes are not kept. TripService reads GPS every 2 to 4 seconds, adds up the distance and discards the points; a saved trip has only date, miles and odometer. Keeping the points is a small change. Trips recorded before it have no map.
- **Screens:** the trip list gets a small route preview per trip; tapping a trip opens a trip map with the route, start and end pins named after saved places or the gas station (same labels as the gas station and saved places proposal), start and end times, minutes, average and top speed, odometer before and after, "Log fill-up here" when the trip ended at a station, "Save this spot as a place" for unsaved end spots, and "Delete route (keep the miles)". Settings: Save trip routes on/off, keep routes Forever / 1 year / 90 days, space used, Delete all routes, clear downloaded map images.
- **Recommended map:** MapLibre Native for Android with OpenFreeMap tiles (OpenStreetMap data). Free, no account, no API key, no usage limit, nothing secret in the public repo. Adds about 8 MB to the APK.
- **Alternatives:** osmdroid (free, no key, but older raster look and in maintenance mode; OpenStreetMap's own tile servers ask apps to keep use light). Google Maps SDK (map views in Android apps cost nothing, but it needs a Google Cloud account with a card and an API key; the key ships inside the APK and restricting it to the app is weak because the signing key is in the public repo). Worth it only if satellite view matters.
- **Storage:** keep a point only after the car has moved, simplify straight stretches at trip end (trimmed to within 6 m of the driven line; about 300 to 500 bytes per mile, roughly 5 MB per 15,000 miles). Routes are stored in their own file(s), not in the main data JSON, and are included in Android backup (25 MB app limit). Map images are cached separately, capped, and can be cleared.
- **Battery:** no extra cost while driving, since GPS already runs for every trip. Opening a map downloads map images for that area (a few hundred KB the first time). Nothing runs between trips.
- **Privacy:** routes stay on the phone. The tile server only sees which map area is requested when a map is opened.
- **How it is built:** TripService keeps every accepted GPS fix of a trip (not the pump-wait fixes) and appends them to `routes/active.txt` about every 0.1 mi. When the trip is saved, the points are trimmed and stored as `routes/<trip id>.txt` with the top speed (fastest speed held over 10 seconds). Trips now also save their start and end times. Routes are removed with their trip, and old ones by the keep setting when the app opens. The trip list draws its previews on the phone; only the full map downloads map images (MapLibre 11, OpenFreeMap "liberty" style). Route settings are reached from a trip's page.
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
- Each trip stores its first and last GPS point. When a trip ends outside a saved place, a background job (WorkManager, needs network) asks Overpass (overpass-api.de) for `amenity=fuel` within 100 m. A match labels the trip and, within 30 minutes of the stop, posts "At <station>? Tap to log a fill-up". No match: Android's geocoder supplies a street address for the label.
- The fill-up form searches stations within 1 mile of the last stop (if under 3 hours old) or of the phone's current location, preselecting one within 150 m.
- Pump stop: opening Log fill-up during a trip ends the trip (no prompt). If the car's Bluetooth is still connected, the trip service stays on in "Stopped for fuel" mode and starts the next trip when the fill-up is saved or GPS speed goes over 5 mph; it gives up after 45 minutes or when Bluetooth disconnects.
- Saved places are matched by distance at display time, so saving a place relabels older trips too.
