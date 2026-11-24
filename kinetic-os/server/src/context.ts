import type { Pool } from 'pg';
import type { Config } from './config';
import type { Hub } from './hub';

export interface Ctx {
  pool: Pool;
  config: Config;
  hub: Hub;
}

declare module 'fastify' {
  interface FastifyRequest {
    userId: string;
  }
}
