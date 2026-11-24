import fs from 'node:fs';
import path from 'node:path';
import { Pool } from 'pg';

export function createPool(databaseUrl: string): Pool {
  return new Pool({ connectionString: databaseUrl, max: 10 });
}

/** SQL fragment: timestamptz column -> epoch milliseconds as a JS number. */
export const ms = (col: string) => `floor(extract(epoch from ${col}) * 1000)::float8`;

export function defaultMigrationsDir(): string {
  // dist/db.js or src/db.ts -> ../migrations
  return path.resolve(__dirname, '..', 'migrations');
}

/** Simple forward-only migrations: migrations/*.sql applied in name order, tracked in schema_migrations. */
export async function migrate(pool: Pool, dir = defaultMigrationsDir(), log: (m: string) => void = () => {}): Promise<string[]> {
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  const client = await pool.connect();
  const applied: string[] = [];
  try {
    await client.query('SELECT pg_advisory_lock(727274)');
    await client.query(
      'CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())',
    );
    const done = new Set((await client.query('SELECT name FROM schema_migrations')).rows.map((r) => r.name as string));
    for (const f of files) {
      if (done.has(f)) continue;
      const sql = fs.readFileSync(path.join(dir, f), 'utf8');
      try {
        await client.query('BEGIN');
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations(name) VALUES ($1)', [f]);
        await client.query('COMMIT');
      } catch (e) {
        await client.query('ROLLBACK');
        throw new Error(`Migration ${f} failed: ${(e as Error).message}`);
      }
      applied.push(f);
      log(`applied migration ${f}`);
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock(727274)').catch(() => {});
    client.release();
  }
  return applied;
}
