# Progress log

| Date | Step | Result |
|---|---|---|
| 2026-11-24 | Environment analysis | Repo was a macOS ISO builder; project placed in `kinetic-os/`. SDK installed. |
| 2026-11-24 | Research (subagent) | No Stark API/VargLink; VESC 7.00 current |
| 2026-11-24 | Protocol extraction | enums generated from datatypes.h; layouts read from commands.c/buffer.c/bms.c |
| 2026-11-24 | vesc-protocol | 22 tests green (one decoder resync bug found & fixed) |
| 2026-11-24 | server (subagent) | 43 tests incl. real PostGIS integration green |
| 2026-11-24 | telemetry/vehicle | 19 tests green |
| 2026-11-24 | racing/export (subagent) | 60 tests green |
| 2026-11-24 | LispBM (subagent) | written, unvalidated |
| 2026-11-24 | Android app | compiles, debug APK built |
| 2026-11-24 | App tests (Robolectric, MockWebServer) | see TEST_RESULTS.md |
| 2026-11-24 | Docker stack + E2E smoke + Chromium dashboard | all green |
| 2026-11-24 | Emulator (TCG) | app runs; FGS start crash found & fixed; map blocked by sandbox TLS proxy |
| 2026-11-25 | LispBM in real interpreter (subagent) | 141 assertions + 5 smoke checks; rounding bug fixed; protocol v2 (u32 watts) |
| 2026-11-25 | OSRM routing (subagent) | /api/v1/route + compose profile; e2e Zurich route 7.1 km / 17 steps |
| 2026-11-25 | Throttle (ADC), odometer, Keystore token, CSV injection guard | tests green |
| 2026-11-25 | Alert notifications (WS + WorkManager) | Robolectric test green |
| 2026-11-25 | Turn-by-turn client, MP4 overlay export (Media3) | unit tests green; export instrumented on emulator |
| 2026-11-25 | Forum research (subagent) + decisions | docs/USER_WISHES.md, 9 wishes built, 3 declined with reasons |
| 2026-11-25 | PiP cockpit, warnings (TTS/vibration), dash tiles, sun theme, chemistry + coulomb SoC | tests green |
| 2026-11-25 | App ↔ on-vehicle script bridge, VESC setup check, kinetic.vescpkg (subagent built VESC Tool 7.01) | E2E via simulator green |
| 2026-11-25 | Anti-theft lock (LispBM via subagent + app/server/sim) | 223+9 LispBM, 145 Android, 52 server green; vescpkg rebuilt |
