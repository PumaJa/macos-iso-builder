import { describe, expect, it } from 'vitest';
import { generateGroupCode, isValidCode, normalizeCode } from '../src/logic/groupCode';

describe('group codes', () => {
  it('are 6 chars A-Z0-9', () => {
    for (let i = 0; i < 500; i++) expect(generateGroupCode()).toMatch(/^[A-Z0-9]{6}$/);
  });
  it('are random (no collisions in a small sample)', () => {
    const s = new Set(Array.from({ length: 500 }, () => generateGroupCode()));
    expect(s.size).toBeGreaterThan(495);
  });
  it('uses the injected RNG deterministically', () => {
    expect(generateGroupCode(() => 0)).toBe('AAAAAA');
    expect(generateGroupCode(() => 35)).toBe('999999');
  });
  it('are case-insensitive', () => {
    expect(normalizeCode(' ab12cd ')).toBe('AB12CD');
    expect(isValidCode('ab12cd')).toBe(true);
    expect(isValidCode('ab12c')).toBe(false);
    expect(isValidCode('ab-2cd')).toBe(false);
  });
});
