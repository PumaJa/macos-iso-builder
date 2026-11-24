import { z } from 'zod';

const schema = z.object({
  JWT_SECRET: z.string().min(16, 'JWT_SECRET must be at least 16 characters'),
  DATABASE_URL: z.string().min(1).default('postgres://kinetic:kinetic@db:5432/kinetic'),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  HOST: z.string().default('0.0.0.0'),
  DATA_DIR: z.string().default('/data'),
  TRUST_PROXY: z.enum(['0', '1', 'true', 'false']).default('0'),
  MIGRATIONS_DIR: z.string().optional(),
  PUBLIC_DIR: z.string().optional(),
});

export interface Config {
  jwtSecret: string;
  databaseUrl: string;
  port: number;
  host: string;
  dataDir: string;
  trustProxy: boolean;
  migrationsDir?: string;
  publicDir?: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const r = schema.safeParse(env);
  if (!r.success) {
    const msg = r.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
    throw new Error(`Invalid configuration: ${msg}`);
  }
  const e = r.data;
  return {
    jwtSecret: e.JWT_SECRET,
    databaseUrl: e.DATABASE_URL,
    port: e.PORT,
    host: e.HOST,
    dataDir: e.DATA_DIR,
    trustProxy: e.TRUST_PROXY === '1' || e.TRUST_PROXY === 'true',
    migrationsDir: e.MIGRATIONS_DIR,
    publicDir: e.PUBLIC_DIR,
  };
}
