import { describe, expect, it } from 'vitest';
import { emptyTracking, evaluateState, geofenceTransitions, haversineM, type AlertSettings, type StateLike } from '../src/logic/alerts';

const settings: AlertSettings = { lowBatteryPct: 20, maintenanceKm: 0, moveAlert: true, parked: false };
const st = (o: Partial<StateLike> = {}): StateLike => ({ ts: 1, vescConnected: true, source: 'measured', ...o });

describe('geofenceTransitions', () => {
  const f = (inside: boolean, alertOn: 'exit' | 'enter' | 'both' = 'exit') => [{ id: 'g1', name: 'Home', alertOn, inside }];
  it('alerts on exit when previously inside', () => {
    const r = geofenceTransitions(f(false), { g1: true }, 5);
    expect(r.alerts.map((a) => a.kind)).toEqual(['geofence_exit']);
    expect(r.next.g1).toBe(false);
  });
  it('alerts on enter only when configured', () => {
    expect(geofenceTransitions(f(true, 'exit'), { g1: false }, 5).alerts).toHaveLength(0);
    expect(geofenceTransitions(f(true, 'enter'), { g1: false }, 5).alerts[0].kind).toBe('geofence_enter');
    expect(geofenceTransitions(f(true, 'both'), { g1: false }, 5).alerts[0].kind).toBe('geofence_enter');
    expect(geofenceTransitions(f(false, 'both'), { g1: true }, 5).alerts[0].kind).toBe('geofence_exit');
  });
  it('does not alert for unknown previous state or no change', () => {
    expect(geofenceTransitions(f(false, 'both'), {}, 5).alerts).toHaveLength(0);
    expect(geofenceTransitions(f(false, 'both'), { g1: null }, 5).alerts).toHaveLength(0);
    expect(geofenceTransitions(f(true, 'both'), { g1: true }, 5).alerts).toHaveLength(0);
  });
});

