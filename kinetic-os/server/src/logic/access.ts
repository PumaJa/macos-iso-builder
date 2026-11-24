export interface GroupAccessInfo {
  visibility: 'public' | 'private';
  isMember: boolean;
}
/** Only members can read a private group; public groups are readable by any authenticated user. */
export const canReadGroup = (g: GroupAccessInfo) => g.visibility === 'public' || g.isMember;

/** Telematics (state, history, geofences, alerts, settings, live subscription) is owner-only. */
export const canAccessTelematics = (vehicleOwnerId: string, userId: string) => vehicleOwnerId === userId;

/** Photos: the owner, or someone sharing at least one group with the owner. */
export const canReadVehiclePhoto = (vehicleOwnerId: string, userId: string, sharesGroupWithOwner: boolean) =>
  vehicleOwnerId === userId || sharesGroupWithOwner;

export const canDeleteGroup = (ownerId: string, userId: string) => ownerId === userId;
