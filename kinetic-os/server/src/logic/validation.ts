import { z } from 'zod';

export const usernameSchema = z
  .string()
  .trim()
  .regex(/^[A-Za-z0-9_.-]{3,32}$/, 'username must be 3-32 chars: letters, digits, _ . -');

export const registerSchema = z.object({
  username: usernameSchema,
  password: z.string().min(8, 'password must be at least 8 characters').max(72),
  displayName: z.string().trim().min(1).max(64),
});
export const loginSchema = z.object({ username: z.string().trim().min(1).max(64), password: z.string().min(1).max(200) });
export const usernameBody = z.object({ username: z.string().trim().min(1).max(64) });

export const groupCreateSchema = z.object({
  name: z.string().trim().min(1).max(80),
  visibility: z.enum(['public', 'private']),
});
export const joinSchema = z.object({ code: z.string().trim().length(6) });
export const routeSchema = z.object({ name: z.string().trim().min(1).max(120), gpx: z.string().min(1).max(5_000_000) });

export const uuidSchema = z.string().uuid();

export const vehiclePutSchema = z
  .object({
    name: z.string().trim().min(1).max(80),
    type: z.string().max(40).optional(),
    make: z.string().max(80).optional(),
    model: z.string().max(80).optional(),
  })
  .passthrough();
/** keys that free-form fields may never overwrite */
export const RESERVED_VEHICLE_KEYS = new Set(['id', 'ownerId', 'photoUrl', 'createdAt', 'updatedAt', 'name', 'type', 'make', 'model']);

const num = z.number().finite();
export const vehicleStateSchema = z
  .object({
    ts: z.number().int().positive(),
    lat: num.min(-90).max(90).optional(),
    lon: num.min(-180).max(180).optional(),
    alt: num.optional(),
    speedKmh: num.min(-1000).max(1000).optional(),
    headingDeg: num.min(-360).max(720).optional(),
    socPct: num.min(0).max(100).optional(),
    voltage: num.min(0).max(1000).optional(),
    powerW: num.min(-1e6).max(1e6).optional(),
    tempMosC: num.min(-100).max(500).optional(),
    tempMotorC: num.min(-100).max(500).optional(),
    odometerKm: num.min(0).optional(),
    faultCode: z.number().int().optional(),
    vescConnected: z.boolean(),
    charging: z.boolean().optional(),
    source: z.enum(['measured', 'simulated']),
  })
  .refine((s) => (s.lat === undefined) === (s.lon === undefined), { message: 'lat and lon must be given together' });
export type VehicleState = z.infer<typeof vehicleStateSchema>;

export const stateBatchSchema = z.object({ states: z.array(vehicleStateSchema).min(1).max(500) });

export const geofenceSchema = z.object({
  name: z.string().trim().min(1).max(80),
  lat: num.min(-90).max(90),
  lon: num.min(-180).max(180),
  radiusM: num.min(1).max(100000),
  alertOn: z.enum(['exit', 'enter', 'both']),
});

export const settingsSchema = z.object({
  lowBatteryPct: num.min(0).max(100).optional(),
  maintenanceKm: num.min(0).max(1e6).optional(),
  moveAlert: z.boolean().optional(),
  parked: z.boolean().optional(),
});

export const rideSchema = z.object({
  id: z.string().uuid(),
  vehicleId: z.string().uuid(),
  startedAt: z.number().int().positive(),
  endedAt: z.number().int().positive(),
  distanceM: num.min(0),
  maxSpeedKmh: num.min(0),
  energyWh: num,
  gpx: z.string().max(10_000_000).optional(),
  summary: z.record(z.unknown()).optional(),
});

const sectorLine = z.object({ lat1: num, lon1: num, lat2: num, lon2: num });
export const trackSchema = z.object({
  name: z.string().trim().min(1).max(80),
  startLine: z.object({ lat1: num.min(-90).max(90), lon1: num.min(-180).max(180), lat2: num.min(-90).max(90), lon2: num.min(-180).max(180) }),
  sectors: z.array(sectorLine.passthrough()).max(50).default([]),
});
export const lapSchema = z.object({
  lapMs: z.number().int().positive().max(86_400_000),
  sectorsMs: z.array(z.number().int().nonnegative()).max(50).default([]),
  vehicleId: z.string().uuid().optional(),
  gpsHz: num.min(0).max(1000).optional(),
  ts: z.number().int().positive(),
});

export function parseOrThrow<T>(schema: z.ZodType<T, z.ZodTypeDef, unknown>, data: unknown): T {
  return schema.parse(data);
}