describe('evaluateState', () => {
  it('low battery fires once, re-arms after recovery', () => {
    let t = emptyTracking();
    let r = evaluateState(st({ socPct: 19 }), settings, t, []);
    expect(r.alerts.map((a) => a.kind)).toEqual(['low_battery']);
    t = r.tracking;
    expect(evaluateState(st({ socPct: 18 }), settings, t, []).alerts).toHaveLength(0);
    t = evaluateState(st({ socPct: 80 }), settings, t, []).tracking;
    expect(evaluateState(st({ socPct: 10 }), settings, t, []).alerts).toHaveLength(1);
  });
  it('fault alerts on new non-zero code and re-arms when cleared', () => {
    let r = evaluateState(st({ faultCode: 3 }), settings, emptyTracking(), []);
    expect(r.alerts[0].kind).toBe('fault');
    expect(evaluateState(st({ faultCode: 3 }), settings, r.tracking, []).alerts).toHaveLength(0);
    expect(evaluateState(st({ faultCode: 5 }), settings, r.tracking, []).alerts).toHaveLength(1);
    const cleared = evaluateState(st({ faultCode: 0 }), settings, r.tracking, []);
    expect(evaluateState(st({ faultCode: 3 }), settings, cleared.tracking, []).alerts).toHaveLength(1);
  });
  it('movement: >50 m while vescConnected=false alerts, <50 m does not', () => {
    const s = { ...settings };
    let r = evaluateState(st({ vescConnected: false, lat: 48, lon: 11 }), s, emptyTracking(), []);
    expect(r.alerts).toHaveLength(0);
    expect(r.tracking.anchor).toEqual({ lat: 48, lon: 11 });
    // ~22 m north
    r = evaluateState(st({ vescConnected: false, lat: 48.0002, lon: 11 }), s, r.tracking, []);
    expect(r.alerts).toHaveLength(0);
    // ~111 m north of anchor
    r = evaluateState(st({ vescConnected: false, lat: 48.001, lon: 11 }), s, r.tracking, []);
    expect(r.alerts.map((a) => a.kind)).toEqual(['movement']);
    expect(r.tracking.anchor).toEqual({ lat: 48.001, lon: 11 });
  });
  it('movement respects moveAlert=false and clears anchor while riding', () => {
    let r = evaluateState(st({ vescConnected: false, lat: 48, lon: 11 }), { ...settings, moveAlert: false }, emptyTracking(), []);
    r = evaluateState(st({ vescConnected: false, lat: 48.01, lon: 11 }), { ...settings, moveAlert: false }, r.tracking, []);
    expect(r.alerts).toHaveLength(0);
    r = evaluateState(st({ vescConnected: true, lat: 48.02, lon: 11 }), settings, r.tracking, []);
    expect(r.tracking.anchor).toBeNull();
  });
  it('parked flag watches movement even with VESC connected', () => {
    const p = { ...settings, parked: true };
    let r = evaluateState(st({ lat: 48, lon: 11 }), p, emptyTracking(), []);
    r = evaluateState(st({ lat: 48.002, lon: 11 }), p, r.tracking, []);
    expect(r.alerts.map((a) => a.kind)).toEqual(['movement']);
  });
  it('maintenance fires after maintenanceKm', () => {
    const s = { ...settings, maintenanceKm: 100 };
    let r = evaluateState(st({ odometerKm: 1000 }), s, emptyTracking(), []);
    expect(r.alerts).toHaveLength(0);
    r = evaluateState(st({ odometerKm: 1099 }), s, r.tracking, []);
    expect(r.alerts).toHaveLength(0);
    r = evaluateState(st({ odometerKm: 1100 }), s, r.tracking, []);
    expect(r.alerts.map((a) => a.kind)).toEqual(['maintenance']);
  });
  it('geofence exit through evaluateState and SIMULATION prefix', () => {
    const t = { ...emptyTracking(), fenceInside: { g1: true } };
    const r = evaluateState(st({ lat: 1, lon: 1, source: 'simulated' }), settings, t, [{ id: 'g1', name: 'Home', alertOn: 'exit', inside: false }]);
    expect(r.alerts[0].kind).toBe('geofence_exit');
    expect(r.alerts[0].message.startsWith('[SIMULATION]')).toBe(true);
    expect(r.tracking.fenceInside.g1).toBe(false);
  });
  it('haversine sanity', () => {
    expect(haversineM(0, 0, 0, 0)).toBe(0);
    expect(haversineM(48, 11, 48.001, 11)).toBeGreaterThan(110);
    expect(haversineM(48, 11, 48.001, 11)).toBeLessThan(112);
  });
});

describe('anti-theft lock alerts', () => {
  const settings = { lowBatteryPct: 20, maintenanceKm: 0, moveAlert: true, parked: false };
  const base = { lowBatteryActive: false, lastFault: null, anchor: null, lastMaintKm: null, fenceInside: {} };
  it('raises one theft alert per tamper event and re-arms after unlock', () => {
    let r = evaluateState({ ts: 1, vescConnected: true, locked: true, tamper: false, source: 'measured' }, settings, base, []);
    expect(r.alerts).toHaveLength(0);
    r = evaluateState({ ts: 2, vescConnected: true, locked: true, tamper: true, source: 'measured' }, settings, r.tracking, []);
    expect(r.alerts.map((a) => a.kind)).toEqual(['theft']);
    r = evaluateState({ ts: 3, vescConnected: true, locked: true, tamper: true, source: 'measured' }, settings, r.tracking, []);
    expect(r.alerts).toHaveLength(0);
    r = evaluateState({ ts: 4, vescConnected: true, locked: false, source: 'measured' }, settings, r.tracking, []);
    r = evaluateState({ ts: 5, vescConnected: true, locked: true, tamper: true, source: 'simulated' }, settings, r.tracking, []);
    expect(r.alerts[0].message).toBe('[SIMULATION] THEFT ALERT: vehicle is being moved while locked');
  });
  it('watches GPS movement while locked even with the VESC connected', () => {
    let r = evaluateState({ ts: 1, lat: 47, lon: 8, vescConnected: true, locked: true, source: 'measured' }, settings, base, []);
    r = evaluateState({ ts: 2, lat: 47.001, lon: 8, vescConnected: true, locked: true, source: 'measured' }, settings, r.tracking, []);
    expect(r.alerts.map((a) => a.kind)).toContain('movement');
  });
});
