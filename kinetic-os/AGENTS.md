# Notes for AI agents / contributors

* Read `MASTER_PLAN.md`, `ARCHITECTURE.md`, `TASKS.md`, `KNOWN_ISSUES.md` first. Update `PROGRESS.md`/`TASKS.md` after each step.
* Build: `cd android && gradle assembleDebug` (needs Android SDK 35, JDK 17+; `local.properties` → `sdk.dir`).
  Tests: `gradle :core:vesc-protocol:test :core:telemetry:test :core:vehicle:test :core:racing:test :core:export:test :app:testDebugUnitTest`.
  Server: `cd server && npm ci && npx tsc --noEmit && npx vitest run` (integration: `TEST_DATABASE_URL=postgres://…`).
* Never add a VESC write outside `SafetyGate`. Never present estimated/simulated data as measured. Never fabricate APIs –
  verify against `vedderb/bldc` source (`docs/vesc_protocol_reference.md`, `CommPacketId.kt` is generated from `datatypes.h`).
* Do not load the whole decompiled firmware or big logs into context; grep specific functions.
* UI must stay original: no Stark assets, logos, fonts, or trade dress.
