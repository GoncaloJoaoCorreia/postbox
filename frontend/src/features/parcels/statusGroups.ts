import type { Parcel, TrackingStatus } from "../../api/client";

export interface ParcelGroup {
  label: string;
  parcels: Parcel[];
}

const IN_TRANSIT_STATUSES: ReadonlySet<TrackingStatus> = new Set([
  "PENDING",
  "IN_TRANSIT",
  "OUT_FOR_DELIVERY",
  "UNKNOWN",
]);

export function groupParcels(parcels: Parcel[]): ParcelGroup[] {
  const active = parcels.filter((p) => !p.archivedAt);
  const archived = parcels.filter((p) => p.archivedAt);
  const groups: ParcelGroup[] = [
    { label: "In transit", parcels: active.filter((p) => IN_TRANSIT_STATUSES.has(p.status)) },
    { label: "Delivered", parcels: active.filter((p) => p.status === "DELIVERED") },
    { label: "Exception", parcels: active.filter((p) => p.status === "EXCEPTION") },
  ].filter((group) => group.parcels.length > 0);
  if (archived.length > 0) groups.push({ label: "Archived", parcels: archived });
  return groups;
}
