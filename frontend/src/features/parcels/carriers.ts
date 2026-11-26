import type { Carrier } from "../../api/client";

export const CARRIERS: Carrier[] = ["dhl", "ctt", "postnl", "dpd", "paack", "ecoscooting"];

export function carrierLabel(carrier: Carrier | null | undefined): string {
  return carrier ? carrier.toUpperCase() : "Unknown";
}
