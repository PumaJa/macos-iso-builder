import type { Pool } from 'pg';
import { forbidden, notFound } from '../errors';
import { canAccessTelematics } from '../logic/access';
import { uuidSchema } from '../logic/validation';

/** Loads the vehicle and enforces owner-only access (404 if missing/invalid id, 403 if not the owner). */
export async function requireOwnedVehicle(pool: Pool, vehicleId: string, userId: string) {
  const id = uuidSchema.safeParse(vehicleId);
  if (!id.success) throw notFound('Vehicle not found');
  const v = (await pool.query('SELECT id, owner_id AS "ownerId", name FROM vehicles WHERE id = $1', [id.data])).rows[0];
  if (!v) throw notFound('Vehicle not found');
  if (!canAccessTelematics(v.ownerId, userId)) throw forbidden('Only the vehicle owner can access its telematics');
  return v as { id: string; ownerId: string; name: string };
}
