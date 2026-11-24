# Test results (2026-11-24)

Three levels, kept strictly apart:
* **A – automated** (JVM unit/Robolectric, server vitest, real PostGIS)
* **E – emulator / container** (Android emulator, Docker stack, headless Chromium)
* **H – real hardware** (VESC + vehicle) → **none yet**, see docs/HARDWARE.md

## A – Automated
| Suite | Tests | Result | Covers |
|---|---|---|---|
| core:vesc-protocol | 22 | ✅ | CRC vector, framing (short/long/very long), 200 random packets with random fragmentation, corruption + resync, garbage fuzz, float32_auto round-trip, GET_VALUES (FW 6/7 + old FW tail missing), FW_VERSION, BMS, MCCONF_TEMP layout, FORWARD_CAN, client via simulator: fragmentation, CAN ping/forward, dropped replies/retry, CRC injection, timeout, single-use WriteToken |
| core:telemetry | 10 | ✅ | speed from ERPM, PS/hp/kW, quality tags (CALC/EST/SIM/N/A), GPS fallback & accuracy rejection, trip energy/distance, range, SoC curve, adaptive poller, unconfigured drivetrain → no speed |
| core:vehicle | 11 | ✅ | SafetyGate: never exceeds backup, RAM-only, standstill rule, reductions while moving, fault/link/firmware/UUID refusals, locked assistance features, derating, exact restore; throttle curves; vehicle validation |
| core:racing | 35 | ✅ | geo math, gate crossings, laps at 1 Hz/10 Hz both directions, sectors, min-lap, invalid laps, best/delta, ghost, accel runs, leaderboard |
| core:export | 25 | ✅ | CSV RFC4180 round-trip, JSON meta, GPX export/import round-trip, XXE/billion-laughs rejection, ride summary math |
| app (JVM + Robolectric) | 14 | ✅ | REST (MockWebServer) auth/errors; WebSocket join/ping/member parsing/**server-close reconnect**; telematics mapping never invents values; Room recorder (resume after process death, never mixes simulated data), backups; route guidance/GPX; **Compose UI with simulator**: offline dash, SIM labels, ECO applied through SafetyGate, RACE refused while moving; telemetry/modes/garage/settings/connect render |
| server (vitest) | 43 | ✅ | validation, codes, auth (alg=none rejected), access rules, online calc, alert logic, WS validation/rate limits, app routes, **integration against postgis/postgis:16-3.4** |
| **Total** | **160** | ✅ | |

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
| Android emulator (API 30 x86_64, **software CPU emulation – no KVM on this machine**) | ✅ release APK installs & launches; Connect → START SIMULATOR → "Online: SIM_VESC · FW 6.06 · CAN 1"; dash shows SIM/REC/GPS; app restart auto-reconnects; no app crashes after fix |
| Emulator finding #1 (fixed) | `startForegroundService` deadline missed on slow device → crash/ANR. Fixed: plain `startService` from foreground, lazy app init, FGS-type fallback |
| Emulator finding #2 (environment) | Map tiles not loaded: emulator does not trust this sandbox's TLS-inspecting proxy CA (`CertPathValidatorException`). The app now shows the error instead of a blank map. Not an app defect – verify tiles on a real device |

## H – Hardware
Nothing validated on real hardware. Status of every hardware-dependent feature in FEATURE_MATRIX.md is "IH".
