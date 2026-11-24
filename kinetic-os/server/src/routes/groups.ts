import type { FastifyInstance } from 'fastify';
import type { Pool, PoolClient } from 'pg';
import type { Ctx } from '../context';
import { ms } from '../db';
import { badRequest, conflict, forbidden, notFound } from '../errors';
import { canDeleteGroup, canReadGroup } from '../logic/access';
import { generateGroupCode, normalizeCode } from '../logic/groupCode';
import { groupCreateSchema, joinSchema, routeSchema, usernameBody, uuidSchema } from '../logic/validation';

const GROUP_COLS = `g.id, g.name, g.code, g.visibility, g.owner_id AS "ownerId", ${ms('g.created_at')} AS "createdAt"`;

export default async function groupRoutes(app: FastifyInstance, ctx: Ctx) {
  const pre = { preHandler: app.authenticate };
  const q = (sql: string, p: unknown[] = []) => ctx.pool.query(sql, p);

  async function isMember(groupId: string, userId: string, db: Pool | PoolClient = ctx.pool) {
    return (await db.query('SELECT 1 FROM group_members WHERE group_id = $1 AND user_id = $2', [groupId, userId])).rowCount! > 0;
  }
  async function loadGroup(id: string, withMembers = false) {
    const parsed = uuidSchema.safeParse(id);
    if (!parsed.success) throw notFound('Group not found');
    const g = (await q(`SELECT ${GROUP_COLS} FROM groups g WHERE g.id = $1`, [parsed.data])).rows[0];
    if (!g) throw notFound('Group not found');
    if (withMembers) {
      const m = await q(
        `SELECT m.user_id AS "userId", u.display_name AS "displayName", m.role FROM group_members m JOIN users u ON u.id = m.user_id
          WHERE m.group_id = $1 ORDER BY m.joined_at`,
        [g.id],
      );
      g.members = m.rows.map((r) => ({ ...r, online: ctx.hub.isUserOnline(r.userId) }));
    }
    return g;
  }

  app.post('/groups', pre, async (req, reply) => {
    const b = groupCreateSchema.parse(req.body);
    const client = await ctx.pool.connect();
    try {
      for (let attempt = 0; attempt < 20; attempt++) {
        const code = generateGroupCode();
        try {
          await client.query('BEGIN');
          const r = await client.query(`INSERT INTO groups (name, code, visibility, owner_id) VALUES ($1, $2, $3, $4) RETURNING id`, [b.name, code, b.visibility, req.userId]);
          await client.query(`INSERT INTO group_members (group_id, user_id, role) VALUES ($1, $2, 'owner')`, [r.rows[0].id, req.userId]);
          await client.query('COMMIT');
          reply.code(201);
          return await loadGroup(r.rows[0].id, true);
        } catch (e) {
          await client.query('ROLLBACK').catch(() => {});
          if ((e as { code?: string }).code !== '23505') throw e; // code collision -> retry
        }
      }
      throw new Error('could not allocate a unique group code');
    } finally {
      client.release();
    }
  });

  app.get('/groups', pre, async (req) =>
    (await q(`SELECT ${GROUP_COLS} FROM groups g JOIN group_members m ON m.group_id = g.id WHERE m.user_id = $1 ORDER BY g.created_at DESC`, [req.userId])).rows,
  );

  app.get('/groups/public', pre, async () =>
    (await q(`SELECT ${GROUP_COLS} FROM groups g WHERE g.visibility = 'public' ORDER BY g.created_at DESC LIMIT 200`)).rows,
  );

  app.post('/groups/join', pre, async (req) => {
    const { code } = joinSchema.parse(req.body);
    const g = (await q('SELECT id FROM groups WHERE upper(code) = $1', [normalizeCode(code)])).rows[0];
    if (!g) throw notFound('No group with that code');
    await q(`INSERT INTO group_members (group_id, user_id, role) VALUES ($1, $2, 'member') ON CONFLICT DO NOTHING`, [g.id, req.userId]);
    return loadGroup(g.id, true);
  });

  app.get<{ Params: { id: string } }>('/groups/:id', pre, async (req) => {
    const g = await loadGroup(req.params.id, true);
    if (!canReadGroup({ visibility: g.visibility, isMember: g.members.some((m: { userId: string }) => m.userId === req.userId) }))
      throw forbidden('This group is private');
    return g;
  });

  app.post<{ Params: { id: string } }>('/groups/:id/invite', pre, async (req, reply) => {
    const g = await loadGroup(req.params.id);
    if (!(await isMember(g.id, req.userId))) throw forbidden('Only members can invite');
    const { username } = usernameBody.parse(req.body);
    const t = (await q('SELECT id FROM users WHERE lower(username) = lower($1)', [username])).rows[0];
    if (!t) throw notFound('User not found');
    if (await isMember(g.id, t.id)) throw conflict('User is already a member');
    try {
      const r = await q('INSERT INTO invitations (group_id, from_id, to_id) VALUES ($1, $2, $3) RETURNING id', [g.id, req.userId, t.id]);
      reply.code(201);
      return r.rows[0];
    } catch (e) {
      if ((e as { code?: string }).code === '23505') throw conflict('User already invited');
      throw e;
    }
  });

  app.get('/invitations', pre, async (req) => {
    const r = await q(
      `SELECT i.id AS invitation_id, i.group_id, ${GROUP_COLS},
              u.id AS from_id, u.username AS from_username, u.display_name AS from_display_name, ${ms('u.created_at')} AS from_created
         FROM invitations i JOIN groups g ON g.id = i.group_id JOIN users u ON u.id = i.from_id
        WHERE i.to_id = $1 ORDER BY i.created_at DESC`,
      [req.userId],
    );
    return r.rows.map((x) => ({
      id: x.invitation_id,
      group: { id: x.group_id, name: x.name, code: x.code, visibility: x.visibility, ownerId: x.ownerId, createdAt: x.createdAt },
      from: { id: x.from_id, username: x.from_username, displayName: x.from_display_name, createdAt: x.from_created },
    }));
  });

  app.post<{ Params: { id: string } }>('/invitations/:id/accept', pre, async (req) => {
    const id = uuidSchema.safeParse(req.params.id);
    if (!id.success) throw notFound('Invitation not found');
    const inv = (await q('SELECT group_id, to_id FROM invitations WHERE id = $1', [id.data])).rows[0];
    if (!inv || inv.to_id !== req.userId) throw notFound('Invitation not found');
    await q(`INSERT INTO group_members (group_id, user_id, role) VALUES ($1, $2, 'member') ON CONFLICT DO NOTHING`, [inv.group_id, req.userId]);
    await q('DELETE FROM invitations WHERE id = $1', [id.data]);
    return loadGroup(inv.group_id, true);
  });

  app.delete<{ Params: { id: string } }>('/groups/:id/members/me', pre, async (req, reply) => {
    const g = await loadGroup(req.params.id);
    if (g.ownerId === req.userId) throw badRequest('The owner cannot leave; delete the group instead');
    const r = await q('DELETE FROM group_members WHERE group_id = $1 AND user_id = $2', [g.id, req.userId]);
    if (!r.rowCount) throw notFound('You are not a member of this group');
    if (ctx.hub.removeUserFromRoom(g.id, req.userId)) ctx.hub.toRoom(g.id, { type: 'presence', groupId: g.id, userId: req.userId, online: false });
    reply.code(204);
  });

  app.delete<{ Params: { id: string } }>('/groups/:id', pre, async (req, reply) => {
    const g = await loadGroup(req.params.id);
    if (!canDeleteGroup(g.ownerId, req.userId)) throw forbidden('Only the owner can delete the group');
    await q('DELETE FROM groups WHERE id = $1', [g.id]);
    ctx.hub.closeRoom(g.id);
    reply.code(204);
  });

  app.put<{ Params: { id: string } }>('/groups/:id/route', { ...pre, bodyLimit: 6 * 1024 * 1024 }, async (req) => {
    const g = await loadGroup(req.params.id);
    if (!(await isMember(g.id, req.userId))) throw forbidden('Only members can set the route');
    const b = routeSchema.parse(req.body);
    const r = await q(
      `INSERT INTO group_routes (group_id, name, gpx) VALUES ($1, $2, $3)
       ON CONFLICT (group_id) DO UPDATE SET name = EXCLUDED.name, gpx = EXCLUDED.gpx, updated_at = now()
       RETURNING name, gpx, ${ms('updated_at')} AS "updatedAt"`,
      [g.id, b.name, b.gpx],
    );
    ctx.hub.toRoom(g.id, { type: 'route_updated', groupId: g.id });
    return r.rows[0];
  });

  app.get<{ Params: { id: string } }>('/groups/:id/route', pre, async (req) => {
    const g = await loadGroup(req.params.id);
    if (!canReadGroup({ visibility: g.visibility, isMember: await isMember(g.id, req.userId) })) throw forbidden('This group is private');
    const r = (await q(`SELECT name, gpx, ${ms('updated_at')} AS "updatedAt" FROM group_routes WHERE group_id = $1`, [g.id])).rows[0];
    if (!r) throw notFound('No route shared yet');
    return r;
  });
}
