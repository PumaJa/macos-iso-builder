import fs from 'node:fs';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';
import { ms } from '../db';
import { badRequest, forbidden, notFound } from '../errors';
import { canReadVehiclePhoto } from '../logic/access';
import { RESERVED_VEHICLE_KEYS, uuidSchema, vehiclePutSchema } from '../logic/validation';

const COLS = `id, owner_id AS "ownerId", name, type, make, model, extra, photo_ext, ${ms('created_at')} AS "createdAt", ${ms('updated_at')} AS "updatedAt"`;

interface Row { id: string; extra: Record<string, unknown>; photo_ext: string | null; [k: string]: unknown }
export function vehicleJson(r: Row) {
  const { extra, photo_ext, ...rest } = r;
  return { ...extra, ...rest, photoUrl: photo_ext ? `/api/v1/vehicles/${r.id}/photo` : null };
}

export function sniffImage(buf: Buffer): 'jpg' | 'png' | null {
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpg';
  if (buf.length > 8 && buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'png';
  return null;
}

export default async function vehicleRoutes(app: FastifyInstance, ctx: Ctx) {
  const pre = { preHandler: app.authenticate };
  const photoDir = path.join(ctx.config.dataDir, 'photos');
  const vid = (s: string) => {
    const r = uuidSchema.safeParse(s);
    if (!r.success) throw badRequest('Vehicle id must be a UUID');
    return r.data;
  };

  app.get('/vehicles', pre, async (req) =>
    (await ctx.pool.query(`SELECT ${COLS} FROM vehicles WHERE owner_id = $1 ORDER BY created_at`, [req.userId])).rows.map(vehicleJson),
  );

  app.put<{ Params: { id: string } }>('/vehicles/:id', pre, async (req) => {
    const id = vid(req.params.id);
    const b = vehiclePutSchema.parse(req.body) as Record<string, unknown>;
    const extra = Object.fromEntries(Object.entries(b).filter(([k]) => !RESERVED_VEHICLE_KEYS.has(k)));
    if (JSON.stringify(extra).length > 20_000) throw badRequest('Free-form fields too large');
    const r = await ctx.pool.query(
      `INSERT INTO vehicles (id, owner_id, name, type, make, model, extra) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)
       ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, type = EXCLUDED.type, make = EXCLUDED.make, model = EXCLUDED.model,
              extra = EXCLUDED.extra, updated_at = now()
         WHERE vehicles.owner_id = EXCLUDED.owner_id
       RETURNING ${COLS}`,
      [id, req.userId, b.name, b.type ?? null, b.make ?? null, b.model ?? null, JSON.stringify(extra)],
    );
    if (!r.rows[0]) throw forbidden('Vehicle belongs to another user');
    return vehicleJson(r.rows[0]);
  });

  app.post<{ Params: { id: string } }>('/vehicles/:id/photo', pre, async (req) => {
    const id = vid(req.params.id);
    const v = (await ctx.pool.query('SELECT owner_id FROM vehicles WHERE id = $1', [id])).rows[0];
    if (!v) throw notFound('Vehicle not found');
    if (v.owner_id !== req.userId) throw forbidden('Only the owner can upload a photo');
    if (!req.isMultipart()) throw badRequest('Expected multipart/form-data with a "file" field');
    const file = await req.file();
    if (!file || file.fieldname !== 'file') throw badRequest('Missing "file" field');
    if (!['image/jpeg', 'image/png'].includes(file.mimetype)) throw badRequest('Only image/jpeg and image/png are accepted');
    let buf: Buffer;
    try {
      buf = await file.toBuffer();
    } catch {
      throw badRequest('File too large (max 5 MB)');
    }
    if (file.file.truncated) throw badRequest('File too large (max 5 MB)');
    const ext = sniffImage(buf);
    if (!ext) throw badRequest('File content is not a valid JPEG or PNG');
    await fs.promises.mkdir(photoDir, { recursive: true });
    for (const e of ['jpg', 'png']) if (e !== ext) await fs.promises.rm(path.join(photoDir, `${id}.${e}`), { force: true });
    await fs.promises.writeFile(path.join(photoDir, `${id}.${ext}`), buf);
    await ctx.pool.query('UPDATE vehicles SET photo_ext = $2, updated_at = now() WHERE id = $1', [id, ext]);
    return { photoUrl: `/api/v1/vehicles/${id}/photo` };
  });

  app.get<{ Params: { id: string } }>('/vehicles/:id/photo', pre, async (req, reply) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Photo not found');
    const v = (await ctx.pool.query('SELECT owner_id, photo_ext FROM vehicles WHERE id = $1', [id.data])).rows[0];
    if (!v || !v.photo_ext) throw notFound('Photo not found');
    const shares =
      v.owner_id !== req.userId &&
      (
        await ctx.pool.query(
          `SELECT 1 FROM group_members a JOIN group_members b ON a.group_id = b.group_id WHERE a.user_id = $1 AND b.user_id = $2 LIMIT 1`,
          [req.userId, v.owner_id],
        )
      ).rowCount! > 0;
    if (!canReadVehiclePhoto(v.owner_id, req.userId, shares)) throw forbidden('Not allowed to see this photo');
    const file = path.join(photoDir, `${id.data}.${v.photo_ext}`);
    if (!fs.existsSync(file)) throw notFound('Photo not found');
    reply.header('Cache-Control', 'private, max-age=300');
    return reply.type(v.photo_ext === 'png' ? 'image/png' : 'image/jpeg').send(fs.createReadStream(file));
  });

  app.delete<{ Params: { id: string } }>('/vehicles/:id', pre, async (req, reply) => {
    const id = vid(req.params.id);
    const v = (await ctx.pool.query('SELECT owner_id FROM vehicles WHERE id = $1', [id])).rows[0];
    if (!v) throw notFound('Vehicle not found');
    if (v.owner_id !== req.userId) throw forbidden('Only the owner can delete a vehicle');
    await ctx.pool.query('DELETE FROM vehicles WHERE id = $1', [id]);
    for (const e of ['jpg', 'png']) await fs.promises.rm(path.join(photoDir, `${id}.${e}`), { force: true });
    reply.code(204);
  });
}
