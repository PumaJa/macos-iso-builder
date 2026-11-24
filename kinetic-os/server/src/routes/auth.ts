import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';
import { ms } from '../db';
import { conflict, unauthorized } from '../errors';
import { hashPassword, signToken, verifyPassword } from '../logic/auth';
import { loginSchema, registerSchema } from '../logic/validation';

export const USER_COLS = `id, username, display_name AS "displayName", ${ms('created_at')} AS "createdAt"`;

export default async function authRoutes(app: FastifyInstance, ctx: Ctx) {
  const strict = { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } };

  app.post('/auth/register', strict, async (req, reply) => {
    const b = registerSchema.parse(req.body);
    const hash = await hashPassword(b.password);
    try {
      const r = await ctx.pool.query(
        `INSERT INTO users (username, display_name, password_hash) VALUES ($1, $2, $3) RETURNING ${USER_COLS}`,
        [b.username, b.displayName, hash],
      );
      reply.code(201);
      return { token: signToken(r.rows[0].id, ctx.config.jwtSecret), user: r.rows[0] };
    } catch (e) {
      if ((e as { code?: string }).code === '23505') throw conflict('Username already taken', 'username_taken');
      throw e;
    }
  });

  app.post('/auth/login', strict, async (req) => {
    const b = loginSchema.parse(req.body);
    const r = await ctx.pool.query(`SELECT ${USER_COLS}, password_hash FROM users WHERE lower(username) = lower($1)`, [b.username]);
    const row = r.rows[0];
    // constant-ish time: always run a bcrypt compare
    const ok = await verifyPassword(b.password, row?.password_hash ?? '$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidu');
    if (!row || !ok) throw unauthorized('Invalid username or password');
    delete row.password_hash;
    return { token: signToken(row.id, ctx.config.jwtSecret), user: row };
  });

  app.get('/me', { preHandler: app.authenticate }, async (req) => {
    const r = await ctx.pool.query(`SELECT ${USER_COLS} FROM users WHERE id = $1`, [req.userId]);
    if (!r.rows[0]) throw unauthorized('User no longer exists');
    return r.rows[0];
  });
}
