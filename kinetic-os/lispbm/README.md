# Kinetic OS - VESC LispBM script

> **WARNING: NOT VALIDATED ON HARDWARE.** This script was written against the official LispBM docs but has never been run on a VESC. Test first on a stand with the wheel off the ground, with a way to cut power. Do not ride until verified.

`kinetic.lisp` runs on the VESC itself. Ride modes, derating and the stationary-only up-shift rule are enforced locally; the phone/BLE link is only a request channel. If BLE drops, the last mode and derating keep working.

## Install
1. Connect to the VESC with VESC Tool (USB).
2. Open **VESC Dev Tools -> LispBM**.
3. Load `kinetic.lisp` into the editor, press **Upload** (or Run for a streamed, non-persistent test run first).
4. After upload, enable **Auto run** (checkbox / "Run on boot"; wording varies by VESC Tool version) so it starts at power-up.
5. Your phone app must use the VESC's custom app data (e.g. via the VESC BLE UART bridge); see `protocol.md` (v2; v1 requests still accepted).

Important: the ceiling is read with `conf-get` at script start. The script never calls `conf-store`, so limits revert on reboot. If you re-upload while the script was already running and has reduced limits, power-cycle the VESC first, otherwise the lowered values become the new ceiling.

## Install as VESC package
A VESC Tool package description lives in `vesc_pkg/kinetic/` (format of vedderb/vesc_pkg). `kinetic.lisp` stays the single source; the Makefile copies it at build time.

Build (needs the `vesc_tool` command-line binary, GPL-3.0, from vedderb/vesc_tool or an installed VESC Tool):
```
cd vesc_pkg/kinetic
make VESC_TOOL=/path/to/vesc_tool     # writes kinetic.vescpkg and dist/kinetic.vescpkg
```
Install: connect to the VESC, open **VESC Packages** (sidebar), use **Load Custom** / **Install from file** and pick `kinetic.vescpkg`, then press **Install**. Alternative without a package: use the manual LispBM upload described under Install. The package only shows as compatible on motor controllers (not VESC BMS / Express). After installing, check the script is set to run on boot, and read the warning at the top: it is not validated on hardware.

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

## Validated in LispBM interpreter (not on hardware)
`kinetic.lisp` was run in the real LispBM REPL (svenssonjoel/lispBM master, commit `1359ac0`, "LBM Version 0.41.0", 64-bit Linux build). This proves the core-language usage and the logic; it does **not** prove anything about a VESC. The WARNING above still applies in full.

Run: `./test/run_tests.sh` (clones/builds the REPL under `/tmp/claude-0/lbm/src` if missing; override with `LBM_SRC=` or `LBM_REPL=`; needs git, gcc, make, libreadline-dev, libpng-dev). It exits non-zero on any failure. Manual build: `cd <lispBM>/repl && make FEATURES="64"`.

Files: `test/stubs.lisp` (fake `conf-get`/`conf-set`/`get-temp-fet`/`get-temp-mot`/`get-vin`/`get-rpm`/`send-data`/`event-enable` with controllable state and call logs), `test/run_tests.lisp` (141 assertions), `test/smoke.lisp` (end-to-end driver).

Checked (141 assertions + 5 smoke checks, all passing):
- Core syntax actually accepted by the interpreter: `defun`, `define` (also inside functions, sets the global), `let`, `cond` with `t`, `if`, `{ }` blocks, `and`/`or`/`not`, `ix`/`first`/`rest`/`cons`, `to-i`/`to-float`, `bufcreate`, `bufget/bufset` u8/i8/u16/u32/i32 (big-endian), `spawn`, `recv` pattern `(event-data-rx . (? data))` with `_` fallthrough, `loopwhile`.
- Boot ceiling read from `conf-get`, auto voltage thresholds, eco applied at start.
- Every `conf-set` value stays <= boot ceiling (incl. ceiling scale 0.8, custom params of 65535/0xFFFFFFFF, hot and cold ticks).
- Mode changes: accepted when stationary; more-powerful rejected when |erpm| >= `stop-erpm` (incl. negative erpm and exactly 300) with code 3 and `last-reject` set; less/equal-powerful accepted while moving; bad index (code 2), short packet (4), unknown command (5), bad custom slot (6).
- Custom params: decode of the 14-byte request, clamping to ceiling, no slot selection, active-slot increase rejected while moving, decrease applied immediately.
- Derating: `derate-hi`/`derate-lo` (start, midpoint, end, beyond, floor), FET/motor/voltage flags, `use-mot-temp 0`, min of all derates, mode scale as upper bound.
- Rate limiter: -0.10/tick down, +0.02/tick up, floor, recovery to mode scale.
- Status reply: 17-byte layout byte-by-byte against `protocol.md` (big-endian, signed temps/erpm), all 1001 permille and 400 vin values round-trip.
- Protocol v2 (u32 watts, 16-byte cmd 2): 120000 W accepted exactly, 4e9 W clamped to the ceiling, field byte order, short/bad-slot rejects, replies echo request version; v1 (u16 watts, 14-byte cmd 2) still works unchanged.
- Bad magic / version not 1 or 2 / too short: no reply, no state change, no `conf-set`.
- Event handler thread via real `spawn`/`recv`/`send`; and the unmodified full script (autostart + forever loop) under a driver thread.

Not checked (needs real VESC firmware): that your firmware's LispBM is new enough for these constructs (the REPL is newer than most VESC releases), `conf-set` semantics and range checks for each parameter (the stub accepts any number; real FW may need `conf-store`/`conf-set` ordering, or ignore/limit values), whether `l-current-max-scale` etc. take effect immediately in the running motor controller, `event-data-rx` payload format and `send-data` routing over BLE, real sensor units/ranges (`get-rpm` is ERPM; `get-temp-mot` may be -1 or 0 without a sensor), timing/CPU load of the 0.1 s loop, and heap/stack limits on the VESC.

## Verified against the official docs (vedderb/bldc lispBM/README.md)
Present: `conf-set`, `conf-get`, `conf-store` (not used), `get-temp-fet`, `get-temp-mot`, `get-vin`, `get-rpm`, `send-data`, `event-register-handler`, `event-enable`, `event-data-rx` (data = byte array), `bufget-i8/u8/i16/u16/i32/u32/f32`, `bufset-*`, `buflen`, `bufcreate`, `loopwhile`, `spawn`, `recv`, `sleep`, `abs`, and config params `l-current-max-scale`, `l-watt-max`, `l-in-current-max`, `l-max-erpm`, `l-battery-cut-start`.

Uncertain (core LispBM, not documented in that README): `defun`, `define`, `let`, `cond`, `if`, `and`/`or`/`not`, `first`/`rest`/`cons`/`ix`, `to-i`, `to-float`, `{ }` progn blocks. `bufset-i8` assumed (docs list bufset-i8). Redefining globals with `define` inside functions is assumed to work. `bufget-u32` returns possibly large values; fine for erpm < 2^28. Written with `get-batt` not used.
