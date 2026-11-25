# Kinetic OS Server

Self-hostable backend and web dashboard for the Kinetic OS Android cockpit (VESC electric vehicles).
Implements the contract in [`../API_DOCUMENTATION.md`](../API_DOCUMENTATION.md): REST + WebSocket,
PostgreSQL 16 + PostGIS, JWT auth, group rides, read-only telematics with geofence / battery / fault /
movement alerts, and a static dashboard served at `/`.

**Telematics is read-only.** There is no endpoint, WebSocket message or dashboard control that can command a motor.

## What you get

| Service | Purpose |
|---|---|
| `db` | `postgis/postgis:16-3.4`, data in the `db_data` volume |
| `api` | Node 22 / Fastify server + dashboard on port `8080` (`/data` volume holds vehicle photos) |
| `backup` | `pg_dump` every 24 h into `./backups` (14 kept) |
| `caddy` (profile `https`) | Optional reverse proxy with automatic Let's Encrypt HTTPS |

## Quick start (plain Docker)

```bash
cd kinetic-os/server
cp .env.example .env
# set JWT_SECRET (required) and change POSTGRES_PASSWORD
sed -i "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" .env
docker compose up -d --build
```

Open `http://<host>:8080/`, register an account, then point the Android app at `http://<host>:8080`
(or your HTTPS URL). Check health: `curl http://<host>:8080/api/v1/health` returns `{"ok":true,"db":true}`.

> Put the server behind HTTPS before exposing it to the internet; passwords and JWTs travel in requests.

### ZimaOS / CasaOS

1. Open the app store's **Custom Install** (the `+` button) and choose **Import** (docker-compose), or SSH in and
   copy the `server/` folder to e.g. `/DATA/AppData/kinetic-os/`.
2. Paste the contents of `docker-compose.yml`, or run `docker compose up -d --build` from that folder with a `.env` next to it.
3. Set `JWT_SECRET` and `POSTGRES_PASSWORD` in the environment section / `.env`.
4. If port 8080 is taken (CasaOS uses 80), set `API_PORT=8085` (any free port).
5. Backups land in `./backups` next to the compose file, so use a path under `/DATA/` and include it in your NAS backups.

### Synology (DSM 7.2+ Container Manager)

1. Copy the `server/` folder to a shared folder, e.g. `/volume1/docker/kinetic-os/`.
2. Container Manager -> **Project** -> Create -> set path to that folder; it picks up `docker-compose.yml`. Create a `.env` file in the same folder (File Station) with `JWT_SECRET` etc.
3. Build and start. Reach it at `http://<nas-ip>:8080` (set `API_PORT` if needed).
4. For HTTPS use DSM's **Login Portal -> Reverse Proxy** (source `https://kinetic.yourdomain:443`, destination `http://localhost:8080`) and enable WebSocket by adding the custom headers `Upgrade: $http_upgrade` and `Connection: $connection_upgrade` (Custom Header -> Create -> WebSocket). Set `TRUST_PROXY=1`.

## HTTPS and reverse proxies

WebSocket (`/ws`) must be proxied with upgrade support; the app uses `wss://<host>/ws?token=...`.
Set `TRUST_PROXY=1` whenever a proxy sits in front so rate limiting sees real client IPs.

### Built-in Caddy (easiest)

Point a DNS record at your server, forward ports 80/443, then in `.env`:

```
DOMAIN=kinetic.example.com
ACME_EMAIL=you@example.com
TRUST_PROXY=1
```

```bash
docker compose --profile https up -d
```

Caddy obtains and renews certificates automatically (state in the `caddy_data` volume). You can stop publishing
the plain port by removing the `ports:` entry of `api` in a `docker-compose.override.yml`.

### Nginx Proxy Manager

Add a Proxy Host: scheme `http`, forward host = the machine/container running `api` (or `api` if NPM shares the
compose network), port `8080`. Enable **Websockets Support**, request an SSL certificate on the SSL tab, force SSL.
Set `TRUST_PROXY=1`.

### Traefik

