# Test results (updated 2026-11-25)

Three levels, kept strictly apart:
* **A – automated** (JVM unit/Robolectric, server vitest, real PostGIS)
* **E – emulator / container** (Android emulator, Docker stack, headless Chromium)
* **H – real hardware** (VESC + vehicle) → **none yet**, see docs/HARDWARE.md

## A – Automated
| Suite | Tests | Result | Covers |
|---|---|---|---|
| core:vesc-protocol | 24 | ✅ | CRC vector, framing (short/long/very long), random fragmentation, corruption + resync, garbage fuzz, float32_auto, GET_VALUES (FW 6/7 + old FW), FW_VERSION, BMS, MCCONF_TEMP layout, FORWARD_CAN, **DECODED_ADC**, simulator: CAN, retries, CRC injection, timeout, WriteToken, **odometer/throttle** |
| core:telemetry | 17 | ✅ | **+ coulomb counting, LFP curve, rolling range, WarningEngine (battery steps, temperature early warnings, faults, link loss)** · speed from ERPM, units, quality tags, GPS fallback, energy/distance, range, SoC curve, adaptive poller, unconfigured drivetrain, **throttle/odometer tagging** |
| core:vehicle | 21 | ✅ | **+ lock protocol bytes, lock gate rules** · **+ VESC setup check, drivetrain import, on-vehicle script codec (byte-exact vs protocol.md), script plans** · SafetyGate (never exceeds backup, RAM-only, standstill rule, refusals, locked assistance, derating, restore), throttle curves, validation |
| core:racing | 35 | ✅ | geo, gate crossings, laps 1/10 Hz, sectors, ghost, accel, leaderboard |
| core:export | 26 | ✅ | CSV/JSON/GPX round trips, XXE rejection, ride summary, **CSV formula-injection guard** |
| app (JVM + Robolectric) | 22 | ✅ | **+ E2E anti-theft lock: PIN, refusal while moving, modes blocked, tamper → warning, lockout** · **+ E2E: simulator with emulated kinetic.lisp → mode applied via script, setup check renders** · REST/WS (reconnect), telematics mapping, Room recorder, route guidance, **OSRM parsing + turn-by-turn**, **alert notifications (dedupe, channel)**, **overlay timeline + renderer**, Compose UI with simulator |
| server (vitest) | 52 | ✅ | **+ theft alert / locked movement watch, migration 002 against PostGIS** · incl. **/route OSRM proxy (7 tests)** and integration against postgis/postgis:16-3.4 |
| LispBM (real LispBM 0.41 interpreter) | 223 + 9 smoke | ✅ | **+ immobilizer: EEPROM persistence over simulated reboot, output re-assert, PIN/lockout, tamper** · ceiling, mode accept/reject rules, clamping, derating, rate limiter, status byte layout, protocol v1/v2 |
| **Total** | **429** (145 Android + 52 server + 232 LispBM) | ✅ | |

Bugs found by these tests and fixed: decoder stall on corrupted length field; SafetyGate refusing ECO from stock while moving;
WebSocket not reconnecting after server-initiated close; simulated BMS values tagged MEAS; meaningless wheel speed without vehicle profile.

UI screenshots: Robolectric renders `docs/screenshots/{dash_*,telemetry_*,modes,garage,settings,connect}.png`; emulator `docs/screenshots/emulator_*.png`; web dashboard `web_dashboard_login.png`.

## E – Emulator / container
| Check | Result |
|---|---|
| `assembleDebug`, `assembleRelease` (incl. lint-vital) | ✅ |
| Docker image build + `docker compose up` (db, api, backup) | ✅ healthy; `/api/v1/health` → `{"ok":true,"db":true}` |
| End-to-end smoke (2 users, group via code, live WS position, 403 for outsider/non-owner, telematics offline flag, geofence_exit + low_battery alerts, dashboard served) | ✅ |
| Nightly backup container writes `pg_dump` | ✅ (first dump at start) |
| Web dashboard in headless Chromium: loads, Leaflet (SRI) loads, no JS errors | ✅ |
| Caddy HTTPS profile | ⚠ config validated only (no public domain here) |
| **Instrumented on emulator:** MP4 overlay export (Media3 Transformer, real encoder) | ✅ 4 s test video → MP4 with burned-in overlay, audio kept, overlay region changed, rest of frame unchanged; telemetry at 2.0 s shows the correct sample (`docs/screenshots/emulator_mp4_overlay_frame.png`) |
| OSRM routing end-to-end (docker) | ✅ Zurich extract: 7.1 km, 17 steps via `/api/v1/route`; 401/400/503 paths |
| Android emulator (API 30 x86_64, **software CPU emulation – no KVM on this machine**) | ✅ release APK installs & launches; Connect → START SIMULATOR → "Online: SIM_VESC · FW 6.06 · CAN 1"; dash shows SIM/REC/GPS; app restart auto-reconnects; no app crashes after fix |
| Emulator finding #1 (fixed) | `startForegroundService` deadline missed on slow device → crash/ANR. Fixed: plain `startService` from foreground, lazy app init, FGS-type fallback |
| Emulator finding #2 (environment) | Map tiles could not be loaded in this sandbox (direct: TLS interception; via proxy: proxy only reachable on host loopback). The app shows the error instead of a blank map. **Map rendering remains unverified – check on a real device.** |
| Finding #3 (fixed) | WorkManager init failure could cancel the runtime coroutine → `AlertNotifier.schedule` made non-throwing |

## H – Hardware
Nothing validated on real hardware. Status of every hardware-dependent feature in FEATURE_MATRIX.md is "IH".
