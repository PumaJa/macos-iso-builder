import type { Pool, PoolClient } from 'pg';
import { evaluateState, type AlertDraft, type FenceCheck, type Tracking } from '../logic/alerts';
import type { VehicleState } from '../logic/validation';

export interface StoredAlert extends AlertDraft {
  id: number;
  state: VehicleState;
}

export interface IngestResult {
  alerts: StoredAlert[];
  latest: VehicleState;
  receivedAt: number;
}

/**
 * Persist a batch of states (history + latest) and run the alert engine.
 * Geofence membership is evaluated by PostGIS (ST_DWithin on geography).
 */
export async function ingestStates(pool: Pool, vehicleId: string, states: VehicleState[], now = Date.now()): Promise<IngestResult> {
  const sorted = [...states].sort((a, b) => a.ts - b.ts);
  const client: PoolClient = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('INSERT INTO vehicle_settings (vehicle_id) VALUES ($1) ON CONFLICT DO NOTHING', [vehicleId]);
    const s = (
      await client.query(
        `SELECT low_battery_pct, maintenance_km, move_alert, parked, low_battery_active, last_fault, last_maint_km,
                ST_Y(anchor::geometry) AS alat, ST_X(anchor::geometry) AS alon
           FROM vehicle_settings WHERE vehicle_id = $1 FOR UPDATE`,
        [vehicleId],
      )
    ).rows[0];
    const fenceRows = (await client.query('SELECT id, name, alert_on, inside FROM geofences WHERE vehicle_id = $1 FOR UPDATE', [vehicleId])).rows;
    let tracking: Tracking = {
      lowBatteryActive: s.low_battery_active,
      lastFault: s.last_fault,
      anchor: s.alat != null ? { lat: s.alat, lon: s.alon } : null,
      lastMaintKm: s.last_maint_km,
      fenceInside: Object.fromEntries(fenceRows.map((f) => [f.id, f.inside])),
    };
    const settings = { lowBatteryPct: s.low_battery_pct, maintenanceKm: s.maintenance_km, moveAlert: s.move_alert, parked: s.parked };

    const alerts: StoredAlert[] = [];
    for (const st of sorted) {
      const hasPos = st.lat !== undefined && st.lon !== undefined;
      await client.query(
        `INSERT INTO telemetry_history (vehicle_id, ts, state, pos)
         VALUES ($1, to_timestamp($2 / 1000.0), $3::jsonb,
                 CASE WHEN $4::boolean THEN ST_SetSRID(ST_MakePoint($5, $6), 4326)::geography END)`,
        [vehicleId, st.ts, JSON.stringify(st), hasPos, st.lon ?? 0, st.lat ?? 0],
      );
      let fences: FenceCheck[] = [];
      if (hasPos && fenceRows.length) {
        const r = await client.query(
          `SELECT id, name, alert_on,
                  ST_DWithin(center, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography, radius_m) AS inside
             FROM geofences WHERE vehicle_id = $3`,
          [st.lon, st.lat, vehicleId],
        );
        fences = r.rows.map((f) => ({ id: f.id, name: f.name, alertOn: f.alert_on, inside: f.inside }));
      }
      const res = evaluateState(st, settings, tracking, fences);
      tracking = res.tracking;
      for (const a of res.alerts) {
        const ins = await client.query(
          `INSERT INTO alerts (vehicle_id, ts, kind, message, state) VALUES ($1, to_timestamp($2 / 1000.0), $3, $4, $5::jsonb) RETURNING id::float8 AS id`,
          [vehicleId, a.ts, a.kind, a.message, JSON.stringify(st)],
        );
        alerts.push({ ...a, id: ins.rows[0].id, state: st });
      }
    }
    const newest = sorted[sorted.length - 1];
    await client.query(
      `INSERT INTO telemetry_latest (vehicle_id, state, state_ts, received_at)
       VALUES ($1, $2::jsonb, to_timestamp($3 / 1000.0), to_timestamp($4 / 1000.0))
       ON CONFLICT (vehicle_id) DO UPDATE
         SET state = EXCLUDED.state, state_ts = EXCLUDED.state_ts, received_at = EXCLUDED.received_at
         WHERE telemetry_latest.state_ts <= EXCLUDED.state_ts`,
      [vehicleId, JSON.stringify(newest), newest.ts, now],
    );
    await client.query(
      `UPDATE vehicle_settings SET low_battery_active = $2, last_fault = $3, last_maint_km = $4,
              anchor = CASE WHEN $5::boolean THEN ST_SetSRID(ST_MakePoint($6, $7), 4326)::geography END
        WHERE vehicle_id = $1`,
      [vehicleId, tracking.lowBatteryActive, tracking.lastFault, tracking.lastMaintKm, tracking.anchor !== null, tracking.anchor?.lon ?? 0, tracking.anchor?.lat ?? 0],
    );
    for (const f of fenceRows) {
      const v = tracking.fenceInside[f.id];
      if (v !== undefined && v !== f.inside) await client.query('UPDATE geofences SET inside = $2 WHERE id = $1', [f.id, v]);
    }
    await client.query('COMMIT');
    return { alerts, latest: newest, receivedAt: now };
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

export const alertToJson = (a: StoredAlert) => ({ id: a.id, ts: a.ts, kind: a.kind, message: a.message, state: a.state });
