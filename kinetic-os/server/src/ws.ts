import type { FastifyInstance } from 'fastify';
import type { Ctx } from './context';
import { safeSend, type Conn } from './hub';
import { verifyToken } from './logic/auth';
import { parseClientMessage, RateLimiter, TokenBucket, type ClientMessage } from './logic/wsMessages';
import { ingestAndBroadcast } from './routes/telematics';
import { computeOnline } from './logic/online';
import { ms } from './db';

export const PING_INTERVAL_MS = 20_000;
export const IDLE_TIMEOUT_MS = 60_000;

export default async function wsRoutes(app: FastifyInstance, ctx: Ctx) {
  app.get<{ Querystring: { token?: string } }>(
    '/ws',
    {
      websocket: true,
      config: { rateLimit: { max: 30, timeWindow: '1 minute' } },
      preValidation: async (req, reply) => {
        const uid = verifyToken(req.query.token, ctx.config.jwtSecret);
        if (!uid) return reply.code(401).send({ error: 'unauthorized', message: 'Invalid or missing token' });
        req.userId = uid;
      },
    },
    async (socket, req) => {
      const user = (await ctx.pool.query('SELECT display_name FROM users WHERE id = $1', [req.userId])).rows[0];
      if (!user) {
        socket.close(4401, 'unknown user');
        return;
      }
      const conn: Conn = {
        sock: socket,
        userId: req.userId,
        displayName: user.display_name,
        groups: new Set(),
        limiter: new RateLimiter(200),
        bucket: new TokenBucket(10, 20),
        lastTraffic: Date.now(),
      };
      ctx.hub.add(conn);
      ctx.pool.query('UPDATE users SET last_seen = now() WHERE id = $1', [conn.userId]).catch(() => {});
      const send = (o: unknown) => safeSend(socket, JSON.stringify(o));
      const err = (error: string, message: string) => send({ type: 'error', error, message });

      const timer = setInterval(() => {
        if (Date.now() - conn.lastTraffic > IDLE_TIMEOUT_MS) {
          socket.close(4408, 'idle timeout');
          return;
        }
        send({ type: 'ping' });
      }, PING_INTERVAL_MS);

      // messages of one connection are processed strictly in order (no concurrent DB transactions per socket)
      let chain: Promise<void> = Promise.resolve();
      socket.on('message', (data: Buffer) => {
        conn.lastTraffic = Date.now();
        const raw = data.toString('utf8');
        // voice messages are expensive (≤ 1 MB) → cost 10 tokens; everything else 1 (pong is free)
        const cost = raw.includes('"voice"') ? 10 : raw.includes('"pong"') ? 0 : 1;
        if (cost > 0 && !conn.bucket.take(cost)) { err('rate_limited', 'Too many messages'); return; }
        chain = chain.then(() => handle(raw)).catch((e) => {
          req.log.error(e);
          err('internal_error', 'Internal error');
        });
      });
      socket.on('error', () => {});
      socket.on('close', () => {
        clearInterval(timer);
        const groups = ctx.hub.remove(conn);
        for (const g of groups) if (!ctx.hub.userInRoom(g, conn.userId)) ctx.hub.toRoom(g, { type: 'presence', groupId: g, userId: conn.userId, online: false });
        ctx.pool.query('UPDATE users SET last_seen = now() WHERE id = $1', [conn.userId]).catch(() => {});
      });

      async function isMember(groupId: string) {
        return (await ctx.pool.query('SELECT 1 FROM group_members WHERE group_id = $1 AND user_id = $2', [groupId, conn.userId])).rowCount! > 0;
      }
      async function requireJoined(groupId: string): Promise<boolean> {
        if (!conn.groups.has(groupId)) {
          err('not_joined', 'Send join_group first');
          return false;
        }
        // membership may have been revoked since joining
        if (!(await isMember(groupId))) {
          ctx.hub.leave(conn, groupId);
          err('forbidden', 'You are no longer a member of this group');
          return false;
        }
        return true;
      }

      async function handle(raw: string) {
        const p = parseClientMessage(raw);
        if (!p.ok) return err(p.error, p.message);
        const m: ClientMessage = p.msg;
        switch (m.type) {
          case 'pong':
            return;
          case 'join_group': {
            if (!(await isMember(m.groupId))) return err('forbidden', 'Not a member of this group (join via code first)');
            const first = ctx.hub.join(conn, m.groupId);
            if (first) ctx.hub.toRoom(m.groupId, { type: 'presence', groupId: m.groupId, userId: conn.userId, online: true }, conn.userId);
            return;
          }
          case 'leave_group': {
            if (!conn.groups.has(m.groupId)) return;
            if (ctx.hub.leave(conn, m.groupId)) ctx.hub.toRoom(m.groupId, { type: 'presence', groupId: m.groupId, userId: conn.userId, online: false });
            return;
          }
          case 'position': {
            if (!conn.groups.has(m.groupId)) return err('not_joined', 'Send join_group first');
            if (!conn.limiter.allow()) return; // 5 Hz cap: silently drop
            const { type: _t, groupId, ...pos } = m;
            ctx.hub.toRoom(groupId, { type: 'member_position', groupId, userId: conn.userId, displayName: conn.displayName, vehicleName: await vehicleName(), ...pos }, conn.userId);
            return;
          }
          case 'chat': {
            if (!(await requireJoined(m.groupId))) return;
            ctx.hub.toRoom(m.groupId, { type: 'chat', groupId: m.groupId, userId: conn.userId, displayName: conn.displayName, ts: Date.now(), text: m.text }, conn.userId);
            return;
          }
          case 'voice': {
            if (!(await requireJoined(m.groupId))) return;
            ctx.hub.toRoom(m.groupId, { type: 'voice', groupId: m.groupId, userId: conn.userId, displayName: conn.displayName, ts: Date.now(), mime: m.mime, durationMs: m.durationMs, dataBase64: m.dataBase64 }, conn.userId);
            return;
          }
          case 'help_request': {
            if (!(await requireJoined(m.groupId))) return;
            ctx.hub.toRoom(m.groupId, { type: 'help_request', groupId: m.groupId, userId: conn.userId, displayName: conn.displayName, ts: Date.now(), lat: m.lat, lon: m.lon, message: m.message }, conn.userId);
            return;
          }
          case 'subscribe_vehicle': {
            const v = (await ctx.pool.query('SELECT owner_id FROM vehicles WHERE id = $1', [m.vehicleId])).rows[0];
            if (!v) return err('not_found', 'Vehicle not found');
            if (v.owner_id !== conn.userId) return err('forbidden', 'Only the owner can subscribe to a vehicle');
            ctx.hub.subscribe(conn, m.vehicleId);
            const r = (await ctx.pool.query(`SELECT state, ${ms('received_at')} AS "receivedAt" FROM telemetry_latest WHERE vehicle_id = $1`, [m.vehicleId])).rows[0];
            if (r) send({ type: 'vehicle_state', vehicleId: m.vehicleId, state: r.state, receivedAt: r.receivedAt, online: computeOnline(r.receivedAt, r.state.vescConnected) });
            return;
          }
          case 'vehicle_state': {
            const v = (await ctx.pool.query('SELECT owner_id FROM vehicles WHERE id = $1', [m.vehicleId])).rows[0];
            if (!v) return err('not_found', 'Vehicle not found');
            if (v.owner_id !== conn.userId) return err('forbidden', 'Only the owner can push vehicle state');
            await ingestAndBroadcast(ctx, m.vehicleId, [m.state]);
            return;
          }
        }
      }

      async function vehicleName(): Promise<string | undefined> {
        const r = await ctx.pool.query('SELECT name FROM vehicles WHERE owner_id = $1 ORDER BY updated_at DESC LIMIT 1', [conn.userId]);
        return r.rows[0]?.name;
      }
    },
  );
}
