# Kinetic OS custom-app-data protocol v2 (v1 still accepted)

Transport: VESC `COMM_CUSTOM_APP_DATA` (phone -> VESC arrives in LispBM as `event-data-rx`; VESC -> phone via `send-data`). Payload = raw bytes below. All multi-byte integers are **big-endian** (LispBM `bufget/bufset` default). Versions 1 and 2 are accepted; any other magic/version is ignored silently (no reply). The only difference between them is the cmd 2 layout (v2 widens the watt field to u32). Every reply echoes the version of the request it answers; the reply layout is identical in both versions.

## Request (phone -> VESC)

| Byte | Field | Notes |
|---|---|---|
| 0 | magic | 0x4B ('K') |
| 1 | version | 2 (current) or 1 (legacy cmd 2 layout) |
| 2 | command | 1 set mode, 2 set custom params, 3 status request |

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

## Reply (VESC -> phone), 17 bytes. Sent after every accepted/rejected cmd 1-3.

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

## Reject / result codes
| Code | Meaning |
|---|---|
| 0 | OK |
| 2 | bad mode index |
| 3 | vehicle moving (|ERPM| >= threshold) and request would increase power |
| 4 | packet too short |
| 5 | unknown command |
| 6 | cmd 2 slot not 3 or 4 |

(Code 1 is unused/reserved.)

## Example
Set race mode: `4B 02 01 02` (v1 form `4B 01 01 02` also works). Status request: `4B 02 03`. Set slot 3 to 120000 W (v2): `4B 02 02 03 01F4 0001D4C0 00FA 0000C350` (bytes concatenated; scale 500, 25.0 A, 50000 ERPM).