```yaml
  api:
    labels:
      - traefik.enable=true
      - traefik.http.routers.kinetic.rule=Host(`kinetic.example.com`)
      - traefik.http.routers.kinetic.entrypoints=websecure
      - traefik.http.routers.kinetic.tls.certresolver=letsencrypt
      - traefik.http.services.kinetic.loadbalancer.server.port=8080
```
(attach `api` to Traefik's network; WebSockets work without extra config.)

## Backups and restore

The `backup` service writes `./backups/kinetic-YYYYmmdd-HHMMSS.sql.gz` every `BACKUP_INTERVAL_HOURS` (default 24)
and keeps the newest `BACKUP_KEEP` (default 14). Run one on demand: `docker compose restart backup`
(the loop dumps immediately on start) or

```bash
docker compose exec db pg_dump -U kinetic --no-owner --clean --if-exists kinetic | gzip > manual.sql.gz
```

Restore (stop the API first so nothing writes while you restore):

```bash
docker compose stop api
gunzip -c backups/kinetic-20250101-030000.sql.gz | docker compose exec -T db psql -U kinetic -d kinetic
docker compose start api
```

Vehicle photos live in the `api_data` volume; back it up too:

```bash
docker run --rm -v kinetic-os_api_data:/data -v "$PWD":/out alpine tar czf /out/photos.tgz -C /data .
```

Also copy `.env` (especially `JWT_SECRET`: changing it logs everyone out) somewhere safe.

## Road routing (optional, OSRM)

The server can proxy a self-hosted [OSRM](https://project-osrm.org/) instance so clients get turn-by-turn road
routes without a third-party service. It is off by default (compose profile `routing`) and the OSRM container is
never published on the host: only the authenticated API endpoint exposes it.

```bash
./osrm/prepare.sh europe/liechtenstein            # downloads the Geofabrik extract and builds the routing graph
./osrm/prepare.sh europe/germany/bayern bicycle   # profile: car (default) | bicycle | foot
docker compose --profile routing up -d            # starts osrm next to db/api
```

- `prepare.sh` needs docker and curl/wget, writes to `./osrm-data/` (git-ignored) and is idempotent: rerunning with the
  same region/profile does nothing; a different region or profile rebuilds. `FORCE=1` rebuilds, `FORCE_DOWNLOAD=1`
  refetches the extract, `OSM_PBF_URL=...` uses another mirror. Region names are Geofabrik paths
  (<https://download.geofabrik.de/>). There is no motorcycle profile: use `car` (`motorcycle` is accepted as an alias).
- One OSRM instance serves one region and one profile; the API always asks for `driving`.
- Refresh the map by rerunning `FORCE_DOWNLOAD=1 ./osrm/prepare.sh <region>` and `docker compose restart osrm`.
- `OSRM_URL` (default `http://osrm:5000`) is where the API looks for OSRM; set it empty to disable the endpoint
  (it then answers 503 `routing_unavailable`). Without the profile running, the endpoint also answers 503.
- Approximate needs (graph building is the peak; serving needs roughly the final data size in RAM):

| Region | Download | Disk | RAM to prepare | RAM to serve |
|---|---|---|---|---|
| Liechtenstein / small city | < 5 MB | < 0.1 GB | < 0.5 GB | < 0.2 GB |
| Large country (Germany) | ~4 GB | ~25 GB | ~16-24 GB | ~8-12 GB |
| Europe | ~30 GB | ~200 GB | ~128 GB+ | ~64 GB |

  The planet is not recommended on small hardware. Pick the smallest region that covers your rides.
- **Attribution:** routing data derives from OpenStreetMap, (c) OpenStreetMap contributors, licensed under the
  [ODbL](https://opendatacommons.org/licenses/odbl/). Clients that show a route or map must display
  "(c) OpenStreetMap contributors".

### `GET /api/v1/route`

Requires `Authorization: Bearer <token>`. Rate limit: 60 requests/minute per client.

| Query | Description |
|---|---|
| `coords` | required, `lon,lat;lon,lat[;...]`, 2-25 points, lon -180..180, lat -90..90, plain decimals |
| `profile` | optional, only `driving` (default) |

The API calls `OSRM_URL/route/v1/driving/{coords}?overview=full&geometries=geojson&steps=true` and returns the OSRM
JSON unchanged (`code`, `routes[].distance` in metres, `routes[].duration` in seconds, `routes[].geometry` GeoJSON
LineString `[lon,lat]`, `routes[].legs[].steps[]`, `waypoints`).

| Status | Body |
|---|---|
| 200 | OSRM response |
| 400 | `{"error":"validation_error"\|"bad_request","message":...}` for invalid coords/profile; or OSRM's own error body (`{"code":"NoRoute"\|"NoSegment"\|"InvalidQuery",...}`) |
| 401 | `{"error":"unauthorized",...}` |
| 429 | `{"error":"rate_limited",...}` |
| 503 | `{"error":"routing_unavailable","message":...}` OSRM not configured, unreachable, timed out (10 s) or failing |

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | **required** (>= 16 chars) | HS256 signing secret. Generate with `openssl rand -hex 32` |
| `POSTGRES_PASSWORD` | `change-me-too` | Database password (compose only) |
| `DATABASE_URL` | set by compose | Connection string used by the API |
| `API_PORT` | `8080` | Published host port (compose only) |
| `PORT` / `HOST` | `8080` / `0.0.0.0` | Listen address inside the container |
| `DATA_DIR` | `/data` | Photos are stored in `$DATA_DIR/photos` |
| `TRUST_PROXY` | `0` | `1` when behind a reverse proxy |
| `DOMAIN`, `ACME_EMAIL` | - | Caddy (`https` profile) |
| `OSRM_URL` | `http://osrm:5000` | OSRM base URL for `GET /api/v1/route`; empty disables it |
| `BACKUP_INTERVAL_HOURS`, `BACKUP_KEEP` | `24`, `14` | Backup schedule / retention |

Migrations in `migrations/*.sql` are applied automatically at start (tracked in `schema_migrations`).

## Behaviour notes

- **online** (vehicles) = last state received < 60 s ago **and** `vescConnected`. Friends/group members are
  online while they hold a WebSocket connection.
- **Alerts** are generated when states are ingested (REST batch or WebSocket `vehicle_state`):
  geofence enter/exit (PostGIS `ST_DWithin` on `geography`), low battery (`lowBatteryPct`, re-arms after +3 %),
  new fault code, maintenance (`maintenanceKm` since last service), and movement (> 50 m from the parked position while
  `vescConnected=false` or the `parked` flag is set, only if `moveAlert` is true).
- Simulated states (`source:"simulated"`) are stored and flagged; their alerts are prefixed `[SIMULATION]` and the
  dashboard shows a SIMULATION badge.
- Rate limits: 600 req/min per client on the API, 20/min on login/register. Position messages are capped at 5 Hz per socket.

## Development

```bash
npm install
npx tsc --noEmit
npx vitest run                       # unit tests; the integration test is skipped without a database
# integration test against real PostGIS:
docker run -d --name kpg -e POSTGRES_PASSWORD=pw -p 55432:5432 postgis/postgis:16-3.4
TEST_DATABASE_URL=postgres://postgres:pw@localhost:55432/postgres npx vitest run test/integration.test.ts
# run locally
JWT_SECRET=$(openssl rand -hex 32) DATABASE_URL=postgres://postgres:pw@localhost:55432/postgres DATA_DIR=./data npm run dev
```
