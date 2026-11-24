# Kinetic OS - VESC LispBM script

> **WARNING: NOT VALIDATED ON HARDWARE.** This script was written against the official LispBM docs but has never been run on a VESC. Test first on a stand with the wheel off the ground, with a way to cut power. Do not ride until verified.

`kinetic.lisp` runs on the VESC itself. Ride modes, derating and the stationary-only up-shift rule are enforced locally; the phone/BLE link is only a request channel. If BLE drops, the last mode and derating keep working.

## Install
1. Connect to the VESC with VESC Tool (USB).
2. Open **VESC Dev Tools -> LispBM**.
3. Load `kinetic.lisp` into the editor, press **Upload** (or Run for a streamed, non-persistent test run first).
4. After upload, enable **Auto run** (checkbox / "Run on boot"; wording varies by VESC Tool version) so it starts at power-up.
5. Your phone app must use the VESC's custom app data (e.g. via the VESC BLE UART bridge); see `protocol.md`.

Important: the ceiling is read with `conf-get` at script start. The script never calls `conf-store`, so limits revert on reboot. If you re-upload while the script was already running and has reduced limits, power-cycle the VESC first, otherwise the lowered values become the new ceiling.

## Behaviour
- Boot: reads `l-current-max-scale`, `l-watt-max`, `l-in-current-max`, `l-max-erpm`, `l-battery-cut-start` as the **hard ceiling**. Every applied value is `min(value, ceiling)`. Starts in mode 0 (eco).
- Modes: 0 eco, 1 sport, 2 race, 3 custom1, 4 custom2. Default table = fractions of the ceiling (eco 40%, sport 70%, race 100%, custom 50%/60%). Mode scale is a fraction of the boot current scale.
- Mode change accepted if |ERPM| < `stop-erpm`, or the new mode is not more powerful in every limit (scale, watt, battery current, erpm) than the current one.
- Derating every `loop-dt` (0.1 s): linear derate from MOSFET temp, motor temp and battery voltage; applied scale = `min(mode scale, derates)`, then rate limited (fast down, slow up) and written with `conf-set 'l-current-max-scale` (RAM only).
- Watt, battery current and ERPM limits are applied on mode change; they are not derated by the script.

## Tunables (top of `kinetic.lisp`)
| Name | Default | Meaning |
|---|---|---|
| stop-erpm | 300 | stationary threshold (ERPM, not km/h; depends on pole pairs) |
| loop-dt | 0.1 | derate loop period (s) |
| derate-floor | 0.15 | minimum derate factor |
| step-down / step-up | 0.10 / 0.02 | rate limiter per tick |
| fet-start / fet-end | 70 / 90 | MOSFET degC |
| mot-start / mot-end | 90 / 120 | motor degC |
| use-mot-temp | 1 | set 0 without a motor sensor |
| v-start / v-end | 0 (auto) | auto = battery-cut-start + v-auto-margin / battery-cut-start |
| v-auto-margin | 3.0 | V |
| default mode fractions | see `m-*` lists | edit tables |

## Not implemented
- **Forward/reverse crawl mode**: not implemented in LispBM. It needs app-level and hardware validation (reverse limits, direction handling, `l-min-erpm`). Document-only.
- Speed limit uses `l-max-erpm`; `max-speed` (m/s) is not touched. Reverse limits (`l-min-erpm`, `l-current-min-scale`) are untouched.
- Regen/brake limits and `l-in-current-max-scale` (FW 7.01+ only) are not used.
- Nothing is persisted (no `conf-store`), custom mode edits are lost on reboot.

## Safety notes
- Phone data can only lower or equal the boot ceiling; values above are clamped.
- VESC's own hard limits (fault codes, `l-battery-cut`, temp limits) remain active under the script.
- A crashed/stopped script leaves the last applied limits in RAM until reboot.
- Up-shift while moving is rejected (code 3), but ERPM is a motor value; wheel slip or freewheel can fool it.

## Verified against the official docs (vedderb/bldc lispBM/README.md)
Present: `conf-set`, `conf-get`, `conf-store` (not used), `get-temp-fet`, `get-temp-mot`, `get-vin`, `get-rpm`, `send-data`, `event-register-handler`, `event-enable`, `event-data-rx` (data = byte array), `bufget-i8/u8/i16/u16/i32/u32/f32`, `bufset-*`, `buflen`, `bufcreate`, `loopwhile`, `spawn`, `recv`, `sleep`, `abs`, and config params `l-current-max-scale`, `l-watt-max`, `l-in-current-max`, `l-max-erpm`, `l-battery-cut-start`.

Uncertain (core LispBM, not documented in that README): `defun`, `define`, `let`, `cond`, `if`, `and`/`or`/`not`, `first`/`rest`/`cons`/`ix`, `to-i`, `to-float`, `{ }` progn blocks. `bufset-i8` assumed (docs list bufset-i8). Redefining globals with `define` inside functions is assumed to work. `bufget-u32` returns possibly large values; fine for erpm < 2^28. Written with `get-batt` not used.
