# Research log (2026-11-24)

Raw notes with all source URLs: [docs/research_raw_stark.md](docs/research_raw_stark.md). Protocol extraction:
[docs/vesc_protocol_reference.md](docs/vesc_protocol_reference.md).

## Stark VARG ecosystem
* Features advertised: ride/power modes incl. custom, 10-point power/regen curve, engine braking, flywheel effect, traction
  control (OTA, June 2026), digital lock, GPS tracking, OTA via Stark app. (Press/marketing sources – not verified on a bike.)
* **No public developer API**, no documented BLE protocol, no documented cloud API, **no official group-ride feature for
  third-party vehicles** found. A forum report says only the Stark phone connects to the VARG.
* Third-party community efforts exist (Svag, VargPilot, `b1naryth1ef/svag-telemetry-format` – license unstated). They target
  reading Stark bikes, not letting non-Stark vehicles join Stark services. Not used.
* **Stark app 2026 (checked 2026-11-25):** traction control via OTA for VARG Alpha, intervention 0–100 % per riding mode,
  disengages when airborne; app/bike version history in settings; power maps blocked below a minimum power; map style kept
  between rides. Sources: electrek.co (2026-06-28), powersportsbusiness.com (2026-06-30), Google Play listing "VARG"
  (com.stark.moto, updated 2026-09-29). Kinetic implements the same *functions* for VESC (TC in the vehicle script), not the design.
* **VargLink** (varglink.com, checked 2026-11-25): independent third-party Android app (`ru.varg.link`), not made by Stark.
  Group rides; guests without a Stark bike can join via an invite link (app or browser). No public API → Kinetic integrates
  only by opening invite links / the app (`GroupScreen.VargLinkCard`). No data exchange, no login, no reverse engineering.
  (Earlier search in this project had not found it.)
* No Stark APK was supplied, so no APK analysis was performed. Analysing/redistributing a proprietary APK to clone its UI
  would not be license-compliant anyway.
* Conclusion: original Stark group-ride compatibility = **requires an authorised external interface (EXT)**. Implemented
  instead: own self-hosted group system, plus a button to open the official Stark app.

## VESC
* Current firmware: **7.00** (released 2026-05-15); master is 7.01-beta1. 6.05/6.06 are older. **"6.7" does not exist** – the
  request probably meant 6.0x or 7.0x; both are covered (layouts are backward compatible: newer fields are appended, and the
  parser treats missing tail fields as unavailable).
* All packet IDs (0–159, 153–155 reserved), fault codes (0–34) and payload layouts were taken from `bldc/datatypes.h`,
  `comm/commands.c`, `util/buffer.c`, `util/crc.c`, `comm/packet.c`, `bms.c` (master) and generated/encoded from source.
* BLE: VESC Express `main/comm_ble.c` uses service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, char `…0002` write/write-no-rsp,
  char `…0003` read/notify, default 20-byte MTU raised by negotiation – verified in source and matched by `BleVescTransport`.
* Licenses: bldc, vesc_tool, vesc_pkg, vesc_express – GPL-3.0. Kinetic OS is GPL-3.0 accordingly.
* Existing OSS apps: FreeSK8 mobile (Flutter, GPL-3.0, FW 5.1–7.00) – evaluated as a base, rejected because the requested
  stack is Kotlin/Compose and its architecture is board-specific. VESC Tool (Qt) runs on Android but is a config tool.
