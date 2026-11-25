# Known issues & limitations

1. **No hardware validation.** Everything touching a real VESC (BLE/USB/TCP transports, ride-mode writes, LispBM) is only tested
   against the protocol simulator. Treat as experimental; test on a stand with the wheel off the ground.
2. **Stark compatibility:** no authorised interface → no joining Stark group rides, no Stark accounts, no display of Stark riders
   unless they use Kinetic. VargLink (third-party app `ru.varg.link`, not by Stark) has no public API: Kinetic only opens
   VargLink invite links in the VargLink app or the browser – no positions are exchanged between VargLink and Kinetic groups.
3. **Throttle curve / engine character / engine braking** are modelled and previewed, but the VESC executes its own ADC app
   throttle mapping. Executing custom curves requires a LispBM throttle controller, which is intentionally not shipped
   unvalidated. Approximate engine characters are illustrative only.
4. **Throttle position** comes from `COMM_GET_DECODED_ADC`; it is only meaningful if the throttle is wired to the VESC ADC input (PPM/UART throttles show the ADC level instead – documented in the UI).
5. **Motor power** is an estimate (duty × V × I_motor); efficiency is not shown.
6. **USB:** only CDC-ACM (VESC native USB, CDC USB-UART bridges). CP210x/CH34x/FTDI adapters are not supported.
7. **Navigation:** turn-by-turn needs the optional self-hosted OSRM (`--profile routing`) and network; offline only GPX/planned-route following. Geofabrik download in `osrm/prepare.sh` was not testable in the build sandbox (tested with a bbbike.org extract). Instructions are English only.
8. **Video overlay:** MP4 export re-encodes with H.264; long 4K videos take long on older phones. Fixed overlay layout.
9. **Telematics alerts:** background check every 15 min (Android minimum without a push service); instant only while the app process runs. Location when the phone is off needs a GPS tracker.
10. **Kiosk mode:** full lock-task requires provisioning the app as device owner; otherwise Android screen pinning applies.
    Android 10+ does not allow auto-opening the UI after boot unless Kinetic is the HOME app.
11. **Odometer:** VESC odometer when connected, otherwise sum of recorded rides; maintenance items marked done in one basis are not converted to the other.
12. **Emulator:** this build machine has no KVM; the emulator ran with software CPU emulation (very slow, SystemUI ANRs). Map tiles could not be loaded there because of the sandbox's TLS proxy – map rendering is unverified until tested on a device.
13. **Auth token** is AES-GCM encrypted with an Android Keystore key; falls back to app-private plaintext only if the Keystore is unusable.
14. The APK is large (~63 MB debug) because MapLibre ships native libraries for 4 ABIs; use ABI splits for distribution.
19. **Code review 2026-11-25:** the 19 findings were fixed except: BLE write-callback race between chunks (low), per-CAN-controller backups (only the primary controller's limits are backed up; others get the same absolute limits via forwarding), JWT still in the WebSocket URL (now redacted from server logs; short-lived tickets planned).
20. **Language:** UI is German/English (system language). Messages generated in core logic (gate refusals, setup-check texts, spoken warnings, turn instructions) are still English.
21. Throttle is live during the short time between VESC power-up and the start of the vehicle script (relevant only while locked).
18. **Anti-theft lock** is a deterrent, not a certified immobilizer (see SECURITY.md). Lock/unlock is only possible from the cockpit phone (BLE); the second phone receives theft alerts but cannot lock remotely (deliberately no remote control path).
16. **kinetic.vescpkg** was built with VESC Tool 7.01 (compiled here with small Qt 6.4 compatibility patches) and verified structurally; it has not been loaded in an official VESC Tool or on a VESC.
17. PiP mini cockpit, voice warnings and dash tile selection were render-tested on the JVM only; check on a real phone.
15. LispBM: validated in the LispBM interpreter (lispBM 0.41, newer than some firmware builds) – not yet on a VESC. Re-uploading the script without a VESC power cycle makes already-reduced limits the new ceiling (documented).
22. **Firmware update over WiFi/BLE is not implemented** on purpose: a failed VESC flash can brick the controller. Use VESC Tool for firmware updates.
23. **Map-switch LED** (Stark handlebar hardware) has no VESC equivalent; not applicable.
24. **Airtime / g-force** come from the phone accelerometer. They are only plausible with the phone rigidly mounted on the vehicle; in a pocket/backpack values are unreliable. Labelled as phone-sensor values.
25. **FR/IT/ES/SV translations** were machine-made and need review by native speakers; some long labels may be truncated.
26. **Parental lock speed limit** needs correct wheel/gear data (VESC setup); without it only the power limit is available.
27. **Power display in PS/kW** in ride modes is derived from the backed-up `l_watt_max` of the controller, not a dyno value.
