import { describe, expect, it } from 'vitest';
import { canAccessTelematics, canDeleteGroup, canReadGroup, canReadVehiclePhoto } from '../src/logic/access';
import { computeOnline } from '../src/logic/online';

describe('access rules', () => {
  it('non-member cannot read a private group; member and public are fine', () => {
    expect(canReadGroup({ visibility: 'private', isMember: false })).toBe(false);
    expect(canReadGroup({ visibility: 'private', isMember: true })).toBe(true);
    expect(canReadGroup({ visibility: 'public', isMember: false })).toBe(true);
  });
  it('only the owner can access telematics', () => {
    expect(canAccessTelematics('u1', 'u1')).toBe(true);
    expect(canAccessTelematics('u1', 'u2')).toBe(false);
  });
  it('photo: owner or group-sharing user only', () => {
    expect(canReadVehiclePhoto('u1', 'u1', false)).toBe(true);
    expect(canReadVehiclePhoto('u1', 'u2', true)).toBe(true);
    expect(canReadVehiclePhoto('u1', 'u2', false)).toBe(false);
  });
  it('only the owner can delete a group', () => {
    expect(canDeleteGroup('u1', 'u1')).toBe(true);
    expect(canDeleteGroup('u1', 'u2')).toBe(false);
  });
});

describe('online computation', () => {
  const now = 1_000_000;
  it('online only if < 60 s old AND vescConnected', () => {
    expect(computeOnline(now - 1000, true, now)).toBe(true);
    expect(computeOnline(now - 59_999, true, now)).toBe(true);
    expect(computeOnline(now - 60_000, true, now)).toBe(false);
    expect(computeOnline(now - 1000, false, now)).toBe(false);
    expect(computeOnline(null, true, now)).toBe(false);
  });
});
