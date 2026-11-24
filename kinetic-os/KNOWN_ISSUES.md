# Known issues & limitations

1. **No hardware validation.** Everything touching a real VESC (BLE/USB/TCP transports, ride-mode writes, LispBM) is only tested
   against the protocol simulator. Treat as experimental; test on a stand with the wheel off the ground.
2. **Stark compatibility:** no authorised interface → no joining Stark group rides, no Stark accounts, no display of Stark riders
   unless they use Kinetic. "VargLink" could not be found.
3. **Throttle curve / engine character / engine braking** are modelled and previewed, but the VESC executes its own ADC app
   throttle mapping. Executing custom curves requires a LispBM throttle controller, which is intentionally not shipped
   unvalidated. Approximate engine characters are illustrative only.
4. **Throttle position** is not reported by `COMM_GET_VALUES`; shown as unavailable (could be added via `COMM_GET_DECODED_ADC`).
5. **Motor power** is an estimate (duty × V × I_motor); efficiency is not shown.
6. **USB:** only CDC-ACM (VESC native USB, CDC USB-UART bridges). CP210x/CH34x/FTDI adapters are not supported.
7. **Navigation:** route following along GPX/planned straight segments; no road routing/turn-by-turn.
8. **Video overlay:** in-app synchronised preview + SRT export; no burned-in MP4 export.
9. **Telematics alerts** are shown in app/dashboard; no push notifications yet. Location when the phone is off needs a GPS tracker.
10. **Kiosk mode:** full lock-task requires provisioning the app as device owner; otherwise Android screen pinning applies.
    Android 10+ does not allow auto-opening the UI after boot unless Kinetic is the HOME app.
11. **Odometer** is the sum of recorded real rides (VESC `odometer` from GET_VALUES_SETUP not yet used).
12. **Emulator:** this build machine has no KVM; UI was verified with Robolectric (JVM) rendering instead of an emulator.
13. **Auth token** stored in app-private DataStore (not Keystore-encrypted).
14. The APK is large (~63 MB debug) because MapLibre ships native libraries for 4 ABIs; use ABI splits for distribution.
15. LispBM: re-uploading the script without a VESC power cycle makes already-reduced limits the new ceiling (documented).
