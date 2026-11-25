import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Pool } from 'pg';
import { buildApp } from '../src/app';
import { signToken } from '../src/logic/auth';
import { parseCoords } from '../src/routes/route';

process.env.NODE_ENV = 'test';
const base = { jwtSecret: 'route-test-secret-0123456789', databaseUrl: 'x', port: 0, host: '127.0.0.1', dataDir: '/tmp/k', trustProxy: false };
const pool = { query: async () => ({ rows: [], rowCount: 0 }) } as unknown as Pool;
const auth = { authorization: `Bearer ${signToken('00000000-0000-4000-8000-000000000001', base.jwtSecret)}` };
const OSRM = { code: 'Ok', routes: [{ distance: 1234.5, duration: 99.1, geometry: { type: 'LineString', coordinates: [[9.5, 47.1], [9.6, 47.2]] }, legs: [{ steps: [{}, {}] }] }], waypoints: [] };

afterEach(() => vi.unstubAllGlobals());

const mockFetch = (impl: (url: string) => Promise<Response>) => {
  const f = vi.fn((url: string) => impl(url));
  vi.stubGlobal('fetch', f);
  return f;
};
const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'content-type': 'application/json' } });

describe('parseCoords', () => {
  it('accepts valid pairs and rejects bad input', () => {
    expect(parseCoords('9.5,47.1;9.6,47.2')).toEqual([[9.5, 47.1], [9.6, 47.2]]);
    expect(() => parseCoords('9.5,47.1')).toThrow();
    expect(() => parseCoords('181,0;0,0')).toThrow();
    expect(() => parseCoords('0,91;0,0')).toThrow();
    expect(() => parseCoords('a,b;0,0')).toThrow();
    expect(() => parseCoords('1,2,3;0,0')).toThrow();
    expect(() => parseCoords('1e3,2;0,0')).toThrow();
    expect(() => parseCoords(Array(26).fill('1,1').join(';'))).toThrow();
    expect(parseCoords(Array(25).fill('1,1').join(';'))).toHaveLength(25);
  });
});

describe('GET /api/v1/route', () => {
  it('requires auth', async () => {
    const { app } = await buildApp(pool, { ...base, osrmUrl: 'http://osrm:5000' });
    expect((await app.inject('/api/v1/route?coords=1,1;2,2')).statusCode).toBe(401);
  });

  it('proxies to OSRM and returns its JSON unchanged', async () => {
    const f = mockFetch(async () => json(OSRM));
    const { app } = await buildApp(pool, { ...base, osrmUrl: 'http://osrm:5000/' });
    const r = await app.inject({ url: '/api/v1/route?coords=9.5,47.1;9.6,47.2&profile=driving', headers: auth });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual(OSRM);
    expect(f.mock.calls[0][0]).toBe('http://osrm:5000/route/v1/driving/9.5,47.1;9.6,47.2?overview=full&geometries=geojson&steps=true');
  });

  it('returns 400 for invalid input without calling OSRM', async () => {
    const f = mockFetch(async () => json(OSRM));
    const { app } = await buildApp(pool, { ...base, osrmUrl: 'http://osrm:5000' });
    for (const qs of ['', 'coords=1,1', 'coords=200,1;2,2', 'coords=1,1;2,2&profile=flying', 'coords=1,1;x']) {
      const r = await app.inject({ url: `/api/v1/route?${qs}`, headers: auth });
      expect(r.statusCode, qs).toBe(400);
    }
    expect(f).not.toHaveBeenCalled();
  });

  it('503 routing_unavailable when not configured', async () => {
    const f = mockFetch(async () => json(OSRM));
    const { app } = await buildApp(pool, base);
    const r = await app.inject({ url: '/api/v1/route?coords=1,1;2,2', headers: auth });
    expect(r.statusCode).toBe(503);
    expect(r.json().error).toBe('routing_unavailable');
    expect(f).not.toHaveBeenCalled();
  });

  it('503 when OSRM is unreachable or returns 5xx', async () => {
    const { app } = await buildApp(pool, { ...base, osrmUrl: 'http://osrm:5000' });
    mockFetch(async () => { throw new TypeError('fetch failed'); });
    expect((await app.inject({ url: '/api/v1/route?coords=1,1;2,2', headers: auth })).json().error).toBe('routing_unavailable');
    mockFetch(async () => json({ code: 'x' }, 502));
    expect((await app.inject({ url: '/api/v1/route?coords=1,1;2,2', headers: auth })).statusCode).toBe(503);
  });

  it('passes OSRM 4xx (NoRoute/NoSegment) through as 400 with the OSRM body', async () => {
    const body = { code: 'NoSegment', message: 'Could not find a matching segment' };
    mockFetch(async () => json(body, 400));
    const { app } = await buildApp(pool, { ...base, osrmUrl: 'http://osrm:5000' });
    const r = await app.inject({ url: '/api/v1/route?coords=1,1;2,2', headers: auth });
    expect(r.statusCode).toBe(400);
    expect(r.json()).toEqual(body);
  });
});
