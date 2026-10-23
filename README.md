# Garage Log (working name)

An Android app for tracking vehicle mileage, fuel, service, and costs across several vehicles. It replaces MotoLog with an original design and code.

**Status:** mock-ups and a clickable simulator done, under review. The real Android app is not built yet.

## What it does

- Links each vehicle to a Bluetooth device (car radio or hands-free). When the phone connects, a trip starts; when it disconnects, the trip ends and the GPS miles are added to that vehicle's odometer.
- Whichever vehicle is connected becomes the active profile, and new fuel, service, and expense entries default to it (manual override available).
- Fuel log with MPG and cost per mile.
- Service records with reminders by miles or date.
- Parts log for parts bought outside a service (installed or on the shelf).
- Cost totals by category and by vehicle.
- CSV import and export.

## Docs

- `docs/spec.md`: decisions so far, screens, and open questions.
- `docs/index.html`: the clickable simulator (a single web page, sample data only).

## Mock-ups

Seven Android screens (sample data) on a Claude design canvas: https://claude.ai/artifact/VGRdfAx4dbkh7tXuK1jZ78 (private to the owner unless shared).

## Simulator

A clickable prototype that runs in any browser, with sample data and simulated Bluetooth trips: https://motomech.github.io/garage-log/ (served by GitHub Pages from the `docs` folder). It is for trying out the design, not the real app.
