import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { badRequest } from '../errors';

const NUM = /^-?\d+(\.\d+)?$/;

/** Parses "lon,lat;lon,lat[;...]" (2-25 points) into validated numbers. */
export function parseCoords(raw: string): Array<[number, number]> {
  const parts = raw.split(';');
  if (parts.length < 2 || parts.length > 25) throw badRequest('coords must contain 2 to 25 lon,lat pairs separated by ;');
  return parts.map((p) => {
    const xy = p.split(',');
    if (xy.length !== 2 || !NUM.test(xy[0]) || !NUM.test(xy[1])) throw badRequest(`Invalid coordinate "${p.slice(0, 40)}", expected lon,lat`);
    const lon = Number(xy[0]);
    const lat = Number(xy[1]);
    if (lon < -180 || lon > 180 || lat < -90 || lat > 90) throw badRequest(`Coordinate out of range: ${lon},${lat}`);
    return [lon, lat];
  });
}

const querySchema = z.object({
  coords: z.string().min(1).max(1000),
  profile: z.literal('driving').default('driving'),
});

const OSRM_TIMEOUT_MS = 10_000;

export default async function routeRoutes(app: FastifyInstance, ctx: Ctx) {
  app.get(
    '/route',
    { preHandler: app.authenticate, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const q = querySchema.parse(req.query);
      const coords = parseCoords(q.coords);
      const base = ctx.config.osrmUrl?.replace(/\/+$/, '');
      const unavailable = () => reply.code(503).send({ error: 'routing_unavailable', message: 'Routing service is not available' });
      if (!base) return unavailable();

      const path = coords.map(([lon, lat]) => `${lon},${lat}`).join(';');
      let res: Response;
      let body: unknown;
      try {
        res = await fetch(`${base}/route/v1/driving/${path}?overview=full&geometries=geojson&steps=true`, {
          signal: AbortSignal.timeout(OSRM_TIMEOUT_MS),
          headers: { accept: 'application/json' },
        });
        body = await res.json();
      } catch (err) {
        req.log.warn({ err }, 'OSRM request failed');
        return unavailable();
      }
      // OSRM answers 400 for NoRoute/NoSegment/InvalidQuery etc. with a JSON body: pass it through.
      if (res.status >= 500) return unavailable();
      if (!res.ok) return reply.code(400).send(body);
      return reply.send(body);
    },
  );
}
