import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';
import { ms } from '../db';
import { badRequest, conflict, forbidden, notFound } from '../errors';
import { uuidSchema, usernameBody } from '../logic/validation';
import { USER_COLS } from './auth';

export default async function friendRoutes(app: FastifyInstance, ctx: Ctx) {
  const pre = { preHandler: app.authenticate };

  app.get('/friends', pre, async (req) => {
    const r = await ctx.pool.query(
      `SELECT u.id, u.username, u.display_name AS "displayName", ${ms('u.last_seen')} AS "lastSeen"
         FROM friendships f JOIN users u ON u.id = CASE WHEN f.from_id = $1 THEN f.to_id ELSE f.from_id END
        WHERE f.status = 'accepted' AND $1 IN (f.from_id, f.to_id) ORDER BY u.display_name`,
      [req.userId],
    );
    return r.rows.map((u) => ({ ...u, online: ctx.hub.isUserOnline(u.id), lastSeen: u.lastSeen }));
  });

  app.post('/friends/requests', pre, async (req, reply) => {
    const { username } = usernameBody.parse(req.body);
    const t = (await ctx.pool.query('SELECT id FROM users WHERE lower(username) = lower($1)', [username])).rows[0];
    if (!t) throw notFound('User not found');
    if (t.id === req.userId) throw badRequest('Cannot befriend yourself');
    try {
      const r = await ctx.pool.query(`INSERT INTO friendships (from_id, to_id) VALUES ($1, $2) RETURNING id, status`, [req.userId, t.id]);
      reply.code(201);
      return r.rows[0];
    } catch (e) {
      if ((e as { code?: string }).code === '23505') throw conflict('Friendship or request already exists');
      throw e;
    }
  });

  app.get('/friends/requests', pre, async (req) => {
    const r = await ctx.pool.query(
      `SELECT f.id, f.status,
              (SELECT row_to_json(x) FROM (SELECT ${USER_COLS} FROM users WHERE id = f.from_id) x) AS "from",
              (SELECT row_to_json(x) FROM (SELECT ${USER_COLS} FROM users WHERE id = f.to_id) x) AS "to"
         FROM friendships f WHERE f.status = 'pending' AND $1 IN (f.from_id, f.to_id) ORDER BY f.created_at DESC`,
      [req.userId],
    );
    return r.rows;
  });

  app.post<{ Params: { id: string } }>('/friends/requests/:id/accept', pre, async (req) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Request not found');
    const f = (await ctx.pool.query('SELECT to_id FROM friendships WHERE id = $1 AND status = $2', [id.data, 'pending'])).rows[0];
    if (!f) throw notFound('Request not found');
    if (f.to_id !== req.userId) throw forbidden('Only the recipient can accept');
    const r = await ctx.pool.query(`UPDATE friendships SET status = 'accepted' WHERE id = $1 RETURNING id, status`, [id.data]);
    return r.rows[0];
  });

  app.delete<{ Params: { userId: string } }>('/friends/:userId', pre, async (req, reply) => {
    const uid = uuidSchema.safeParse(req.params.userId);
    if (!uid.success) throw notFound('Friend not found');
    const r = await ctx.pool.query(
      `DELETE FROM friendships WHERE (from_id = $1 AND to_id = $2) OR (from_id = $2 AND to_id = $1)`,
      [req.userId, uid.data],
    );
    if (!r.rowCount) throw notFound('Friend not found');
    reply.code(204);
  });
}
