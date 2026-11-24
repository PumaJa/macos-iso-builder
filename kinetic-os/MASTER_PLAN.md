# Master plan

Goal: an Android cockpit that offers the feature set of an integrated e-moto dash (ride modes, telemetry, navigation, group rides,
telematics, racing) for **any VESC-based vehicle**, without extra hardware, with safety and honesty about data quality as
hard requirements.

## Guiding decisions (and why)
1. **Independent app instead of modifying the Stark app.** No Stark APK was provided, the Stark app is proprietary, and no
   authorised integration interface exists (RESEARCH.md). Modifying/redistributing it would violate its license; so we build
   an original, compatible-in-spirit app. Look & feel is original (no Stark assets, logos, fonts or trade dress).
2. **Group rides via a self-hosted server.** Stark group riding has no public API; we do not emulate Stark devices/accounts.
3. **Ride modes via `COMM_SET_MCCONF_TEMP` (RAM-only) instead of full `SET_MCCONF`.** Full config writes are
   firmware-version-specific binary blobs; a mistake can brick or endanger. TEMP writes change only runtime limits and
   firmware clamps current scales to 0..1. Our SafetyGate additionally caps everything at the backed-up stored values.
4. **On-vehicle logic in LispBM** (derating, mode table) so it works even when the phone/BLE is gone.
5. **Pure-Kotlin core modules** so protocol, telemetry, safety, racing and export are unit-testable on the JVM.

## Phases
| # | Phase | State |
|---|---|---|
| 1 | Environment, research, protocol extraction | done |
| 2 | VESC protocol core + simulator + tests | done |
| 3 | Telemetry engine, garage, ride profiles, SafetyGate | done |
| 4 | Racing + export modules | done |
| 5 | Server + dashboard + Docker | done (integration-tested with PostGIS) |
| 6 | LispBM script | written, unvalidated |
| 7 | Android app (transports, data, service, UI) | done, builds |
| 8 | Tests (unit, Robolectric UI), emulator | see TEST_RESULTS.md |
| 9 | Hardware validation | **pending – needs a real vehicle** |
