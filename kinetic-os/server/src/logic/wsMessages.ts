import { z } from 'zod';
import { vehicleStateSchema } from './validation';

export const MAX_VOICE_BYTES = 1_000_000;
export const MAX_VOICE_MS = 60_000;
export const MAX_CHAT_CHARS = 1000;

const id = z.string().uuid();
const num = z.number().finite();
const lat = num.min(-90).max(90);
const lon = num.min(-180).max(180);

/** decoded size of a base64 string without decoding it */
export function base64DecodedSize(b64: string): number {
  const pad = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0;
  return Math.floor((b64.length * 3) / 4) - pad;
}

export const clientMessageSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('pong') }),
  z.object({ type: z.literal('join_group'), groupId: id }),
  z.object({ type: z.literal('leave_group'), groupId: id }),
  z.object({
    type: z.literal('position'),
    groupId: id,
    lat,
    lon,
    alt: num.optional(),
    speedKmh: num.optional(),
    headingDeg: num.optional(),
    ts: z.number().int().positive(),
    telemetry: z.object({ socPct: num.min(0).max(100).optional(), powerW: num.optional() }).optional(),
  }),
  z.object({ type: z.literal('chat'), groupId: id, text: z.string().min(1).max(MAX_CHAT_CHARS) }),
  z.object({
    type: z.literal('voice'),
    groupId: id,
    mime: z.literal('audio/mp4'),
    durationMs: z.number().int().positive().max(MAX_VOICE_MS),
    dataBase64: z
      .string()
      .min(4)
      .regex(/^[A-Za-z0-9+/]+={0,2}$/, 'invalid base64')
      .refine((s) => base64DecodedSize(s) <= MAX_VOICE_BYTES, 'voice message larger than 1 MB'),
  }),
  z.object({ type: z.literal('help_request'), groupId: id, lat, lon, message: z.string().max(500) }),
  z.object({ type: z.literal('subscribe_vehicle'), vehicleId: id }),
  z.object({ type: z.literal('vehicle_state'), vehicleId: id, state: vehicleStateSchema }),
]);
export type ClientMessage = z.infer<typeof clientMessageSchema>;

export type ParseResult = { ok: true; msg: ClientMessage } | { ok: false; error: string; message: string };

export function parseClientMessage(raw: string): ParseResult {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return { ok: false, error: 'bad_json', message: 'Frame is not valid JSON' };
  }
  if (typeof json !== 'object' || json === null || typeof (json as { type?: unknown }).type !== 'string') {
    return { ok: false, error: 'bad_message', message: 'Missing "type"' };
  }
  const r = clientMessageSchema.safeParse(json);
  if (!r.success) {
    const known = clientMessageSchema.options.some((o) => o.shape.type.value === (json as { type: string }).type);
    return known
      ? { ok: false, error: 'validation_error', message: r.error.issues.map((i) => `${i.path.join('.') || 'message'}: ${i.message}`).join('; ') }
      : { ok: false, error: 'unknown_type', message: `Unknown message type "${(json as { type: string }).type}"` };
  }
  return { ok: true, msg: r.data };
}

/** Token-less minimum-interval limiter (default 5 Hz). */
/** Token bucket for all message types: `rate` messages/s sustained, bursts up to `burst`. */
export class TokenBucket {
  private tokens: number;
  private last: number;
  constructor(private rate = 10, private burst = 20, now = Date.now()) { this.tokens = burst; this.last = now; }
  take(cost = 1, now = Date.now()): boolean {
    this.tokens = Math.min(this.burst, this.tokens + ((now - this.last) / 1000) * this.rate);
    this.last = now;
    if (this.tokens < cost) return false;
    this.tokens -= cost;
    return true;
  }
}

/** Plausible client timestamps: not before 2020, not more than 10 min in the future (bad clocks would freeze "latest"). */
export const MIN_TS = Date.UTC(2020, 0, 1);
export const MAX_FUTURE_MS = 10 * 60_000;
export const plausibleTs = (ts: number, now = Date.now()) => ts >= MIN_TS && ts <= now + MAX_FUTURE_MS;

export class RateLimiter {
  private last = 0;
  constructor(private minIntervalMs = 200) {}
  allow(now = Date.now()): boolean {
    if (now - this.last < this.minIntervalMs) return false;
    this.last = now;
    return true;
  }
}
