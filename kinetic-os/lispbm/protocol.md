# Kinetic OS custom-app-data protocol v1

Transport: VESC `COMM_CUSTOM_APP_DATA` (phone -> VESC arrives in LispBM as `event-data-rx`; VESC -> phone via `send-data`). Payload = raw bytes below. All multi-byte integers are **big-endian** (LispBM `bufget/bufset` default). Packets with wrong magic/version are ignored silently (no reply).

## Request (phone -> VESC)

| Byte | Field | Notes |
|---|---|---|
| 0 | magic | 0x4B ('K') |
| 1 | version | 1 |
| 2 | command | 1 set mode, 2 set custom params, 3 status request |

### cmd 1 - set mode (length 4)
| 3 | mode index | 0 eco, 1 sport, 2 race, 3 custom1, 4 custom2 |

### cmd 2 - set custom mode params (length 14)
| Byte | Field | Type |
|---|---|---|
| 3 | slot | u8, 3 or 4 only |
| 4-5 | current scale, permille (0..1000) | u16 |
| 6-7 | max power, watts | u16 |
| 8-9 | max battery current, 0.1 A | u16 |
| 10-13 | max ERPM | u32 |

Values above the boot-time ceiling are silently clamped to the ceiling. Does not select the slot; send cmd 1 afterwards. If the slot is active and the vehicle is moving, an increase is rejected (code 3).

### cmd 3 - status request (length 3)
Reply only.

## Reply (VESC -> phone), 17 bytes. Sent after every accepted/rejected cmd 1-3.

| Byte | Field | Type / notes |
|---|---|---|
| 0 | magic | 0x4B |
| 1 | version | 1 |
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
Set race mode: `4B 01 01 02`. Status request: `4B 01 03`.
