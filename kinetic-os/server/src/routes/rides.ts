import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';
import { ms } from '../db';
import { badRequest, conflict, forbidden, notFound } from '../errors';
import { lapSchema, rideSchema, trackSchema, uuidSchema } from '../logic/validation';
import { requireOwnedVehicle } from './helpers';

const RIDE_COLS = `id, vehicle_id AS "vehicleId", user_id AS "userId", ${ms('started_at')} AS "startedAt", ${ms('ended_at')} AS "endedAt",
  distance_m AS "distanceM", max_speed_kmh AS "maxSpeedKmh", energy_wh AS "energyWh", summary`;

export default async function rideRoutes(app: FastifyInstance, ctx: Ctx) {
  const pre = { preHandler: app.authenticate };

  app.post('/rides', { ...pre, bodyLimit: 12 * 1024 * 1024 }, async (req, reply) => {
    const b = rideSchema.parse(req.body);
    if (b.endedAt < b.startedAt) throw badRequest('endedAt must not be before startedAt');
    await requireOwnedVehicle(ctx.pool, b.vehicleId, req.userId);
    const r = await ctx.pool.query(
      `INSERT INTO rides (id, user_id, vehicle_id, started_at, ended_at, distance_m, max_speed_kmh, energy_wh, gpx, summary)
       VALUES ($1, $2, $3, to_timestamp($4 / 1000.0), to_timestamp($5 / 1000.0), $6, $7, $8, $9, $10::jsonb)
       ON CONFLICT (id) DO NOTHING RETURNING id`,
      [b.id, req.userId, b.vehicleId, b.startedAt, b.endedAt, b.distanceM, b.maxSpeedKmh, b.energyWh, b.gpx ?? null, b.summary ? JSON.stringify(b.summary) : null],
    );
    if (!r.rowCount) {
      const own = (await ctx.pool.query('SELECT user_id FROM rides WHERE id = $1', [b.id])).rows[0];
      if (own?.user_id !== req.userId) throw conflict('Ride id already in use');
    }
    reply.code(201);
    return { id: b.id };
  });

  app.get<{ Querystring: { vehicleId?: string } }>('/rides', pre, async (req) => {
    const vId = req.query.vehicleId;
    if (vId !== undefined && !uuidSchema.safeParse(vId).success) throw badRequest('vehicleId must be a UUID');
    return (
      await ctx.pool.query(
        `SELECT ${RIDE_COLS} FROM rides WHERE user_id = $1 AND ($2::uuid IS NULL OR vehicle_id = $2) ORDER BY started_at DESC LIMIT 500`,
        [req.userId, vId ?? null],
      )
    ).rows;
  });

  app.get<{ Params: { id: string } }>('/rides/:id', pre, async (req) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Ride not found');
    const r = (await ctx.pool.query(`SELECT ${RIDE_COLS}, gpx, user_id FROM rides WHERE id = $1`, [id.data])).rows[0];
    if (!r) throw notFound('Ride not found');
    if (r.user_id !== req.userId) throw forbidden('Not your ride');
    return r;
  });

  // ---- tracks / laps ----
  app.post('/tracks', pre, async (req, reply) => {
    const b = trackSchema.parse(req.body);
    const r = await ctx.pool.query(
      `INSERT INTO tracks (creator_id, name, start_line, sectors) VALUES ($1, $2, $3::jsonb, $4::jsonb)
       RETURNING id, name, start_line AS "startLine", sectors, creator_id AS "creatorId", ${ms('created_at')} AS "createdAt"`,
      [req.userId, b.name, JSON.stringify(b.startLine), JSON.stringify(b.sectors)],
    );
    reply.code(201);
    return r.rows[0];
  });

  app.get('/tracks', pre, async () =>
    (await ctx.pool.query(`SELECT id, name, start_line AS "startLine", sectors, creator_id AS "creatorId", ${ms('created_at')} AS "createdAt" FROM tracks ORDER BY created_at DESC LIMIT 500`)).rows,
  );

  app.post<{ Params: { id: string } }>('/tracks/:id/laps', pre, async (req, reply) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Track not found');
    const b = lapSchema.parse(req.body);
    if (!(await ctx.pool.query('SELECT 1 FROM tracks WHERE id = $1', [id.data])).rowCount) throw notFound('Track not found');
    if (b.vehicleId) await requireOwnedVehicle(ctx.pool, b.vehicleId, req.userId);
    const r = await ctx.pool.query(
      `INSERT INTO laps (track_id, user_id, vehicle_id, lap_ms, sectors_ms, gps_hz, ts)
       VALUES ($1, $2, $3, $4, $5::jsonb, $6, to_timestamp($7 / 1000.0)) RETURNING id`,
      [id.data, req.userId, b.vehicleId ?? null, b.lapMs, JSON.stringify(b.sectorsMs), b.gpsHz ?? null, b.ts],
    );
    reply.code(201);
    return r.rows[0];
  });

  app.get<{ Params: { id: string } }>('/tracks/:id/leaderboard', pre, async (req) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Track not found');
    const r = await ctx.pool.query(
      `SELECT DISTINCT ON (l.user_id) l.user_id AS "userId", u.display_name AS "displayName", l.lap_ms AS "bestLapMs", v.name AS "vehicleName"
         FROM laps l JOIN users u ON u.id = l.user_id LEFT JOIN vehicles v ON v.id = l.vehicle_id
        WHERE l.track_id = $1 ORDER BY l.user_id, l.lap_ms ASC`,
      [id.data],
    );
    return r.rows.sort((a, b) => a.bestLapMs - b.bestLapMs);
  });
}
