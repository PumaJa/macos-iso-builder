import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';

export const TOKEN_TTL_SECONDS = 30 * 24 * 3600;
const COST = 10;

export const hashPassword = (pw: string) => bcrypt.hash(pw, COST);
export const verifyPassword = (pw: string, hash: string) => bcrypt.compare(pw, hash);

export function signToken(userId: string, secret: string, now = Date.now()): string {
  return jwt.sign({ sub: userId, iat: Math.floor(now / 1000) }, secret, { algorithm: 'HS256', expiresIn: TOKEN_TTL_SECONDS });
}
/** Returns the user id, or null when the token is missing/invalid/expired. */
export function verifyToken(token: string | undefined | null, secret: string): string | null {
  if (!token) return null;
  try {
    const p = jwt.verify(token, secret, { algorithms: ['HS256'] });
    return typeof p === 'object' && typeof p.sub === 'string' ? p.sub : null;
  } catch {
    return null;
  }
}
export function bearerToken(header: string | undefined): string | null {
  const m = /^Bearer\s+(.+)$/i.exec(header ?? '');
  return m ? m[1].trim() : null;
}
