CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username text NOT NULL,
  display_name text NOT NULL,
  password_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz
);
CREATE UNIQUE INDEX users_username_lower ON users (lower(username));

CREATE TABLE friendships (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  from_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (from_id <> to_id)
);
CREATE UNIQUE INDEX friendships_pair ON friendships (LEAST(from_id, to_id), GREATEST(from_id, to_id));

CREATE TABLE groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  code char(6) NOT NULL,
  visibility text NOT NULL CHECK (visibility IN ('public','private')),
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX groups_code_upper ON groups (upper(code));

CREATE TABLE group_members (
  group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('owner','member')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user ON group_members (user_id);

CREATE TABLE invitations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  from_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id, to_id)
);

CREATE TABLE group_routes (
  group_id uuid PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  name text NOT NULL,
  gpx text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vehicles (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name text NOT NULL,
  type text,
  make text,
  model text,
  extra jsonb NOT NULL DEFAULT '{}'::jsonb,
  photo_ext text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX vehicles_owner ON vehicles (owner_id);

CREATE TABLE vehicle_settings (
  vehicle_id uuid PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
  low_battery_pct real NOT NULL DEFAULT 20,
  maintenance_km real NOT NULL DEFAULT 0,
  move_alert boolean NOT NULL DEFAULT false,
  parked boolean NOT NULL DEFAULT false,
  -- alert-engine bookkeeping
  low_battery_active boolean NOT NULL DEFAULT false,
  last_fault integer,
  anchor geography(Point, 4326),
  last_maint_km real
);

CREATE TABLE telemetry_latest (
  vehicle_id uuid PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
  state jsonb NOT NULL,
  state_ts timestamptz NOT NULL,
  received_at timestamptz NOT NULL
);

CREATE TABLE telemetry_history (
  id bigserial PRIMARY KEY,
  vehicle_id uuid NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
  ts timestamptz NOT NULL,
  state jsonb NOT NULL,
  pos geography(Point, 4326)
);
CREATE INDEX telemetry_history_vehicle_ts ON telemetry_history (vehicle_id, ts);
CREATE INDEX telemetry_history_pos ON telemetry_history USING gist (pos);

CREATE TABLE geofences (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  vehicle_id uuid NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
  name text NOT NULL,
  lat double precision NOT NULL,
  lon double precision NOT NULL,
  center geography(Point, 4326) NOT NULL,
  radius_m real NOT NULL CHECK (radius_m > 0),
  alert_on text NOT NULL CHECK (alert_on IN ('exit','enter','both')),
  inside boolean,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX geofences_vehicle ON geofences (vehicle_id);
CREATE INDEX geofences_center ON geofences USING gist (center);

CREATE TABLE alerts (
  id bigserial PRIMARY KEY,
  vehicle_id uuid NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
  ts timestamptz NOT NULL,
  kind text NOT NULL,
  message text NOT NULL,
  state jsonb NOT NULL
);
CREATE INDEX alerts_vehicle_ts ON alerts (vehicle_id, ts DESC);

CREATE TABLE rides (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  vehicle_id uuid NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
  started_at timestamptz NOT NULL,
  ended_at timestamptz NOT NULL,
  distance_m double precision NOT NULL,
  max_speed_kmh double precision NOT NULL,
  energy_wh double precision NOT NULL,
  gpx text,
  summary jsonb
);
CREATE INDEX rides_vehicle ON rides (vehicle_id, started_at DESC);

CREATE TABLE tracks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  creator_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name text NOT NULL,
  start_line jsonb NOT NULL,
  sectors jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE laps (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  track_id uuid NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  vehicle_id uuid REFERENCES vehicles(id) ON DELETE SET NULL,
  lap_ms integer NOT NULL,
  sectors_ms jsonb NOT NULL DEFAULT '[]'::jsonb,
  gps_hz real,
  ts timestamptz NOT NULL
);
CREATE INDEX laps_track_best ON laps (track_id, lap_ms);
