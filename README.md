# Garage Log (working name)

An Android app for tracking vehicle mileage, fuel, service, and costs across several vehicles. It replaces MotoLog with an original design and code.

**Status:** version 0.1 of the real Android app is built (Kotlin + Jetpack Compose, in `app/`).

**Install on your phone:** open https://github.com/motomech/garage-log/releases/latest/download/garage-log.apk on the phone, then tap the downloaded file. The first time, Android asks you to allow installs from your browser. New versions install over the old one and keep your data.

## What it does

- Links each vehicle to a Bluetooth device (car radio or hands-free). When the phone connects, a trip starts; when it disconnects, the trip ends and the GPS miles are added to that vehicle's odometer.
- Whichever vehicle is connected becomes the active profile, and new fuel, service, and expense entries default to it (manual override available).
- Fuel log with MPG and cost per mile.
- Service records with reminders by miles or date.
- Parts log for parts bought outside a service (installed or on the shelf).
- Cost totals by category and by vehicle.
- CSV export (import waits until MotoLog's format is reviewed).

## Building

GitHub Actions (`.github/workflows/android.yml`) builds the APK on every push and publishes a release on pushes to `main`. To build locally: `./gradlew assembleRelease` with the Android SDK installed.

## Docs

- `docs/spec.md`: decisions so far, screens, and open questions.
- `docs/index.html`: the clickable simulator (a single web page, sample data only).
- `docs/route-map.html`: mock-up of the trip route map proposal (sample data only).

## Mock-ups

Seven Android screens (sample data) on a Claude design canvas: https://claude.ai/artifact/VGRdfAx4dbkh7tXuK1jZ78 (private to the owner unless shared).

## Simulator

A clickable prototype that runs in any browser, with sample data and simulated Bluetooth trips: https://motomech.github.io/garage-log/ (served by GitHub Pages from the `docs` folder). It is for trying out the design, not the real app.
