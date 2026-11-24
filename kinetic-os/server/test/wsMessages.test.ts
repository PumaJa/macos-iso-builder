import { describe, expect, it } from 'vitest';
import { base64DecodedSize, parseClientMessage, RateLimiter } from '../src/logic/wsMessages';

const gid = '3f8e6d52-7a2b-4c1e-9d0a-1b2c3d4e5f60';
const parse = (o: unknown) => parseClientMessage(JSON.stringify(o));

describe('ws message validation', () => {
  it('rejects non-JSON, missing type and unknown types', () => {
    expect(parseClientMessage('nope')).toMatchObject({ ok: false, error: 'bad_json' });
    expect(parse({ a: 1 })).toMatchObject({ ok: false, error: 'bad_message' });
    expect(parse({ type: 'motor_control', rpm: 1 })).toMatchObject({ ok: false, error: 'unknown_type' });
  });
  it('accepts valid join/position/chat', () => {
    expect(parse({ type: 'join_group', groupId: gid }).ok).toBe(true);
    expect(parse({ type: 'position', groupId: gid, lat: 1, lon: 2, ts: 1700000000000, telemetry: { socPct: 50 } }).ok).toBe(true);
    expect(parse({ type: 'chat', groupId: gid, text: 'hi' }).ok).toBe(true);
    expect(parse({ type: 'pong' }).ok).toBe(true);
  });
  it('rejects bad group ids, coordinates and oversized chat', () => {
    expect(parse({ type: 'join_group', groupId: 'x' })).toMatchObject({ ok: false, error: 'validation_error' });
    expect(parse({ type: 'position', groupId: gid, lat: 100, lon: 2, ts: 1 }).ok).toBe(false);
    expect(parse({ type: 'chat', groupId: gid, text: 'a'.repeat(1001) }).ok).toBe(false);
    expect(parse({ type: 'chat', groupId: gid, text: 'a'.repeat(1000) }).ok).toBe(true);
    expect(parse({ type: 'chat', groupId: gid, text: '' }).ok).toBe(false);
  });
  it('voice: mime, duration and size limits', () => {
    const ok = { type: 'voice', groupId: gid, mime: 'audio/mp4', durationMs: 5000, dataBase64: 'AAAA' };
    expect(parse(ok).ok).toBe(true);
    expect(parse({ ...ok, mime: 'audio/wav' }).ok).toBe(false);
    expect(parse({ ...ok, durationMs: 60001 }).ok).toBe(false);
    expect(parse({ ...ok, dataBase64: '***' }).ok).toBe(false);
    expect(parse({ ...ok, dataBase64: 'A'.repeat(1_400_000) }).ok).toBe(false); // ~1.05 MB decoded
    expect(base64DecodedSize('AAAA')).toBe(3);
    expect(base64DecodedSize('AA==')).toBe(1);
  });
  it('vehicle_state embeds full state validation; subscribe needs uuid', () => {
    const state = { ts: 1, vescConnected: false, source: 'simulated' };
    expect(parse({ type: 'vehicle_state', vehicleId: gid, state }).ok).toBe(true);
    expect(parse({ type: 'vehicle_state', vehicleId: gid, state: { ts: 1 } }).ok).toBe(false);
    expect(parse({ type: 'subscribe_vehicle', vehicleId: gid }).ok).toBe(true);
    expect(parse({ type: 'subscribe_vehicle', vehicleId: 5 }).ok).toBe(false);
  });
  it('help_request requires coordinates', () => {
    expect(parse({ type: 'help_request', groupId: gid, lat: 1, lon: 1, message: 'flat tire' }).ok).toBe(true);
    expect(parse({ type: 'help_request', groupId: gid, message: 'x' }).ok).toBe(false);
  });
});

describe('RateLimiter (5 Hz)', () => {
  it('allows one message per 200 ms', () => {
    const r = new RateLimiter(200);
    expect(r.allow(1000)).toBe(true);
    expect(r.allow(1100)).toBe(false);
    expect(r.allow(1200)).toBe(true);
    expect(r.allow(1399)).toBe(false);
  });
});
