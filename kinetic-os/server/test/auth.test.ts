import jwt from 'jsonwebtoken';
import { describe, expect, it } from 'vitest';
import { bearerToken, hashPassword, signToken, TOKEN_TTL_SECONDS, verifyPassword, verifyToken } from '../src/logic/auth';

const SECRET = 'unit-test-secret-0123456789';

describe('auth', () => {
  it('hashes and verifies passwords', async () => {
    const h = await hashPassword('correct horse');
    expect(h).not.toContain('correct horse');
    expect(await verifyPassword('correct horse', h)).toBe(true);
    expect(await verifyPassword('wrong', h)).toBe(false);
  });
  it('signs HS256 tokens valid for 30 days', () => {
    const t = signToken('user-1', SECRET);
    const dec = jwt.decode(t, { complete: true })!;
    expect(dec.header.alg).toBe('HS256');
    const p = dec.payload as jwt.JwtPayload;
    expect(p.exp! - p.iat!).toBe(TOKEN_TTL_SECONDS);
    expect(TOKEN_TTL_SECONDS).toBe(30 * 86400);
    expect(verifyToken(t, SECRET)).toBe('user-1');
  });
  it('rejects wrong secret, garbage, missing, expired and alg=none tokens', () => {
    const t = signToken('user-1', SECRET);
    expect(verifyToken(t, 'other-secret-other-secret')).toBeNull();
    expect(verifyToken('garbage', SECRET)).toBeNull();
    expect(verifyToken(undefined, SECRET)).toBeNull();
    const old = jwt.sign({ sub: 'u' }, SECRET, { expiresIn: -10 });
    expect(verifyToken(old, SECRET)).toBeNull();
    const none = jwt.sign({ sub: 'u' }, '', { algorithm: 'none' });
    expect(verifyToken(none, SECRET)).toBeNull();
  });
  it('extracts bearer tokens', () => {
    expect(bearerToken('Bearer abc.def')).toBe('abc.def');
    expect(bearerToken('bearer abc')).toBe('abc');
    expect(bearerToken('Basic abc')).toBeNull();
    expect(bearerToken(undefined)).toBeNull();
  });
});
