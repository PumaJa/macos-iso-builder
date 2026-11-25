# Tasks

## Done
- [x] Environment: JDK 21, Gradle 8.14, Android SDK 35, Docker, PostGIS
- [x] Research Stark/VargLink/VESC (RESEARCH.md)
- [x] VESC protocol from firmware source; codec, parsers, client, simulator
- [x] Telemetry engine with quality tags; garage; ride profiles; SafetyGate; derating
- [x] Racing; export/import
- [x] Server, dashboard, Docker Compose, backup, Caddy
- [x] LispBM script (unvalidated)
- [x] Android app: transports, Room, service, all screens, map, group, telematics, racing
- [x] Automated tests (see TEST_RESULTS.md)

## Open
- [ ] Hardware validation (docs/HARDWARE.md checklist) – needs a real VESC vehicle
- [x] LispBM: validated in real LispBM interpreter (141 assertions), protocol v2 (u32 watts)
- [ ] LispBM: run on real VESC, verify `conf-set` names on FW 6.06/7.00; add throttle-curve/engine-brake control (needs validation)
- [x] Telematics alert notifications (WebSocket live + WorkManager 15 min)
- [x] Turn-by-turn via self-hosted OSRM (+ prepare script, compose profile)
- [ ] Offline road routing on the phone (BRouter)
- [x] In-app MP4 overlay export (Media3 Transformer)
- [x] Keystore-encrypted token storage
- [x] Throttle (GET_DECODED_ADC), VESC odometer, CSV formula-injection guard
- [x] Emulator run (software emulation): app, simulator, map, MP4 export
- [ ] Release signing key for distribution
