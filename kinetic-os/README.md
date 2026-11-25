# Kinetic OS – open cockpit for VESC vehicles

An Android "vehicle operating system" for electric motorcycles, motocross bikes, karts, e-bikes, scooters, DIY and RC builds
driven by **VESC®** motor controllers. It is an *original* app inspired by the integrated-dash concept of modern electric
motocross bikes (such as the Stark VARG). It is **not** affiliated with Stark Future, uses none of its code, assets or trademarks,
and cannot join Stark's own services (see [RESEARCH.md](RESEARCH.md)).

| Part | Path | Status |
|---|---|---|
| Android app (Kotlin, Jetpack Compose) | `android/app` | builds → `app-debug.apk` / `app-release.apk` |
| VESC protocol library (pure Kotlin/JVM) | `android/core/vesc-protocol` | layouts verified against `vedderb/bldc` master (FW 7.01-beta) |
| Telemetry engine, garage/ride profiles/safety, racing, export | `android/core/*` | unit-tested |
| Self-hosted server (multiplayer + telematics + REST + WebSocket) | `server/` | Node 22 / Fastify / PostgreSQL + PostGIS, Docker Compose |
| Web dashboard | `server/public` | served by the server at `/` |
| LispBM on-vehicle script (ride modes + derating) | `lispbm/` | **not validated on hardware** |

Start here: [docs/INSTALL.md](docs/INSTALL.md) · [FEATURE_MATRIX.md](FEATURE_MATRIX.md) · [ARCHITECTURE.md](ARCHITECTURE.md) ·
[SECURITY.md](SECURITY.md) · [KNOWN_ISSUES.md](KNOWN_ISSUES.md) · [TEST_RESULTS.md](TEST_RESULTS.md) · [docs/HARDWARE.md](docs/HARDWARE.md)

## Safety first
* Throttle, brakes and all safety-critical control stay on the VESC/vehicle hardware. The phone only **reads** telemetry and,
  through a safety gate, changes **runtime limits that can only reduce** what was configured in VESC Tool. Nothing is ever
  stored to controller flash; power-cycling the VESC restores the stock configuration.
* Simulation data is magenta-labelled everywhere and flagged in recordings, exports and telematics.
* Nothing in this repository has been validated on a real vehicle yet. Read [docs/HARDWARE.md](docs/HARDWARE.md) before riding.

License: GPL-3.0 (same as VESC firmware/VESC Tool from which protocol definitions were derived).
Font: Barlow © The Barlow Project Authors, SIL Open Font License 1.1 (`docs/licenses/Barlow-OFL.txt`).
