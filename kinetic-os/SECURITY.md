# Security & safety architecture

## Vehicle safety (highest priority)
| Rule | Enforcement |
|---|---|
| Throttle, brakes, motor control never depend on the phone | App sends **no** `SET_CURRENT/DUTY/RPM` commands at all. Only `SET_MCCONF_TEMP` (limits) is ever written. |
| Writes only through one audited path | `SafetyGate` issues a single-use `WriteToken`; `VescClient.writeAckedChecked` requires it. UI code cannot build writes. |
| Never exceed configured hardware limits | On every connect the stored runtime limits are read (`GET_MCCONF_TEMP`) and the **first** backup per controller UUID is kept forever. All mode values are fractions ≤ 1 of that backup; firmware additionally clamps current scales to 0..1. |
| Reversible | `store = 0` on every write → RAM only. Power-cycling the VESC returns to stock. "Restore stock" re-writes the backup exactly. |
| Incompatible firmware | Writes blocked unless FW 5.03–7.x (layout verified in source) and the backup UUID matches the connected controller. |
| Safe states only | Power increases only below 2 km/h; reductions always allowed. No writes during faults or with an unhealthy link. |
| Unvalidated assistance | Virtual flywheel, traction control, wheelie control are refused by the gate until hardware validation exists. |
| Link loss | Limits stay as last applied (≤ stock). The LispBM script keeps derating locally without the phone. |
| Simulated data | Never mixed into real rides; flagged `source:"simulated"` in telematics; magenta UI. |

## App security
* HTTPS enforced by `network_security_config` (cleartext only for localhost/emulator/`.local`/`.lan`/`home.arpa` during NAS setup).
* JWT encrypted with AES-256-GCM using a non-exportable Android Keystore key (`TokenCipher`), stored in app-private DataStore.
* CSV exports neutralise spreadsheet formula injection (`=`, `+`, `-`, `@` prefixed text cells).
* BLE: VESC modules use their own pairing/PIN (`COMM_SET_BLE_PIN`); the app does not weaken it. BLE link-layer encryption
  depends on the module.
* No remote motor control endpoint exists on the server or in the app (verified by server test `no control endpoints`).
* GPX import parser rejects DOCTYPE/external entities (XXE tests).

## Server security
bcrypt password hashing, HS256 JWT (alg=none rejected – tested), zod validation of every body and WS message, per-route rate
limits, private groups readable only by members, telematics owner-only, photo access limited to owner/shared group members,
non-root container, HTTPS via Caddy (Let's Encrypt) or any reverse proxy, secrets only via environment (`JWT_SECRET` required).
