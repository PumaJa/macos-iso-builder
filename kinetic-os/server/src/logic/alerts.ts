export type AlertKind = 'geofence_exit' | 'geofence_enter' | 'low_battery' | 'fault' | 'movement' | 'maintenance';
export interface AlertDraft {
  ts: number;
  kind: AlertKind;
  message: string;
}
export interface StateLike {
  ts: number;
  lat?: number;
  lon?: number;
  socPct?: number;
  odometerKm?: number;
  faultCode?: number;
  vescConnected: boolean;
  source: 'measured' | 'simulated';
}
export interface AlertSettings {
  lowBatteryPct: number;
  maintenanceKm: number;
  moveAlert: boolean;
  /** explicit "parked" flag: movement is watched even if the VESC is connected */
  parked: boolean;
}
export interface Tracking {
  lowBatteryActive: boolean;
  lastFault: number | null;
  anchor: { lat: number; lon: number } | null;
  lastMaintKm: number | null;
  /** previous inside/outside per geofence id; missing/null = unknown */
  fenceInside: Record<string, boolean | null>;
}
export interface FenceCheck {
  id: string;
  name: string;
  alertOn: 'exit' | 'enter' | 'both';
  /** result of ST_DWithin for the current position */
  inside: boolean;
}

export const MOVEMENT_THRESHOLD_M = 50;
export const LOW_BATTERY_HYSTERESIS = 3;

export function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371008.8;
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Pure geofence transition logic. Unknown previous state only records, never alerts. */
export function geofenceTransitions(
  fences: FenceCheck[],
  prev: Record<string, boolean | null>,
  ts: number,
): { alerts: AlertDraft[]; next: Record<string, boolean | null> } {
  const alerts: AlertDraft[] = [];
  const next = { ...prev };
  for (const f of fences) {
    const was = prev[f.id];
    if (was === true && f.inside === false && (f.alertOn === 'exit' || f.alertOn === 'both')) {
      alerts.push({ ts, kind: 'geofence_exit', message: `Left geofence "${f.name}"` });
    } else if (was === false && f.inside === true && (f.alertOn === 'enter' || f.alertOn === 'both')) {
      alerts.push({ ts, kind: 'geofence_enter', message: `Entered geofence "${f.name}"` });
    }
    next[f.id] = f.inside;
  }
  return { alerts, next };
}

export const emptyTracking = (): Tracking => ({
  lowBatteryActive: false,
  lastFault: null,
  anchor: null,
  lastMaintKm: null,
  fenceInside: {},
});

/** Evaluate one state sample. Pure: returns the alerts raised and the updated tracking. */
export function evaluateState(
  state: StateLike,
  settings: AlertSettings,
  tracking: Tracking,
  fences: FenceCheck[],
): { alerts: AlertDraft[]; tracking: Tracking } {
  const alerts: AlertDraft[] = [];
  const t: Tracking = { ...tracking, anchor: tracking.anchor ? { ...tracking.anchor } : null };
  const prefix = state.source === 'simulated' ? '[SIMULATION] ' : '';
  const push = (kind: AlertKind, message: string) => alerts.push({ ts: state.ts, kind, message: prefix + message });

  // geofences
  if (state.lat !== undefined && state.lon !== undefined) {
    const g = geofenceTransitions(fences, tracking.fenceInside, state.ts);
    for (const a of g.alerts) push(a.kind, a.message);
    t.fenceInside = g.next;
  }

  // low battery (with hysteresis so it fires once per discharge)
  if (state.socPct !== undefined) {
    if (state.socPct <= settings.lowBatteryPct) {
      if (!t.lowBatteryActive) {
        t.lowBatteryActive = true;
        push('low_battery', `Low battery: ${Math.round(state.socPct)}% (limit ${settings.lowBatteryPct}%)`);
      }
    } else if (state.socPct > settings.lowBatteryPct + LOW_BATTERY_HYSTERESIS) {
      t.lowBatteryActive = false;
    }
  }

  // fault codes: alert on each new non-zero code, re-arm when cleared
  if (state.faultCode !== undefined) {
    if (state.faultCode !== 0) {
      if (t.lastFault !== state.faultCode) {
        t.lastFault = state.faultCode;
        push('fault', `VESC fault code ${state.faultCode}`);
      }
    } else {
      t.lastFault = null;
    }
  }

  // maintenance interval
  if (state.odometerKm !== undefined) {
    if (t.lastMaintKm === null || state.odometerKm < t.lastMaintKm) {
      t.lastMaintKm = state.odometerKm;
    } else if (settings.maintenanceKm > 0 && state.odometerKm - t.lastMaintKm >= settings.maintenanceKm) {
      t.lastMaintKm = state.odometerKm;
      push('maintenance', `Maintenance due: ${settings.maintenanceKm} km since last service`);
    }
  }

  // movement while parked / VESC disconnected
  if (state.lat !== undefined && state.lon !== undefined) {
    if (!state.vescConnected || settings.parked) {
      if (!t.anchor) {
        t.anchor = { lat: state.lat, lon: state.lon };
      } else {
        const d = haversineM(t.anchor.lat, t.anchor.lon, state.lat, state.lon);
        if (d > MOVEMENT_THRESHOLD_M) {
          if (settings.moveAlert) push('movement', `Vehicle moved ${Math.round(d)} m while parked`);
          t.anchor = { lat: state.lat, lon: state.lon };
        }
      }
    } else {
      t.anchor = null;
    }
  }
  return { alerts, tracking: t };
}
