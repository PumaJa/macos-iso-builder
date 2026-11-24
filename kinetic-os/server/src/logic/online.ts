export const ONLINE_WINDOW_MS = 60_000;
/** online = last state received < 60 s ago AND vescConnected. */
export function computeOnline(receivedAtMs: number | null | undefined, vescConnected: boolean, nowMs: number = Date.now()): boolean {
  if (receivedAtMs == null) return false;
  const age = nowMs - receivedAtMs;
  return vescConnected === true && age >= 0 && age < ONLINE_WINDOW_MS;
}
