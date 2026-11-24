import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { ms } from '../db';
import { badRequest, notFound } from '../errors';
import { computeOnline } from '../logic/online';
import { geofenceSchema, settingsSchema, stateBatchSchema, uuidSchema } from '../logic/validation';
import { alertToJson, ingestStates } from '../services/telemetry';
import { requireOwnedVehicle } from './helpers';

const GEO_COLS = `id, vehicle_id AS "vehicleId", name, lat, lon, radius_m::float8 AS "radiusM", alert_on AS "alertOn", ${ms('created_at')} AS "createdAt"`;
const SETTINGS_COLS = `low_battery_pct::float8 AS "lowBatteryPct", maintenance_km::float8 AS "maintenanceKm", move_alert AS "moveAlert", parked`;

/** Shared by REST and WebSocket ingestion: persist, alert, and fan out to subscribers. */
export async function ingestAndBroadcast(ctx: Ctx, vehicleId: string, states: z.infer<typeof stateBatchSchema>['states']) {
  const res = await ingestStates(ctx.pool, vehicleId, states);
  ctx.hub.toVehicleSubscribers(vehicleId, {
    type: 'vehicle_state',
    vehicleId,
    state: res.latest,
    receivedAt: res.receivedAt,
    online: computeOnline(res.receivedAt, res.latest.vescConnected),
  });
  for (const a of res.alerts) ctx.hub.toVehicleSubscribers(vehicleId, { type: 'alert', vehicleId, alert: alertToJson(a) });
  return res;
}

