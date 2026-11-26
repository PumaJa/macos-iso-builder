# User wishes: Stark VARG app / Arkenstone dash and VESC dashboards

Research date: 2026-11-25.

## Method and honesty notes

- Sources were found with web search. Result snippets were summarised by the search tool.
- **electricdirtriders.com (EDR) blocked page fetching.** Every WebFetch returned only a "Verify You Are Human" shell, so no EDR thread was opened. All EDR rows rest on **search snippets**. They are marked `snippet-only, not verified`.
- vesc-project.com, endless-sphere and electric-skateboard.builders (ESB) pages were also only seen as search snippets. They were not opened either.
- Reddit (r/StarkVarg, r/ElectricMotorcycles and others) produced no usable results, so no Reddit claims are made.
- Strength scale: 1 = single mention, 2 = a few mentions or one long thread, 3 = recurring across threads or communities. The scale is a judgement call from a small sample. It is **not** a measured frequency.
- Posts date from roughly 2019 to 2025. Some items may already be fixed.
- Community key: **EDR** = Stark owners (Electric Dirt Riders forum). **VESC** = vesc-project.com. **ES** = endless-sphere. **ESB** = electric-skateboard.builders / esk8.news. **Press** = reviews.

## 1. Wish table

| # | Wish | Community | Strength (1-3) | Source URLs |
|---|------|-----------|----------------|-------------|
| 1 | Stark app should run in the background and come forward only for faults or warnings, so a map or nav app can stay on top | EDR | 3 | https://electricdirtriders.com/goto/post?id=41659 ; https://electricdirtriders.com/threads/displaying-gps-trail-mapping-app-on-stark-phone-while-riding.3912 (snippet-only, not verified) |
| 2 | Map/trail overlay or Picture-in-Picture over the dash (a rider built one) | EDR | 2 | https://electricdirtriders.com/goto/post?id=41659 (snippet-only, not verified) |
| 3 | Stop the app stealing focus on every bike input and dragging the rider out of a nav app | EDR | 3 | https://electricdirtriders.com/threads/i-finally-got-my-stark%E2%80%A6pros-and-cons.3464/page-2 (snippet-only, not verified) |
| 4 | Reliable ride recording, and recording that survives switching apps | EDR | 3 | https://electricdirtriders.com/goto/post?id=46190 ; https://electricdirtriders.com/goto/post?id=38972 (snippet-only, not verified) |
| 5 | Odometer and trip data visible in the app, not only on the dash | EDR | 2 | https://electricdirtriders.com/goto/post?id=46190 (snippet-only, not verified) |
| 6 | Automatic ride recording (the custom app lacked it) | EDR | 2 | https://electricdirtriders.com/threads/ex-custom-ios-android-app.4376/page-5 (snippet-only, not verified) |
| 7 | Exporting rides to a viewer with map and telemetry charts | EDR | 2 | https://electricdirtriders.com/threads/ex-custom-ios-android-app.4376/page-8 (snippet-only, not verified) |
| 8 | Lap timing (Stark's "TRACKS" lap timing thread exists; content not read) | EDR | 1 | https://electricdirtriders.com/threads/tracks-starks-new-lap-timing-software.5040/ (title only, not verified) |
| 9 | A third column on the mode screen: HP, engine braking and throttle sensitivity together | EDR | 1 | https://electricdirtriders.com/threads/i-finally-got-my-stark%E2%80%A6pros-and-cons.3464/page-2 (snippet-only, not verified) |
| 10 | Current mode readable at a glance. The phone sits too far back to glance down at it | EDR | 2 | https://electricdirtriders.com/threads/i-finally-got-my-stark%E2%80%A6pros-and-cons.3464/page-2 (snippet-only, not verified) |
| 11 | Full fault display (the app reportedly never shows the motor-overheat fault the VCU defines) | EDR | 2 | https://electricdirtriders.com/goto/post?id=53386 ; https://electricdirtriders.com/goto/post?id=54800 (snippet-only, not verified) |
| 12 | RPM, HP and regen readouts on the dashboard | EDR | 1 | https://electricdirtriders.com/threads/ex-custom-ios-android-app.4376/page-6 (snippet-only, not verified) |
| 13 | A reliable "lock bike" that does not depend on the phone staying connected | EDR | 2 | https://electricdirtriders.com/goto/post?id=46729 (snippet-only, not verified) |
| 14 | Fully customizable HUD and themes (the community VargPilot app offers them) | EDR | 2 | https://electricdirtriders.com/goto/post?id=54800 ; https://electricdirtriders.com/goto/post?id=54824 (snippet-only, not verified) |
| 15 | Telemetry logging with graphing, plus a remote debugging server | EDR | 1 | https://electricdirtriders.com/goto/post?id=54800 (snippet-only, not verified) |
| 16 | An open or stable third-party API, because community apps fear Stark locking them out | EDR | 2 | https://electricdirtriders.com/goto/post?id=54824 ; https://electricdirtriders.com/threads/ex-custom-ios-android-app.4376/page-17 (snippet-only, not verified) |
| 17 | Use the Stark app on a normal phone, not only the Stark phone (a thread exists; content not read) | EDR | 1 | https://electricdirtriders.com/threads/varg-phone-is-garbage.3921/ (title only, not verified) |
| 18 | Better throttle response and sensitivity at low-power modes (about 45 hp and below) | EDR | 2 | https://electricdirtriders.com/threads/i-finally-got-my-stark%E2%80%A6pros-and-cons.3464/page-2 (snippet-only, not verified) |
| 19 | Power curves based on wheel speed that survive wheel slip in motocross | EDR | 1 | Search-tool summary of an EDR owner thread. See https://electricdirtriders.com/goto/post?id=42132 (snippet-only, not verified) |
| 20 | Foot brake as a regen brake controller; engine-sound or HP-map effects | EDR | 1 | https://electricdirtriders.com/goto/post?id=53926 ; https://electricdirtriders.com/goto/post?id=53830 (snippet-only, not verified) |
| 21 | Fine-grained mode tuning kept: up to 100 saved modes, regen 0-100%, traction 0-100%, torque curves (a liked feature that riders want kept or expanded) | Press, EDR | 3 | https://www.revzilla.com/common-tread/stark-varg-electric-motocross-first-ride ; https://electrek.co/2025/06/26/stark-varg-ex-review-our-first-look-at-the-80-hp-street-legal-dirt-bike/ (search snippet, not opened) |
| 22 | Fewer permission-prompt nags after app updates | EDR | 1 | https://electricdirtriders.com/goto/post?id=54800 (snippet-only, not verified) |
| 23 | Android auto-connect to the last Bluetooth VESC, then auto-start logging | VESC | 2 | https://vesc-project.com/taxonomy/term/7 (feature-request index; snippet-only, not verified) |
| 24 | Total odometer persisted across sessions in VESC Tool | VESC | 1 | https://vesc-project.com/taxonomy/term/7 (snippet-only, not verified) |
| 25 | Phone as the programming tool over USB-OTG, with no laptop needed | VESC | 2 | https://vesc-project.com/taxonomy/term/7 ; https://vesc-project.com/node/1852 (snippet-only, not verified) |
| 26 | A dedicated real-time data page for an e-motorcycle dashboard | VESC | 1 | https://vesc-project.com/taxonomy/term/7 (title only, not verified) |
| 27 | Config tabs that stay visible while disconnected | VESC | 1 | https://vesc-project.com/taxonomy/term/7 (snippet-only, not verified) |
| 28 | Voltage, range or capacity alerts, including audio alerts | ESB | 3 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888 (snippet-only, not verified) |
| 29 | Full-ride logging, not just min/max, with CSV export for dual VESCs and min/max/avg stats | ESB | 3 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888?page=17 (snippet-only, not verified) |
| 30 | Logging that survives Android battery optimisation (background logging) | ESB | 2 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888 (snippet-only, not verified) |
| 31 | Smartwatch (Android Wear) speed and battery display | ESB | 1 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888 (snippet-only, not verified) |
| 32 | Dark theme (later added) and BLE that works without location permission | ESB | 2 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888 (snippet-only, not verified) |
| 33 | Stability before features: apps that connect reliably | ESB, VESC | 3 | https://electric-skateboard.builders/t/vesc-monitor-android-app/20888 ; https://vesc-project.com/node/1852 (snippet-only, not verified) |
| 34 | Accurate battery % and range (VESC-derived range is off by 4x or more in reports) | ES, ESB, VESC | 3 | https://endless-sphere.com/sphere/threads/battery-capacity-display-that-actually-is-accurate.117649/latest ; https://electric-skateboard.builders/t/is-the-vesc-4-12-energy-measurements-reliable/33261 (snippet-only, not verified) |
| 35 | A coulomb-counting or BMS-fed state of charge, because voltage-based SoC fails on flat LiFePO4 curves | ES | 2 | https://endless-sphere.com/sphere/threads/battery-capacity-display-that-actually-is-accurate.117649/latest (snippet-only, not verified) |
| 36 | A display that is readable in direct sunlight (white on black reported OK) | ES, ESB | 2 | https://endless-sphere.com/sphere/threads/voyage-mia-display-for-bafang.128323/latest ; https://electric-skateboard.builders/t/davega-battery-monitor-odometer-speedometer/71509?page=9 (snippet-only, not verified) |
| 37 | Larger secondary numbers (they are too small to read while riding) | ESB | 1 | https://electric-skateboard.builders/t/davega-battery-monitor-odometer-speedometer/71509?page=9 (snippet-only, not verified) |
| 38 | GPS speed as an alternative or fallback to motor-derived speed | ES | 2 | https://endless-sphere.com/sphere/threads/a-new-display-speedometer-gps-map-for-vesc-controllers.131313/ ; https://endless-sphere.com/sphere/threads/voyage-mia-display-for-bafang.128323/latest (snippet-only, not verified) |
| 39 | Show battery current or power instead of speed (off-road priority); watts shown | ES | 2 | https://endless-sphere.com/sphere/threads/voyage-mia-display-for-bafang.128323/latest (snippet-only, not verified) |
| 40 | Map on a handlebar or dash display (OpenStreetMap, GPS trail) | ES | 2 | https://endless-sphere.com/sphere/threads/super-vesc-display-advanced-telemetry-display-for-vesc-based-ev-projects.129327/latest (snippet-only, not verified) |
| 41 | A touchscreen display that is also a BLE bridge to VESC Tool, plus ride stats | ES | 1 | https://endless-sphere.com/sphere/threads/super-vesc-display-advanced-telemetry-display-for-vesc-based-ev-projects.129327/latest (snippet-only, not verified) |
| 42 | Handlebar mounting on 22 mm bars (the common ebike standard) | ES | 1 | https://endless-sphere.com/sphere/threads/svd-simple-vesc-display.121335/ (snippet-only, not verified) |
| 43 | A safe throttle or motor control on touch (no slider in mobile VESC Tool; seen as a safety issue) | VESC | 1 | https://vesc-project.com/node/1852 (snippet-only, not verified) |
| 44 | Ride-log viewer and replay tools; HUD overlays from logs for video | ESB, PEV | 2 | https://awesome.ecosyste.ms/projects/github.com%2Facheronfail%2Ffloat-view (search listing only, not verified) |
| 45 | Hardware-independent always-on logging (FreeSK8 needs a Robogotchi logger for this) | ESB | 1 | https://apps.apple.com/us/app/freesk8-mobile/id1534050181 ; https://github.com/FreeSK8/freesk8_mobile (store page via search, not opened) |
| 46 | Setup and tuning in the same app as monitoring (Float Control says it is not a setup replacement) | ESB | 1 | https://apps.apple.com/us/app/id1590924299 (store description via search, not opened) |
| 47 | Pairing that works across VESC Tool versions (error "device not paired to your local version") | VESC | 1 | https://vesc-project.com/node/4006 (snippet-only, not verified) |

Rows 2, 8, 17 and 26 come from titles or very short snippets. Treat them as low-confidence.

## 2. What users like about the Stark app UI/UX

Concepts only. No assets copied. All of this comes from press and forum snippets, not from hands-on use.

- **Deep tuning in one place.** Riders can build many ride modes and keep a handful active on the bike. Per mode they set peak power, regen braking, traction control and, on the paid tier, torque curves. Reviewers call this as much adjustability as a modern combustion bike offers.
- **Mode as the central object.** The mode list is the core screen. A mode is a named bundle of parameters, shown in columns, so a rider can compare modes side by side.
- **Phone as dashboard, docked.** The phone docks on the bike. The Stark app starts automatically on docking and takes priority while the bike is engaged. Fans find this convenient and reliable. One owner called it "rock solid".
- **Rugged, waterproof hardware.** The dedicated phone is waterproof, shockproof and ruggedised. Owners value that it is purpose-built.
- **Telemetry after the ride.** Press mentions lap times, map data, speed, G-force, airtime and power consumption.
- **Crawl and walk modes.** Forward and reverse walk modes help when loading the bike. Crawl modes help in technical riding.
- **Fast, practical charging info.** One owner liked 220 V charging speed and range that held up over two hours of single track. The source is a general bike review, not the app.
- **Community extensibility.** People use the platform enough to build alternatives: Svag, an "EX Custom" app, and VargPilot. That shows demand for personalisation. VargPilot's pitch (HUD and theme editor, graphing, remote debugging) shows what the community values.

## 3. Pain points

- **App takes over the screen.** It jumps to the foreground on any bike input. Riders lose navigation, and split-screen hides critical data. (EDR, rows 1 and 3)
- **Ride recording is unreliable.** Rides are missed, and switching apps cancels recording, including a trail-mapping session. The odometer is visible only on the dash. (EDR, rows 4 and 5)
- **Faults not fully surfaced.** The app reportedly omits at least one fault the VCU defines, the motor-overheat flag. (EDR, row 11)
- **Lock depends on Bluetooth.** If the phone leaves with the rider, the link drops and the lock state is unreliable. (EDR, row 13)
- **Mode screen readability.** The phone sits far back, so the current mode is hard to read at a glance. (EDR, row 10)
- **Throttle feel in low-power modes.** Owners report the front end is hard to lift at about 45 hp and below, and feel throttle sensitivity is backwards across modes. This is a bike-tuning complaint, but it surfaces through the mode UI. (EDR, row 18)
- **Phone-as-dashboard criticism.** One thread is titled "Varg Phone is Garbage". Snippets mention scaling problems, connection trouble, Wi-Fi disconnects and battery drain. The content was not read, and other owners report no problems. (EDR, rows 17 and 22)
- **Permission prompts after updates.** A "some features will not work" message confused at least one owner. (EDR, row 22)
- **Community-app fragility.** Third-party apps depend on Stark not closing access. Installing VargPilot disables the stock app. A pending firmware update appeared for one user, cause unknown, and the developer advises avoiding firmware updates. The custom EX app reportedly stopped working with newer stock app versions, and a new connection method needs the "sold date". (EDR, row 16)
- **VESC Tool mobile connection failures.** Reports include "could not read firmware version", a Bluetooth module found but no connect, and HM-10-style modules not working (an nRF-based module is needed). Swapped TX/RX wiring was one fix, and a Mac crash happened on Bluetooth connect. (VESC, row 33)
- **VESC Tool mobile limits.** There is no motor slider on mobile, the phone cannot easily do USB-OTG tuning, and a foreground app can block the phone's media buttons and drain its battery. (VESC, rows 25 and 43)
- **Range and battery estimates.** A VESC Tool range of about 109 miles against 20-28 real was reported. Two VESCs on CAN showed different values, 150 and 80 miles. VESC voltage can read about 0.5 V low, and one app showed 85% on a full pack. (ES, ESB, VESC, rows 34 and 35)
- **Logging gaps in community apps.** The VESC Monitor app's logs record only min/max, which one user found unusable. Background logging needs Android battery-optimisation exclusion. (ESB, rows 29 and 30)
- **Small text, glare and sunlight.** The Davega-style display was readable in sun, but secondary numbers were too small to read while riding. (ESB, rows 36 and 37)

## 4. Gaps in this research

- No EDR thread was actually opened (verification wall). A human reading the threads directly, or a logged-in session, would give real counts.
- Reddit, YouTube comments and app-store review text were not retrieved. The Float Control, Floaty and FreeSK8 store pages were found, but no written reviews were available.
- No evidence was found on glove use, phone-mount heat, anti-theft beyond the lock issue, Strava export, group ride, offline maps, or custom power-curve editing UX beyond the Stark mode tuning. These remain open questions, not confirmed non-wishes.
- The Voyager display and Svag were not found in any source beyond a mention of Svag stalling in an EDR snippet.

## Decisions (what was built, what not, and why) – 2026-11-25

| Wish | Decision | Implementation |
|---|---|---|
| Nav/map app in front; cockpit only when needed | ✅ built | Picture-in-picture mini cockpit when leaving the app (speed, power, SoC, mode, last warning); critical warnings raise a max-priority notification that reopens the cockpit |
| Don't grab the screen on every input | ✅ already the case | The app never steals focus; only critical warnings notify |
| Reliable recording across app switches | ✅ already built | Foreground service, auto-resume of interrupted rides |
| Show all faults incl. motor overheat, warn *before* cut-off | ✅ built | `WarningEngine`: motor/FET temperature early warnings with hysteresis, battery steps 30/20/10/5 %, cell imbalance, faults, link loss while moving; history on the Telemetry screen |
| Spoken / audible alerts | ✅ built | TextToSpeech + vibration (settings toggles) |
| Mode readable at a glance (+ engine brake / throttle response) | ✅ built | Large colour-coded mode badge with regen % and throttle character; on-vehicle derating state |
| Customisable HUD / themes | ✅ built | Choose up to 6 dash tiles from 17, primary value (speed / power / battery current), new SUN theme for direct sunlight |
| Accurate battery % and range (LiFePO4 flat curve) | ✅ built | Chemistry NMC/LFP/LTO, coulomb counting anchored at rest voltage, rolling 5 km consumption for range |
| Odometer / trip / auto-logging, CSV export, lap timing, map + charts | ✅ already built (odometer from VESC added) | – |
| Open, stable interface | ✅ | GPL-3.0, documented REST/WS API and on-vehicle protocol |
| Auto-connect to last VESC | ✅ already built | – |
| Lock that works without the phone | ⏸ not built | Would have to cut motor output from a LispBM script – safety-critical, needs hardware validation first. Proposed design is in TASKS.md |
| Full motor tuning from the phone (USB) | ❌ not built (by design) | Writing full MCCONF is firmware-specific and can damage hardware. Instead: read-only VESC Setup check with fixes + drivetrain import; ride modes only reduce limits |
| "Exactly like the Stark app" | ❌ not built | Copying a proprietary UI (assets, layout, fonts) is not permitted. The *concepts* users like (big numerals, at-a-glance mode, quick mode switch, map + cockpit) are implemented in an original design |
