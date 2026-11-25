# Kinetic OS custom-app-data protocol v2 (v1 still accepted)

Transport: VESC `COMM_CUSTOM_APP_DATA` (phone -> VESC arrives in LispBM as `event-data-rx`; VESC -> phone via `send-data`). Payload = raw bytes below. All multi-byte integers are **big-endian** (LispBM `bufget/bufset` default). Versions 1 and 2 are accepted; any other magic/version is ignored silently (no reply). The only difference between them is the cmd 2 layout (v2 widens the watt field to u32). Every reply echoes the version of the request it answers; the reply layout is identical in both versions.

## Request (phone -> VESC)

| Byte | Field | Notes |
|---|---|---|
| 0 | magic | 0x4B ('K') |
| 1 | version | 2 (current) or 1 (legacy cmd 2 layout) |
| 2 | command | 1 set mode, 2 set custom params, 3 status request, 4 lock, 5 unlock, 6 set PIN |

### cmd 1 - set mode (length 4)
| 3 | mode index | 0 eco, 1 sport, 2 race, 3 custom1, 4 custom2 |

### cmd 2 - set custom mode params
v2 (length 16, version byte 2):
| Byte | Field | Type |
|---|---|---|
| 3 | slot | u8, 3 or 4 only |
| 4-5 | current scale, permille (0..1000) | u16 |
| 6-9 | max power, watts | u32 |
| 10-11 | max battery current, 0.1 A | u16 |
| 12-15 | max ERPM | u32 |

v1 (length 14, version byte 1, legacy; watts limited to 65535):
| Byte | Field | Type |
|---|---|---|
| 3 | slot | u8, 3 or 4 only |
| 4-5 | current scale, permille (0..1000) | u16 |
| 6-7 | max power, watts | u16 |
| 8-9 | max battery current, 0.1 A | u16 |
| 10-13 | max ERPM | u32 |

The status reply carries no watt value, so it is the same in v1 and v2. A cmd 2 shorter than its version requires is rejected with code 4. Values above the boot-time ceiling are silently clamped to the ceiling. Does not select the slot; send cmd 1 afterwards. If the slot is active and the vehicle is moving, an increase is rejected (code 3).

### cmd 3 - status request (length 3)
Reply only.

### cmd 4 - lock (length 7) / cmd 5 - unlock (length 7)
| Byte | Field | Type |
|---|---|---|
| 3-6 | PIN | u32, big-endian |

Lock is accepted only if a PIN is set (else code 8), the PIN is correct (7 wrong, 9 locked out) and |ERPM| < stop threshold (else 3). Locking persists a flag in EEPROM, disables app output (`app-disable-output -1`) and forces `l-current-max-scale` to 0. Unlock with the correct PIN clears the flag, re-enables output and restores the mode limits (current scale ramps up). Both are idempotent. Wrong PINs are counted: after 5 consecutive failures every PIN-checked command (4, 5, 6) is refused with code 9 for 60 s; each further failure series doubles it (120 s, 240 s, ... capped at 3600 s). A correct PIN resets the counters. The lockout timer is RAM-only.

### cmd 6 - set PIN (length 11)
| Byte | Field | Type |
|---|---|---|
| 3-6 | old PIN (ignored if no PIN is set yet) | u32 |
| 7-10 | new PIN, 1000..99999999 (4-8 digits) | u32 |

Only while unlocked (else code 11). New PIN outside range: code 10. Wrong old PIN counts toward the lockout.

While locked, cmd 1 and cmd 2 are refused with code 11; cmd 3 (status) is always answered.

## Reply (VESC -> phone), 19 bytes. Sent after every accepted/rejected cmd 1-3.

| Byte | Field | Type / notes |
|---|---|---|
| 0 | magic | 0x4B |
| 1 | version | echoes request version (1 or 2) |
| 2 | echoed command | u8 |
| 3 | result code of this command | u8, 0 = OK, else reject code |
| 4 | current mode | u8 0..4 |
| 5-6 | effective scale, permille | u16, fraction of boot-time current scale after derate + rate limit |
| 7 | derate flags | bit0 MOSFET temp, bit1 motor temp, bit2 battery voltage |
| 8 | last reject reason | u8, 0 = none since last success |
| 9 | MOSFET temp degC | i8 |
| 10 | motor temp degC | i8 |
| 11-12 | input voltage, 0.1 V | u16 |
| 13-16 | ERPM | i32 |
| 17 | lock flags | u8: bit0 locked, bit1 PIN set, bit2 TAMPER, bit3 locked out (PIN attempts refused) |
| 18 | lockout remaining, seconds | u8, rounded up, saturates at 255 |

Bytes 0-16 are identical to the earlier 17-byte reply, so old parsers that read only those still work.

## Reject / result codes
| Code | Meaning |
|---|---|
| 0 | OK |
| 2 | bad mode index |
| 3 | vehicle moving (|ERPM| >= threshold) and request would increase power |
| 4 | packet too short |
| 5 | unknown command |
| 6 | cmd 2 slot not 3 or 4 |
| 7 | wrong PIN |
| 8 | no PIN set (cmd 4/5) |
| 9 | PIN attempts locked out (see cmd 4/5) |
| 10 | new PIN not 1000..99999999 |
| 11 | vehicle is locked (cmd 1, 2, 6 refused) |

(Code 1 is unused/reserved.)

## Example
Lock with PIN 1234: `4B 02 04 00 00 04 D2`. Unlock: `4B 02 05 00 00 04 D2`. Set first PIN 1234: `4B 02 06 00 00 00 00 00 00 04 D2`.
Set race mode: `4B 02 01 02` (v1 form `4B 01 01 02` also works). Status request: `4B 02 03`. Set slot 3 to 120000 W (v2): `4B 02 02 03 01F4 0001D4C0 00FA 0000C350` (bytes concatenated; scale 500, 25.0 A, 50000 ERPM).
