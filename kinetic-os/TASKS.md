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
- [ ] LispBM: run on real VESC, verify `conf-set` names on FW 6.06/7.00; add throttle-curve/engine-brake control (needs validation)
- [ ] Push notifications for telematics alerts (FCM or UnifiedPush)
- [ ] Road routing engine (BRouter/GraphHopper offline) for turn-by-turn
- [ ] In-app MP4 rendering of video overlay (MediaCodec)
- [ ] Keystore-encrypted token storage
- [ ] Emulator run on a machine with KVM (this CI box has none)
- [ ] Release signing key for distribution
