# Kinetic OS – Server API (v1)

Base URL: `https://<host>/api/v1` · WebSocket: `wss://<host>/ws?token=<JWT>`
Auth: `Authorization: Bearer <JWT>` (HS256, 30 days). All bodies JSON, UTF-8. Timestamps = epoch milliseconds (UTC).
Errors: `{"error":"<code>","message":"..."}` with HTTP 400/401/403/404/409/429/500.

## Auth
| Method | Path | Body | Response |
|---|---|---|---|
| POST | /auth/register | `{username, password(min 8), displayName}` | `201 {token, user}` |
| POST | /auth/login | `{username, password}` | `{token, user}` |
| GET | /me | – | `user` |

`user = {id, username, displayName, createdAt}`

## Friends
| GET | /friends | – | `[{id, username, displayName, online, lastSeen}]` |
| POST | /friends/requests | `{username}` | `201 {id, status:"pending"}` |
| GET | /friends/requests | – | `[{id, from:user, to:user, status}]` |
| POST | /friends/requests/:id/accept | – | `{id, status:"accepted"}` |
| DELETE | /friends/:userId | – | `204` |

## Groups (group rides)
| POST | /groups | `{name, visibility:"public"|"private"}` | `201 group` |
| GET | /groups | – | my groups `[group]` |
| GET | /groups/public | – | `[group]` |
| POST | /groups/join | `{code}` | `group` |
| GET | /groups/:id | – | `group` incl. `members` |
| POST | /groups/:id/invite | `{username}` | `201` (adds invitation) |
| GET | /invitations | – | `[{id, group, from}]` |
| POST | /invitations/:id/accept | – | `group` |
| DELETE | /groups/:id/members/me | – | `204` (leave) |
| DELETE | /groups/:id | – | `204` (owner only) |
| PUT | /groups/:id/route | `{name, gpx}` | `200` shared route |
| GET | /groups/:id/route | – | `{name, gpx, updatedAt}` or 404 |

`group = {id, name, code(6 chars A-Z0-9), visibility, ownerId, createdAt, members?:[{userId, displayName, role:"owner"|"member", online}]}`
Only members can read a private group. Codes are random, unique, case-insensitive.

## Vehicles
| GET | /vehicles | – | `[vehicle]` |
| PUT | /vehicles/:id | `{name, type, make, model, ...free fields}` | `vehicle` (upsert; id = client UUID) |
| POST | /vehicles/:id/photo | multipart `file` (jpeg/png ≤ 5 MB) | `{photoUrl}` |
| GET | /vehicles/:id/photo | – | image (members of shared groups / owner) |
| DELETE | /vehicles/:id | – | `204` |

## Telematics (owner-only unless shared)
| POST | /telematics/:vehicleId/state | `{states:[vehicleState]}` (batch ≤ 500) | `202` |
| GET | /telematics/:vehicleId/state | – | `{state: vehicleState, receivedAt, online}` |
| GET | /telematics/:vehicleId/history?from&to&limit | – | `[vehicleState]` |
| GET | /telematics/:vehicleId/geofences | – | `[geofence]` |
| POST | /telematics/:vehicleId/geofences | `{name, lat, lon, radiusM, alertOn:"exit"|"enter"|"both"}` | `201 geofence` |
| DELETE | /telematics/:vehicleId/geofences/:gid | – | `204` |
| GET | /telematics/:vehicleId/alerts?since | – | `[alert]` |
| PUT | /telematics/:vehicleId/settings | `{lowBatteryPct, maintenanceKm, moveAlert:boolean}` | settings |

`vehicleState = {ts, lat?, lon?, alt?, speedKmh?, headingDeg?, socPct?, voltage?, powerW?, tempMosC?, tempMotorC?, odometerKm?, faultCode?, vescConnected:boolean, charging?:boolean, source:"measured"|"simulated"}`
`online` = last state received < 60 s ago **and** `vescConnected`. Simulated states are stored but flagged.
`alert = {id, ts, kind:"geofence_exit"|"geofence_enter"|"low_battery"|"fault"|"movement"|"maintenance", message, state}`

## Rides
| POST | /rides | `{id, vehicleId, startedAt, endedAt, distanceM, maxSpeedKmh, energyWh, gpx?, summary?}` | `201` |
| GET | /rides?vehicleId | – | `[ride]` |
| GET | /rides/:id | – | ride incl. gpx |

## Laps / leaderboards
| POST | /tracks | `{name, startLine:{lat1,lon1,lat2,lon2}, sectors:[...]}` | `201 track` |
| GET | /tracks | – | `[track]` |
| POST | /tracks/:id/laps | `{lapMs, sectorsMs:[], vehicleId, gpsHz, ts}` | `201` |
| GET | /tracks/:id/leaderboard | – | `[{userId, displayName, bestLapMs, vehicleName}]` |

## WebSocket protocol
Text frames, JSON `{type, ...}`. Server sends `{"type":"ping"}` every 20 s; client answers `{"type":"pong"}`; 60 s without traffic → disconnect.

Client → server:
- `{type:"join_group", groupId}` / `{type:"leave_group", groupId}`
- `{type:"position", groupId, lat, lon, alt?, speedKmh?, headingDeg?, ts, telemetry?:{socPct?, powerW?}}` (rate-limited to 5 Hz)
- `{type:"chat", groupId, text}` (≤ 1000 chars)
- `{type:"voice", groupId, mime:"audio/mp4", durationMs, dataBase64}` (≤ 60 s, ≤ 1 MB)
- `{type:"help_request", groupId, lat, lon, message}`
- `{type:"subscribe_vehicle", vehicleId}` (owner only – remote monitoring)
- `{type:"vehicle_state", vehicleId, state: vehicleState}` (cockpit → server; also persisted)

Server → client:
- `{type:"member_position", groupId, userId, displayName, vehicleName?, ...position fields}`
- `{type:"presence", groupId, userId, online}`
- `{type:"chat"|"voice"|"help_request", groupId, userId, displayName, ts, ...}`
- `{type:"route_updated", groupId}`
- `{type:"vehicle_state", vehicleId, state}` / `{type:"alert", vehicleId, alert}`
- `{type:"error", error, message}`

There is **no** endpoint for motor control. Telematics is read-only by design.
