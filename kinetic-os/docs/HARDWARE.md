# Hardware compatibility & required hardware tests

## Compatibility (expected, **not yet validated on hardware**)
| Hardware | Basis | Expected |
|---|---|---|
| VESC 6 / 75/300 / Trampa, Flipsky, Makerbase, Spintend etc. with FW 5.03–7.x | standard COMM protocol | telemetry ✓, ride modes ✓ (MCCONF_TEMP) |
| VESC FW < 5.03 | older layouts | telemetry likely ✓ (missing fields = n/a), **writes blocked** |
| VESC Express (ESP32-C3) BLE / WiFi | NUS UUIDs verified in source | ✓ |
| NRF51/52 VESC BLE modules | Nordic UART | ✓ (20-byte MTU → lower poll rate) |
| HM-10/CC2541 style "BLE UART" (FFE0) | different service | ✗ not supported |
| USB: VESC native (STM32 VCP) | CDC-ACM | ✓ |
| USB-UART CP210x / CH34x / FTDI | vendor protocols | ✗ |
| Multiple VESCs on CAN | PING_CAN + FORWARD_CAN | ✓ telemetry per ID; modes forwarded by firmware |
| VESC BMS | COMM_BMS_GET_VALUES | ✓ parser tested |
| VESC IMU (on-board) | COMM_GET_IMU_DATA | ✓ lean/pitch/accel if calibrated |
| Phones | Android 8+; Samsung tested in Robolectric only | ✓ |

## Mandatory hardware test protocol (before riding)
Perform on a **stand with the driven wheel off the ground**, kill switch in reach, battery fused.
1. **Link:** connect via BLE, USB, WiFi. Check FW/HW/UUID/CAN IDs on Telemetry screen. Unplug/replug → auto-reconnect < 15 s.
2. **Telemetry plausibility:** voltage vs. multimeter (±0.5 V); battery current vs. clamp meter; FET/motor temperature vs.
   IR thermometer; speed vs. GPS at constant speed (adjust wheel diameter/gear ratio). Record results in TEST_RESULTS.md.
3. **Backup:** first connect must show "Stored limits backed up". Compare with VESC Tool → Motor → Current/Power/ERPM.
4. **ECO at standstill:** apply ECO → verify in VESC Tool (*Read temporary config*… or observe reduced current at full
   throttle on the stand). Power-cycle the VESC → stock restored.
5. **Refusal checks:** spin wheel > 2 km/h → switch ECO→RACE must be refused; RACE→ECO must be accepted.
6. **Link loss:** apply ECO, kill Bluetooth → wheel must stay at ECO limits, throttle/brake unaffected.
7. **Faults:** provoke under-voltage (bench supply) → app shows fault, writes refused.
8. **LispBM:** upload `kinetic.lisp`, verify boot ceiling read, mode commands, derating by heating FET (hair-dryer) — see
   lispbm/README.md. Check `conf-set` parameter names on your firmware version.
9. **CAN multi-motor:** verify each controller's limits changed (VESC Tool on each ID).
10. **Cockpit:** 1 h ride-equivalent bench run with screen off: no missing samples, ride saved, telematics online/offline
    correctly reported on the web dashboard.
11. **Anti-theft lock** (wheel off the ground): set PIN, lock at standstill → full throttle must produce no torque;
    try to lock while the wheel spins → must be refused; power-cycle the VESC → still locked; turn the wheel by hand
    > 1 s → tamper warning on the phone and `theft` alert in the web dashboard; 5 wrong PINs → lockout; unlock → normal.
    Verify recovery without PIN via VESC Tool (lispbm/README.md) before relying on it.
Only after 1–11 pass: short low-speed ride in a closed area.
Virtual flywheel, traction and wheelie control remain disabled; they need wheel-speed and IMU validation plans of their own.
