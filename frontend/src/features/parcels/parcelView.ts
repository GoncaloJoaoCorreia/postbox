import type { Parcel, TrackingStatus } from "../../api/client";

export type ParcelView = "all" | "in-transit" | "delivered" | "attention" | "archived";

export const STATUS_LABELS: Record<TrackingStatus, string> = {
  PENDING: "Label created",
  IN_TRANSIT: "In transit",
  OUT_FOR_DELIVERY: "Out for delivery",
  DELIVERED: "Delivered",
  EXCEPTION: "Needs attention",
  UNKNOWN: "Not checked yet",
};

const IN_TRANSIT_STATUSES: ReadonlySet<TrackingStatus> = new Set([
  "PENDING",
  "IN_TRANSIT",
  "OUT_FOR_DELIVERY",
  "UNKNOWN",
]);

export function isInTransit(status: TrackingStatus): boolean {
  return IN_TRANSIT_STATUSES.has(status);
}

export function statusChipClass(status: TrackingStatus): string {
  if (status === "DELIVERED") return "chip chip-green";
  if (status === "EXCEPTION") return "chip chip-red";
  if (status === "OUT_FOR_DELIVERY") return "chip chip-orange";
  return "chip chip-muted";
}

export interface ParcelCounts {
  inTransit: number;
  delivered: number;
  attention: number;
}

export function parcelCounts(parcels: Parcel[]): ParcelCounts {
  const active = parcels.filter((p) => !p.archivedAt);
  return {
    inTransit: active.filter((p) => isInTransit(p.status)).length,
    delivered: active.filter((p) => p.status === "DELIVERED").length,
    attention: active.filter((p) => p.status === "EXCEPTION").length,
  };
}

export function filterParcels(parcels: Parcel[], view: ParcelView, search: string): Parcel[] {
  const query = search.trim().toLowerCase();
  return parcels.filter((p) => {
    if (view === "archived" ? !p.archivedAt : p.archivedAt) return false;
    if (view === "in-transit" && !isInTransit(p.status)) return false;
    if (view === "delivered" && p.status !== "DELIVERED") return false;
    if (view === "attention" && p.status !== "EXCEPTION") return false;
    if (
      query &&
      !p.displayName.toLowerCase().includes(query) &&
      !p.trackingNumber.toLowerCase().includes(query)
    ) {
      return false;
    }
    return true;
  });
}