export default async function telematicsRoutes(app: FastifyInstance, ctx: Ctx) {
  const pre = { preHandler: app.authenticate };
  type P = { Params: { vehicleId: string } };

  app.post<P>('/telematics/:vehicleId/state', { ...pre, bodyLimit: 4 * 1024 * 1024 }, async (req, reply) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const { states } = stateBatchSchema.parse(req.body);
    await ingestAndBroadcast(ctx, v.id, states);
    reply.code(202);
    return { accepted: states.length };
  });

  app.get<P>('/telematics/:vehicleId/state', pre, async (req) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const r = (await ctx.pool.query(`SELECT state, ${ms('received_at')} AS "receivedAt" FROM telemetry_latest WHERE vehicle_id = $1`, [v.id])).rows[0];
    if (!r) throw notFound('No telemetry received yet');
    return { state: r.state, receivedAt: r.receivedAt, online: computeOnline(r.receivedAt, r.state.vescConnected) };
  });

  const historyQ = z.object({
    from: z.coerce.number().int().nonnegative().optional(),
    to: z.coerce.number().int().nonnegative().optional(),
    limit: z.coerce.number().int().min(1).max(5000).default(1000),
  });
  app.get<P>('/telematics/:vehicleId/history', pre, async (req) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const q = historyQ.parse(req.query);
    // the newest `limit` samples inside the window, returned oldest-first
    const r = await ctx.pool.query(
      `SELECT state FROM (
         SELECT state, ts FROM telemetry_history
          WHERE vehicle_id = $1 AND ($2::float8 IS NULL OR ts >= to_timestamp($2 / 1000.0)) AND ($3::float8 IS NULL OR ts <= to_timestamp($3 / 1000.0))
          ORDER BY ts DESC LIMIT $4) t ORDER BY ts ASC`,
      [v.id, q.from ?? null, q.to ?? null, q.limit],
    );
    return r.rows.map((x) => x.state);
  });

  app.get<P>('/telematics/:vehicleId/geofences', pre, async (req) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    return (await ctx.pool.query(`SELECT ${GEO_COLS} FROM geofences WHERE vehicle_id = $1 ORDER BY created_at`, [v.id])).rows;
  });

  app.post<P>('/telematics/:vehicleId/geofences', pre, async (req, reply) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const b = geofenceSchema.parse(req.body);
    const cnt = (await ctx.pool.query('SELECT count(*)::int AS n FROM geofences WHERE vehicle_id = $1', [v.id])).rows[0].n;
    if (cnt >= 50) throw badRequest('At most 50 geofences per vehicle');
    // seed `inside` from the latest known position so the first transition is detected correctly
    const r = await ctx.pool.query(
      `INSERT INTO geofences (vehicle_id, name, lat, lon, center, radius_m, alert_on, inside)
       VALUES ($1, $2, $3, $4, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, $5::float8, $6,
         (SELECT ST_DWithin(ST_SetSRID(ST_MakePoint(($7::jsonb->>'lon')::float8, ($7::jsonb->>'lat')::float8), 4326)::geography,
                            ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, $5::float8)
            WHERE $7::jsonb ? 'lat'))
       RETURNING ${GEO_COLS}`,
      [v.id, b.name, b.lat, b.lon, b.radiusM, b.alertOn,
        JSON.stringify((await ctx.pool.query('SELECT state FROM telemetry_latest WHERE vehicle_id = $1', [v.id])).rows[0]?.state ?? {})],
    );
    reply.code(201);
    return r.rows[0];
  });

  app.delete<{ Params: { vehicleId: string; gid: string } }>('/telematics/:vehicleId/geofences/:gid', pre, async (req, reply) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const gid = uuidSchema.safeParse(req.params.gid);
    if (!gid.success) throw notFound('Geofence not found');
    const r = await ctx.pool.query('DELETE FROM geofences WHERE id = $1 AND vehicle_id = $2', [gid.data, v.id]);
    if (!r.rowCount) throw notFound('Geofence not found');
    reply.code(204);
  });

  const alertsQ = z.object({ since: z.coerce.number().int().nonnegative().optional(), limit: z.coerce.number().int().min(1).max(500).default(200) });
  app.get<P>('/telematics/:vehicleId/alerts', pre, async (req) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const q = alertsQ.parse(req.query);
    const r = await ctx.pool.query(
      `SELECT id::float8 AS id, ${ms('ts')} AS ts, kind, message, state FROM alerts
        WHERE vehicle_id = $1 AND ($2::float8 IS NULL OR ts > to_timestamp($2 / 1000.0)) ORDER BY ts DESC, id DESC LIMIT $3`,
      [v.id, q.since ?? null, q.limit],
    );
    return r.rows;
  });

  app.put<P>('/telematics/:vehicleId/settings', pre, async (req) => {
    const v = await requireOwnedVehicle(ctx.pool, req.params.vehicleId, req.userId);
    const b = settingsSchema.parse(req.body);
    await ctx.pool.query('INSERT INTO vehicle_settings (vehicle_id) VALUES ($1) ON CONFLICT DO NOTHING', [v.id]);
    const r = await ctx.pool.query(
      `UPDATE vehicle_settings SET
         low_battery_pct = COALESCE($2, low_battery_pct), maintenance_km = COALESCE($3, maintenance_km),
         move_alert = COALESCE($4, move_alert), parked = COALESCE($5, parked),
         -- explicit parked flag: anchor at the last known position; un-parking clears the anchor
         anchor = CASE WHEN $5::boolean IS TRUE THEN (SELECT ST_SetSRID(ST_MakePoint((state->>'lon')::float8, (state->>'lat')::float8), 4326)::geography
                                                         FROM telemetry_latest WHERE vehicle_id = $1 AND state ? 'lat')
                       WHEN $5::boolean IS FALSE THEN NULL ELSE anchor END,
         last_maint_km = CASE WHEN $3 IS NOT NULL THEN (SELECT (state->>'odometerKm')::real FROM telemetry_latest WHERE vehicle_id = $1) ELSE last_maint_km END
       WHERE vehicle_id = $1 RETURNING ${SETTINGS_COLS}`,
      [v.id, b.lowBatteryPct ?? null, b.maintenanceKm ?? null, b.moveAlert ?? null, b.parked ?? null],
    );
    return r.rows[0];
  });
}
