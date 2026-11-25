# Kinetic OS

On-VESC ride-mode manager and local derating, written in LispBM.

**WARNING: NOT VALIDATED ON HARDWARE.** This script has only been run in the LispBM interpreter against stubs, never on a VESC. Test first on a stand with the wheel off the ground and a way to cut power. Do not ride until verified.

## What it does
- Reads `l-current-max-scale`, `l-watt-max`, `l-in-current-max`, `l-max-erpm` and `l-battery-cut-start` at start as the **hard ceiling**. It never applies anything above it.
- Ride modes: 0 eco (40%), 1 sport (70%), 2 race (100%), 3 and 4 custom.
- Derates current scale linearly from MOSFET temperature, motor temperature and battery voltage, rate limited (fast down, slow up).
- A mode that is more powerful is only accepted when stationary (|ERPM| < `stop-erpm`).
- Everything is enforced on the VESC. The phone/BLE link is only a request channel; if it drops, the last mode and derating keep working.

## Safety
- Limits are applied with `conf-set` (RAM only). Nothing is stored, so limits revert on reboot.
- If you re-upload while the script has already lowered the limits, power-cycle the VESC first, otherwise the lowered values become the new ceiling.
- VESC fault codes, battery cutoff and temperature limits stay active.
- A stopped script leaves the last applied limits until reboot.
- ERPM is a motor value; wheel slip or freewheeling can fool the stationary check.

## Protocol (custom app data, v2; v1 accepted)
Packets start with `0x4B 'K'`, a version byte (2) and a command byte. Multi-byte values are big-endian.
- cmd 1: set mode (`4B 02 01 <mode 0..4>`)
- cmd 2: set custom slot 3 or 4 (scale permille, watts, battery current 0.1 A, max ERPM); values above the ceiling are clamped
- cmd 3: status request (`4B 02 03`)

Every command is answered with a 17-byte status reply (mode, effective scale permille, derate flags, MOSFET/motor temperature, input voltage, ERPM, result code). See `protocol.md` in the repository.

## Tunables (top of the script)
`stop-erpm` 300, `loop-dt` 0.1 s, `derate-floor` 0.15, `step-down` 0.10, `step-up` 0.02, `fet-start/end` 70/90 degC, `mot-start/end` 90/120 degC, `use-mot-temp` (set 0 without a motor sensor), `v-start/v-end` (0 = auto from battery cut start), `v-auto-margin` 3.0 V, and the `m-*` mode tables. Change them in the LispBM editor after install if needed.

## After installing
Make sure the script is set to run on boot (LispBM page, auto run) and test with the wheel off the ground.
