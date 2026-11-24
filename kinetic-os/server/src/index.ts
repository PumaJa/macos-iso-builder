import { buildApp } from './app';
import { loadConfig } from './config';
import { createPool, migrate } from './db';

async function main() {
  const config = loadConfig();
  const pool = createPool(config.databaseUrl);
  // wait for the database (compose healthcheck normally guarantees it, but be tolerant)
  for (let i = 0; ; i++) {
    try {
      await pool.query('SELECT 1');
      break;
    } catch (e) {
      if (i >= 30) throw e;
      console.log('waiting for database...');
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  await migrate(pool, config.migrationsDir, (m) => console.log(m));
  const { app } = await buildApp(pool, config);
  await app.listen({ port: config.port, host: config.host });
  const stop = async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
