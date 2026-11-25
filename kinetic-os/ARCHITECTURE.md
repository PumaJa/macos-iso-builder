# Architecture

```
┌─────────────────────────── Android app (:app) ───────────────────────────┐
│ UI (Jetpack Compose)  Dash · Map · Modes · Telemetry · Rides · Group ·   │
│                       Race · Garage · Connect · Remote · Settings        │
│        │ observes StateFlows only (no transport/protocol access)          │
│ AppGraph (DI + orchestration)                                             │
│  ├─ VehicleConnection  connect → identify FW/HW → CAN scan → probe caps → │
│  │                     backup limits → adaptive polling → reconnect      │
│  ├─ SafetyGate (core:vehicle) — the only path to a VESC write            │
│  ├─ RideRecorder (Room) · GpsSource (LocationManager)                    │
│  ├─ Api (REST, OkHttp) · LiveSocket (WebSocket, auto-reconnect)          │
│  └─ telematics / group-broadcast / derating / lap-timer loops            │
│ CockpitService (foreground: connectedDevice|location) · BootReceiver      │
│ Transports: BleVescTransport (Nordic UART) · UsbVescTransport (CDC-ACM)   │
│             TcpVescTransport (VESC Express / bridge) · Simulated          │
└──────────────────────────────────────────────────────────────────────────┘
        │ VescTransport (bytes)                         │ HTTPS / WSS
┌───────▼──────── core:vesc-protocol ─────────┐   ┌─────▼──── server/ ─────────────┐
│ PacketCodec/Decoder (framing, CRC16, resync)│   │ Fastify REST + ws               │
│ VescReader/Writer (float16/32/auto)          │   │ PostgreSQL + PostGIS            │
│ Commands / Parsers (FW, VALUES, SETUP, IMU,  │   │ alerts (geofence, battery,     │
│   BMS, MCCONF_TEMP, STATS, GNSS, PING_CAN)   │   │  fault, movement, maintenance) │
│ VescClient (req/resp, timeouts, retries,     │   │ web dashboard (Leaflet)        │
│   CAN forwarding, WriteToken)                │   │ backup container, Caddy HTTPS  │
│ SimulatedVescTransport (fault injection)     │   └────────────────────────────────┘
└──────────────────────────────────────────────┘
core:telemetry (TelemetryEngine, Q/Quality tags) · core:vehicle (VehicleProfile, RideProfile, MotorCharacter, SafetyGate,
Derating) · core:racing (Geo, LapTimer, GhostLap, AccelRun, Leaderboard) · core:export (CSV, JSON, GPX in/out, RideSummary)
lispbm/kinetic.lisp — runs on the VESC: mode table, local temperature/voltage derating, custom-app-data protocol
```

## Requested module list → implementation
| Requested module | Where |
|---|---|
| Android App | `android/app` |
| VESC Protocol | `core/vesc-protocol` (codec, model, client) |
| Bluetooth Transport / USB Transport | `app/.../transport/Ble*`, `UsbVescTransport`, plus `TcpVescTransport` |
| Vehicle Configuration / Garage | `core/vehicle/Vehicle.kt`, `ui/screens/GarageScreen.kt` |
| Ride Profiles | `core/vehicle/RideProfile.kt`, `ui/screens/ModesScreen.kt` |
| Telemetry Engine | `core/telemetry` |
| Data Recording | `app/.../core/RideRecorder.kt`, Room `data/Db.kt`, `core/export` |
| Navigation | `app/.../nav`, `ui/screens/MapScreen.kt` (MapLibre + OpenFreeMap/OSM) |
| Multiplayer | `app/.../net/LiveSocket.kt`, `GroupScreen.kt`, `server/` |
| Stark Compatibility / VargLink Integration | `GroupScreen.StarkCompatCard` (status only, no authorised Stark interface) + `GroupScreen.VargLinkCard` (opens VargLink invite links/app; no API) |
| Telematics | `AppGraph.telematicsLoop`, `net/Telematics.kt`, `RemoteScreen.kt`, server `/telematics` |
| Cloud Backend / Web Dashboard | `server/` |
| Security | `core/vehicle/SafetyGate.kt`, `WriteToken`, `SECURITY.md`, server auth/access logic |
| VESC Firmware Extensions | `lispbm/` |
| Tests | `*/src/test`, `server/test` |

## Data flow & threading
Transport emits fragments → `PacketDecoder` → `VescClient.dispatch` → awaiting request. Polling loop (Dispatchers.Default)
→ `TelemetryEngine.process` → `StateFlow<TelemetrySample>` → UI + recorder + telematics. One request in flight at a time
(VESC replies carry only the command id). Adaptive poll interval backs off on timeouts.

## Data quality model
Every value is a `Q(value, quality)`: MEASURED (from hardware), CALCULATED (deterministic from measured), ESTIMATED
(model-based, e.g. SoC from voltage, range, motor power), SIMULATED, UNAVAILABLE. UI shows the tag next to every number.
