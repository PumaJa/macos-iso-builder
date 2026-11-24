# Research raw: Stark VARG / VESC (2026-11-24)
Method: web search + page fetch. Anything not confirmed is marked "not verified". Search summaries are secondary; primary pages were not always readable.

## 1. Stark app / Arkenstone
- Features (from Stark marketing as relayed by press, not independently verified): power-curve tuning, engine braking (0-100% on SM), flywheel effect, traction control, per-mode custom settings; basic sliders plus an advanced 10-point power/regen curve. https://starkfuture.com/products/stark-varg-sm
- Traction control arrived as a free OTA update for MX/EX/SM ALPHA (June 2026), adjustable per power mode, 0-100%. https://www.motorcycle.com/bikes/news/stark-future-introduces-traction-control-for-the-varg-44666878 , https://electrek.co/2026/06/28/leading-electric-dirt-bike-stark-varg-lets-owners-download-a-new-trick/
- Anti-theft: handlebar key lock, in-app digital lock, built-in GPS tracking. https://starkfuture.com/products/stark-varg-sm
- OTA updates via Stark app: https://mxvice.com/?p=7527
- Power level 1 = ~10 hp (reviewer); dash shows power mode, speed, battery: https://www.cyclegear.com/common-tread/2026-stark-varg-sm-sci-fi-supermoto-review
- Arkenstone is an Android-based dash/phone; mapping apps get covered by it (forum): https://electricdirtriders.com/threads/displaying-gps-trail-mapping-app-on-stark-phone-while-riding.3912
- Group ride / telematics: NO official Stark group-ride feature found. Only a forum question about a "buddies" feature (that is Voyager Pro, not Stark). Telematics: GPS tracking is mentioned; details not verified.
- UI characteristics (colors, layout, fonts): not verified from any source. Needs screenshots from starkfuture.com / App Store (https://apps.apple.com/us/app/stark-app/id6449729436 returned 404 on fetch).
- Public developer API: none found (not verified to not exist). Official group-ride join for non-Stark vehicles: none found.
- Forum: Stark FAQ reportedly says only the Stark phone connects to the VARG; non-Stark phone claims unverified: https://electricdirtriders.com/threads/using-the-varg-app-on-a-non-stark-phone.3495/post-37624

## 2. "VargLink"
Not found. Searches returned no project/app with that name. Related community names: VargPilot (HUD/telemetry replacement app), Svag, "EX Custom iOS/Android App": https://electricdirtriders.com/goto/post?id=48349 , https://electricdirtriders.com/goto/post?id=49753 . Owner/URL/API of VargPilot: not verified.

## 3. Reverse-engineering projects
- https://github.com/b1naryth1ef/svag-telemetry-format , protobuf telemetry format for the third-party Svag app and Stark Varg; fields mirror Bluetooth data (battery_dc_bus /10 = V, battery_current, 100 cell groups in 1/10000 V). No license stated on page.
- No dedicated BLE protocol repo found. Forum threads (electricdirtriders.com, links above) discuss Svag / VargPilot.

## 4. VESC
- Changelog (https://github.com/vedderb/bldc/blob/master/CHANGELOG.md): 7.00 released 2026-05-15 (newest released); 7.01 TBD; 6.06.6 2025-11-01; 6.06 2025-08-18; 6.05 2024-08-19. So 6.05/6.06 are NOT current; 7.00 is. A "6.7" was not seen (not verified; likely does not exist). Note GitHub releases/tags pages show tags only up to 6.00 (https://github.com/vedderb/bldc/tags); 7.00 exists as branch release_7_00 (https://endless-sphere.com/sphere/goto/post?id=1896066).
- Repos: https://github.com/vedderb/bldc (GPL-3.0, https://github.com/vedderb/bldc), https://github.com/vedderb/vesc_tool (GPL-3.0), https://github.com/vedderb/vesc_pkg (GPL-3.0), https://github.com/vedderb/vesc_express (GPL-3.0; ESP32 family WiFi/BLE logger and IO board).
- BLE UUIDs: Nordic UART Service 6E400001-B5A3-F393-E0A9-E50E24DCCA9E, RX (write) 6E400002-..., TX (notify) 6E400003-... (https://docs.nordicsemi.com/ ; VESC forum post lists 6e400001 as UUID for VESC Tool BLE bridging: https://vesc-project.com/node/474 ). Exact constants in vesc_express source: not verified. HM-10 style modules (FFE0/FFE1) also used: not verified here.

## 5. Open-source Android VESC dashboards
- FreeSK8 mobile (Flutter, Android APK on Releases; iOS not distributed), GPL-3.0, supports VESC FW 5.1-7.00: https://github.com/FreeSK8/freesk8_mobile
- Float Control (iOS, App Store, paid) and Floaty (iOS; Android unverified): proprietary/closed as far as seen; license not verified. https://apps.apple.com/app/id1590924299 , https://apps.apple.com/us/app/id1671681675
- DIYWheel https://github.com/robdan7/DIYWheel (license not verified); Refloat https://github.com/bwees/refloat (VESC package, license not verified); float-view https://github.com/acheronfail/float-view (license not verified).
- "vesc-android", "pubmote": not found/verified in this research. VESC Tool itself (GPL-3.0, Qt) also runs on Android.
