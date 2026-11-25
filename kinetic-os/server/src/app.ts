import fs from 'node:fs';
import path from 'node:path';
import fastify, { type FastifyInstance } from 'fastify';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import fastifyStatic from '@fastify/static';
import websocket from '@fastify/websocket';
import { ZodError } from 'zod';
import type { Pool } from 'pg';
import type { Config } from './config';
import type { Ctx } from './context';
import { HttpError, unauthorized } from './errors';
import { Hub } from './hub';
import { bearerToken, verifyToken } from './logic/auth';
import authRoutes from './routes/auth';
import friendRoutes from './routes/friends';
import groupRoutes from './routes/groups';
import routeRoutes from './routes/route';
import rideRoutes from './routes/rides';
import telematicsRoutes from './routes/telematics';
import vehicleRoutes from './routes/vehicles';
import wsRoutes from './ws';

declare module 'fastify' {
  interface FastifyInstance {
    authenticate: (req: import('fastify').FastifyRequest) => Promise<void>;
  }
}

export async function buildApp(pool: Pool, config: Config): Promise<{ app: FastifyInstance; hub: Hub }> {
  const hub = new Hub();
  const ctx: Ctx = { pool, config, hub };
  const app = fastify({ logger: process.env.NODE_ENV !== 'test', trustProxy: config.trustProxy, bodyLimit: 2 * 1024 * 1024 });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof HttpError) return reply.code(err.statusCode).send({ error: err.code, message: err.message });
    if (err instanceof ZodError) {
      return reply.code(400).send({ error: 'validation_error', message: err.issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`).join('; ') });
    }
    const e = err as { statusCode?: number; code?: string; message: string };
    if (e.statusCode === 429) return reply.code(429).send({ error: 'rate_limited', message: 'Too many requests, slow down' });
    if (e.statusCode && e.statusCode >= 400 && e.statusCode < 500) {
      const map: Record<number, string> = { 400: 'bad_request', 401: 'unauthorized', 403: 'forbidden', 404: 'not_found', 409: 'conflict' };
      return reply.code(e.statusCode === 413 ? 400 : (e.statusCode as 400)).send({ error: e.statusCode === 413 ? 'payload_too_large' : (map[e.statusCode] ?? 'bad_request'), message: e.message });
    }
    req.log.error(err);
    return reply.code(500).send({ error: 'internal_error', message: 'Internal server error' });
  });
  app.setNotFoundHandler((_req, reply) => reply.code(404).send({ error: 'not_found', message: 'Not found' }));

  await app.register(rateLimit, {
    max: 600,
    timeWindow: '1 minute',
    allowList: (req) => !req.url.startsWith('/api/') && !req.url.startsWith('/ws'),
  });
  await app.register(multipart, { limits: { fileSize: 5 * 1024 * 1024, files: 1, fields: 5 } });
  await app.register(websocket, { options: { maxPayload: 2 * 1024 * 1024 } });

  app.decorateRequest('userId', '');
  app.decorate('authenticate', async (req) => {
    const uid = verifyToken(bearerToken(req.headers.authorization), config.jwtSecret);
    if (!uid) throw unauthorized('Invalid or missing token');
    req.userId = uid;
  });

  await app.register(
    async (api) => {
      api.get('/health', async (_req, reply) => {
        let db = false;
        try {
          await pool.query('SELECT 1');
          db = true;
        } catch {
          /* db down */
        }
        return reply.code(db ? 200 : 503).send({ ok: db, db });
      });
      await authRoutes(api, ctx);
      await friendRoutes(api, ctx);
      await groupRoutes(api, ctx);
      await vehicleRoutes(api, ctx);
      await telematicsRoutes(api, ctx);
      await rideRoutes(api, ctx);
      await routeRoutes(api, ctx);
    },
    { prefix: '/api/v1' },
  );
  await app.register(async (a) => wsRoutes(a, ctx));

  const publicDir = config.publicDir ?? path.resolve(__dirname, '..', 'public');
  if (fs.existsSync(publicDir)) {
    await app.register(fastifyStatic, { root: publicDir, prefix: '/', wildcard: false });
  }
  return { app, hub };
}
