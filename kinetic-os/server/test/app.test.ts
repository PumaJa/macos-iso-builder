import { describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { buildApp } from '../src/app';
import { signToken } from '../src/logic/auth';

process.env.NODE_ENV = 'test';
const cfg = { jwtSecret: 'app-test-secret-0123456789', databaseUrl: 'x', port: 0, host: '127.0.0.1', dataDir: '/tmp/k', trustProxy: false };
const failing = { query: async () => { throw new Error('db down'); } } as unknown as Pool;
const okPool = { query: async () => ({ rows: [{ '?column?': 1 }], rowCount: 1 }) } as unknown as Pool;

describe('HTTP app (no database needed)', () => {
  it('health reports db state', async () => {
    const a = (await buildApp(okPool, cfg)).app;
    expect((await a.inject('/api/v1/health')).json()).toEqual({ ok: true, db: true });
    const b = (await buildApp(failing, cfg)).app;
    const r = await b.inject('/api/v1/health');
    expect(r.statusCode).toBe(503);
    expect(r.json()).toEqual({ ok: false, db: false });
  });
  it('requires auth, validates bodies, uses the error contract', async () => {
    const { app } = await buildApp(okPool, cfg);
    const r1 = await app.inject('/api/v1/vehicles');
    expect(r1.statusCode).toBe(401);
    expect(r1.json().error).toBe('unauthorized');
    const r2 = await app.inject({ method: 'POST', url: '/api/v1/auth/register', payload: { username: 'a', password: 'x', displayName: '' } });
    expect(r2.statusCode).toBe(400);
    expect(r2.json().error).toBe('validation_error');
    const token = signToken('00000000-0000-4000-8000-000000000001', cfg.jwtSecret);
    const r3 = await app.inject({ method: 'POST', url: '/api/v1/groups', payload: { name: '', visibility: 'x' }, headers: { authorization: `Bearer ${token}` } });
    expect(r3.statusCode).toBe(400);
    expect((await app.inject('/api/v1/nope')).statusCode).toBe(404);
  });
  it('has no motor-control endpoint and serves the dashboard', async () => {
    const { app } = await buildApp(okPool, cfg);
    for (const p of ['/api/v1/control', '/api/v1/vehicles/x/control', '/api/v1/telematics/x/control']) {
      expect((await app.inject({ method: 'POST', url: p })).statusCode).toBe(404);
    }
    const idx = await app.inject('/');
    expect(idx.statusCode).toBe(200);
    expect(idx.body).toContain('leaflet@1.9.4');
    expect((await app.inject('/app.js')).statusCode).toBe(200);
  });
  it('rejects websocket upgrade without a valid token', async () => {
    const { app } = await buildApp(okPool, cfg);
    expect((await app.inject('/ws')).statusCode).toBe(401);
    expect((await app.inject('/ws?token=bad')).statusCode).toBe(401);
  });
});
