# Garage Log: spec (draft, pre-build)

Working name. Process: talk ideas through, review mock-ups, refine until agreed, and only then build.

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

## Open questions

- MotoLog's CSV export format has not been reviewed yet (needed for import mapping).
- Active-vehicle indicator and manual override on the Fuel, Service, and Costs screens are not in the mock-ups yet.

- Android tech stack: native Kotlin or a cross-platform framework.
- Background trip tracking reliability on the user's phone (battery optimization). Test early.

## Technical notes

- Bluetooth connect and disconnect events need the Bluetooth connect permission on Android 12 and later.
- Trip tracking needs location access, including in the background, and a foreground service.
