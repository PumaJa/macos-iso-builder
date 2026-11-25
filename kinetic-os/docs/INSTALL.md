# Installation, configuration & Bluetooth guide

## 1. Android app
* Requirements: Android 8.0+ (API 26), BLE for wireless VESC, USB-OTG for wired. No root, no Google Play services needed.
* Install `app-release.apk` (or `app-debug.apk`): allow "install unknown apps" for your file manager, open the APK.
* Build yourself: JDK 17+, Android SDK 35 → `cd android && ./gradlew assembleRelease`. Output:
  `android/app/build/outputs/apk/release/app-release.apk`. The release variant is signed with the debug key for testing –
  create your own keystore before distributing.
* First start: grant location (GPS + BLE scan on Android ≤ 11), Bluetooth and notification permissions.
  For background recording on Samsung: Settings → Battery → Background usage limits → **Never sleeping apps** → Kinetic OS,
  and tap *Settings → Battery optimisation exception* in the app.

## 2. Bluetooth connection guide
1. Your VESC needs a BLE module speaking the VESC UART-over-BLE protocol (Nordic UART service): **VESC Express**, NRF51/52
   VESC modules (e.g. the ones sold with Trampa/Flipsky/Makerbase controllers), or any VESC-Tool-compatible dongle.
   In VESC Tool check *App Settings → General → (BLE) / UART* is enabled at a baud rate matching the module (usually 115200).
2. In Kinetic OS: **Connect → SCAN**. Devices advertising the VESC/UART service are highlighted. Tap **CONNECT**.
3. If the module has a PIN (VESC Express "BLE pin"), pair it in Android Bluetooth settings first.
4. After connecting the app reads firmware/hardware, scans the CAN bus, probes IMU/BMS/GNSS and **backs up the controller's
   runtime limits**. The app remembers the device and auto-reconnects (Connect → Auto-connect).
5. Troubleshooting: Bluetooth on? Location permission (Android ≤ 11)? Only one app can hold the BLE link – close VESC Tool.
   Many timeouts → lower *Settings → Telemetry poll rate*.
* **USB:** OTG cable → Connect → CONNECT USB VESC → allow → tap again. Supported: VESC native USB (STM32 CDC), CDC-ACM bridges.
* **WiFi:** VESC Express in AP/STA mode → Connect → host `192.168.4.1:65102` (or the address your Express shows).

## 3. Vehicle setup (Garage)
Garage → + ADD VEHICLE: name, type, photo (shown on the dash in place of a stock bike image), **pole pairs, gear ratio
(motor turns per wheel turn), wheel diameter** (required for correct speed – check "km/h per 1000 ERPM" against GPS),
battery Ah/V/cells (range estimate, voltage-based SoC), limits (informational). Multiple vehicles are supported.

## 4. Ride modes
Configure the **maximum** performance in VESC Tool as usual (motor/battery current, power, ERPM). Kinetic modes are fractions of
that stored configuration and are applied in RAM only. ECO/SPORT/RACE/CRAWL are built in; create up to 200 custom modes.
Power increases are accepted only at standstill. *Restore stock* or a VESC power-cycle returns to the stored configuration.
Optional on-vehicle derating: upload `lispbm/kinetic.lisp` (see `lispbm/README.md`).

## 5. Fixed cockpit phone
* Settings → Start on boot, Keep screen on, Kiosk, Glove mode, Dim when idle.
* Full kiosk (no system UI escape) needs device-owner provisioning on a freshly reset phone (no Google account added yet):
  `adb shell dpm set-device-owner org.kineticos.app/.service.KioskAdminReceiver`, then enable Settings → Kiosk.
  Without it, Android *screen pinning* is used. Kinetic OS can also be chosen as the HOME app (it registers as a launcher),
  which also lets the UI come up automatically after boot.
* Handlebar remotes: Bluetooth media remotes / keyboards work: next/prev = screen, volume up/down = mode, play = marker.

## 6. Server (group rides, telematics, web dashboard)
See [../server/README.md](../server/README.md). Short version on any Docker host (ZimaOS/CasaOS: "Custom install" with the
compose file):
```bash
cd server && cp .env.example .env && sed -i "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" .env
docker compose up -d                    # http://<nas>:8080  (dashboard + API)
docker compose --profile https up -d    # with Caddy + Let's Encrypt (set DOMAIN, ACME_EMAIL, open ports 80/443)
```
App: Settings → Account → server URL (`https://ride.example.com`), register/login. Then Group, Remote and telematics work.
Backups: nightly `pg_dump` into `server/backups/` (restore instructions in server/README.md).

### Optional: turn-by-turn road routing (self-hosted OSRM)
```bash
cd server && ./osrm/prepare.sh europe/germany/bayern car    # Geofabrik region, profile car|bicycle|foot (motorcycle = car)
docker compose --profile routing up -d
```
In the app: Map → NAVIGATE TO… → tap the destination. Re-routes automatically when you leave the route.
RAM/disk: roughly 2–3× the .pbf size during preparation (see server/README.md).

### Alert notifications on a second phone
Install Kinetic OS on the second phone, log in with the same account, Remote → enable *Alert notifications*. Alerts arrive
instantly while the app runs and are checked every 15 min in the background.
