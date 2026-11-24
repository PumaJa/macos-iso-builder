import { randomInt } from 'node:crypto';

export const CODE_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
export const CODE_RE = /^[A-Z0-9]{6}$/;

export function generateGroupCode(rand: (max: number) => number = randomInt): string {
  let s = '';
  for (let i = 0; i < 6; i++) s += CODE_ALPHABET[rand(CODE_ALPHABET.length)];
  return s;
}
/** Codes are case-insensitive: canonical form is upper case. */
export const normalizeCode = (c: string) => c.trim().toUpperCase();
export const isValidCode = (c: string) => CODE_RE.test(normalizeCode(c));
