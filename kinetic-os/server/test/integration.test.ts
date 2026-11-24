/**
 * Integration test against a real PostGIS database.
 * Runs only when TEST_DATABASE_URL points at an empty-ish PostGIS 16 database, e.g.
 *   docker run -d --name kpg -e POSTGRES_PASSWORD=pw -p 55432:5432 postgis/postgis:16-3.4
 *   TEST_DATABASE_URL=postgres://postgres:pw@localhost:55432/postgres npx vitest run test/integration.test.ts
 * Otherwise it is skipped.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { buildApp } from '../src/app';
import { createPool, migrate } from '../src/db';

const url = process.env.TEST_DATABASE_URL;
const d = url ? describe : describe.skip;

d('integration (PostGIS)', () => {
  let app: FastifyInstance;
  let pool: Pool;
  const suffix = Math.random().toString(36).slice(2, 8);
  const vid = '11111111-1111-4111-8111-' + Math.floor(Math.random() * 1e12).toString().padStart(12, '0');

  beforeAll(async () => {
    pool = createPool(url!);
    await migrate(pool);
    ({ app } = await buildApp(pool, { jwtSecret: 'integration-secret-1234567890', databaseUrl: url!, port: 0, host: '127.0.0.1', dataDir: '/tmp/kinetic-test-data', trustProxy: false }));
  });
  afterAll(async () => {
    await app?.close();
    await pool?.end();
  });

  const call = async (method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, token?: string, payload?: unknown) => {
    const r = await app.inject({ method, url: '/api/v1' + path, payload: payload as object, headers: token ? { authorization: `Bearer ${token}` } : {} });
    return { status: r.statusCode, body: r.body ? JSON.parse(r.body) : null };
  };
  const reg = async (n: string) => (await call('POST', '/auth/register', undefined, { username: `${n}_${suffix}`, password: 'password123', displayName: n })).body;

  it('health, auth, vehicle, telemetry, geofence alert, access rules', async () => {
    expect((await call('GET', '/health')).body).toEqual({ ok: true, db: true });
    const a = await reg('alice');
    const b = await reg('bob');
    expect((await call('GET', '/me', a.token)).body.username).toBe(`alice_${suffix}`);
    expect((await call('GET', '/me')).status).toBe(401);
    expect((await call('PUT', `/vehicles/${vid}`, a.token, { name: 'Board', type: 'board', make: 'm', model: 'x', extra1: 1 })).status).toBe(200);
    expect((await call('PUT', `/vehicles/${vid}`, b.token, { name: 'Hijack' })).status).toBe(403);

    expect((await call('POST', `/telematics/${vid}/geofences`, a.token, { name: 'Home', lat: 48, lon: 11, radiusM: 100, alertOn: 'exit' })).status).toBe(201);
    const now = Date.now();
    const base = { vescConnected: true, source: 'measured', speedKmh: 10 };
    expect((await call('POST', `/telematics/${vid}/state`, a.token, { states: [{ ...base, ts: now, lat: 48, lon: 11 }] })).status).toBe(202);
    expect((await call('POST', `/telematics/${vid}/state`, a.token, { states: [{ ...base, ts: now + 1000, lat: 48.01, lon: 11, socPct: 5 }] })).status).toBe(202);
    const alerts = (await call('GET', `/telematics/${vid}/alerts`, a.token)).body;
    const kinds = alerts.map((x: { kind: string }) => x.kind).sort();
    expect(kinds).toEqual(['geofence_exit', 'low_battery']);
    const st = (await call('GET', `/telematics/${vid}/state`, a.token)).body;
    expect(st.online).toBe(true);
    expect((await call('GET', `/telematics/${vid}/state`, b.token)).status).toBe(403);
    expect((await call('GET', `/telematics/${vid}/history`, a.token)).body.length).toBe(2);

    const g = (await call('POST', '/groups', a.token, { name: 'Secret', visibility: 'private' })).body;
    expect(g.code).toMatch(/^[A-Z0-9]{6}$/);
    expect((await call('GET', `/groups/${g.id}`, b.token)).status).toBe(403);
    expect((await call('POST', '/groups/join', b.token, { code: g.code.toLowerCase() })).status).toBe(200);
    expect((await call('GET', `/groups/${g.id}`, b.token)).status).toBe(200);
  });
});
