import { describe, expect, it } from 'vitest';
import { geofenceSchema, registerSchema, stateBatchSchema, vehiclePutSchema, vehicleStateSchema, settingsSchema, lapSchema } from '../src/logic/validation';
import { loadConfig } from '../src/config';

const state = { ts: 1700000000000, lat: 48.1, lon: 11.5, vescConnected: true, source: 'measured' as const };

describe('validation', () => {
  it('register requires password >= 8 and sane username', () => {
    expect(registerSchema.safeParse({ username: 'bob_1', password: '12345678', displayName: 'Bob' }).success).toBe(true);
    expect(registerSchema.safeParse({ username: 'bob', password: '1234567', displayName: 'Bob' }).success).toBe(false);
    expect(registerSchema.safeParse({ username: 'b o b', password: '12345678', displayName: 'Bob' }).success).toBe(false);
    expect(registerSchema.safeParse({ username: 'bob', password: '12345678', displayName: '' }).success).toBe(false);
  });
  it('vehicleState: required fields, ranges, lat/lon pairing', () => {
    expect(vehicleStateSchema.safeParse(state).success).toBe(true);
    expect(vehicleStateSchema.safeParse({ ...state, source: 'fake' }).success).toBe(false);
    expect(vehicleStateSchema.safeParse({ ...state, lat: 91 }).success).toBe(false);
    expect(vehicleStateSchema.safeParse({ ...state, socPct: 101 }).success).toBe(false);
    expect(vehicleStateSchema.safeParse({ ts: 1, vescConnected: false, source: 'simulated' }).success).toBe(true);
    expect(vehicleStateSchema.safeParse({ ...state, lon: undefined }).success).toBe(false);
    expect(vehicleStateSchema.safeParse({ ...state, vescConnected: undefined }).success).toBe(false);
  });
  it('state batch is limited to 500', () => {
    expect(stateBatchSchema.safeParse({ states: Array(500).fill(state) }).success).toBe(true);
    expect(stateBatchSchema.safeParse({ states: Array(501).fill(state) }).success).toBe(false);
    expect(stateBatchSchema.safeParse({ states: [] }).success).toBe(false);
  });
  it('geofence body', () => {
    expect(geofenceSchema.safeParse({ name: 'Home', lat: 1, lon: 2, radiusM: 100, alertOn: 'exit' }).success).toBe(true);
    expect(geofenceSchema.safeParse({ name: 'Home', lat: 1, lon: 2, radiusM: 0, alertOn: 'exit' }).success).toBe(false);
    expect(geofenceSchema.safeParse({ name: 'Home', lat: 1, lon: 2, radiusM: 5, alertOn: 'leave' }).success).toBe(false);
  });
  it('vehicle put keeps free fields, requires name', () => {
    const r = vehiclePutSchema.parse({ name: 'E-Board', type: 'board', make: 'x', model: 'y', batteryS: 12 });
    expect((r as Record<string, unknown>).batteryS).toBe(12);
    expect(vehiclePutSchema.safeParse({ type: 'board' }).success).toBe(false);
  });
  it('settings / lap validation', () => {
    expect(settingsSchema.safeParse({ lowBatteryPct: 20, maintenanceKm: 500, moveAlert: true }).success).toBe(true);
    expect(settingsSchema.safeParse({ lowBatteryPct: 120 }).success).toBe(false);
    expect(lapSchema.safeParse({ lapMs: 61234, sectorsMs: [20000, 41234], gpsHz: 10, ts: 1700000000000 }).success).toBe(true);
    expect(lapSchema.safeParse({ lapMs: -1, ts: 1 }).success).toBe(false);
  });
  it('config requires JWT_SECRET', () => {
    expect(() => loadConfig({})).toThrow(/JWT_SECRET/);
    expect(() => loadConfig({ JWT_SECRET: 'short' })).toThrow();
    const c = loadConfig({ JWT_SECRET: 'x'.repeat(32) });
    expect(c.port).toBe(8080);
    expect(c.dataDir).toBe('/data');
  });
});
