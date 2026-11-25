import { RateLimiter, TokenBucket } from './logic/wsMessages';

export interface Sock {
  send(data: string): void;
  close(code?: number, reason?: string): void;
}
export interface Conn {
  sock: Sock;
  userId: string;
  displayName: string;
  groups: Set<string>;
  limiter: RateLimiter;
  bucket: TokenBucket;
  lastTraffic: number;
}

/** In-memory fan-out registry for WebSocket connections. */
export class Hub {
  private conns = new Set<Conn>();
  private byUser = new Map<string, Set<Conn>>();
  private rooms = new Map<string, Set<Conn>>();
  private vehicleSubs = new Map<string, Set<Conn>>();

  add(c: Conn) {
    this.conns.add(c);
    if (!this.byUser.has(c.userId)) this.byUser.set(c.userId, new Set());
    this.byUser.get(c.userId)!.add(c);
  }
  remove(c: Conn): string[] {
    this.conns.delete(c);
    this.byUser.get(c.userId)?.delete(c);
    if (this.byUser.get(c.userId)?.size === 0) this.byUser.delete(c.userId);
    for (const s of this.vehicleSubs.values()) s.delete(c);
    const left = [...c.groups];
    for (const g of left) this.leave(c, g);
    return left;
  }
  isUserOnline(userId: string) {
    return (this.byUser.get(userId)?.size ?? 0) > 0;
  }
  /** returns true if this is the user's first connection in the room */
  join(c: Conn, groupId: string): boolean {
    const first = !this.userInRoom(groupId, c.userId);
    c.groups.add(groupId);
    if (!this.rooms.has(groupId)) this.rooms.set(groupId, new Set());
    this.rooms.get(groupId)!.add(c);
    return first;
  }
  /** returns true if the user has no connection left in the room */
  leave(c: Conn, groupId: string): boolean {
    c.groups.delete(groupId);
    this.rooms.get(groupId)?.delete(c);
    if (this.rooms.get(groupId)?.size === 0) this.rooms.delete(groupId);
    return !this.userInRoom(groupId, c.userId);
  }
  userInRoom(groupId: string, userId: string) {
    for (const c of this.rooms.get(groupId) ?? []) if (c.userId === userId) return true;
    return false;
  }
  /** drop all of a user's sockets from a room (leave/removed); returns true if any were in it */
  removeUserFromRoom(groupId: string, userId: string): boolean {
    let any = false;
    for (const c of [...(this.rooms.get(groupId) ?? [])]) if (c.userId === userId) { this.leave(c, groupId); any = true; }
    return any;
  }
  closeRoom(groupId: string) {
    for (const c of [...(this.rooms.get(groupId) ?? [])]) c.groups.delete(groupId);
    this.rooms.delete(groupId);
  }
  subscribe(c: Conn, vehicleId: string) {
    if (!this.vehicleSubs.has(vehicleId)) this.vehicleSubs.set(vehicleId, new Set());
    this.vehicleSubs.get(vehicleId)!.add(c);
  }
  toRoom(groupId: string, payload: unknown, exceptUserId?: string) {
    const data = JSON.stringify(payload);
    for (const c of this.rooms.get(groupId) ?? []) if (c.userId !== exceptUserId) safeSend(c.sock, data);
  }
  toVehicleSubscribers(vehicleId: string, payload: unknown) {
    const data = JSON.stringify(payload);
    for (const c of this.vehicleSubs.get(vehicleId) ?? []) safeSend(c.sock, data);
  }
  all(): Conn[] {
    return [...this.conns];
  }
}

export function safeSend(s: Sock, data: string) {
  try {
    s.send(data);
  } catch {
    /* socket gone; cleanup happens on close */
  }
}
