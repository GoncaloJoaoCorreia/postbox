import type { Carrier } from "../../api/client";

export const CARRIERS: Carrier[] = ["dhl", "ctt", "postnl", "dpd", "paack", "ecoscooting"];

const CARRIER_NAMES: Record<Carrier, string> = {
  dhl: "DHL",
  ctt: "CTT — Correios de Portugal",
  postnl: "PostNL",
  dpd: "DPD",
  paack: "Paack",
  ecoscooting: "EcoScooting",
};

export function carrierLabel(carrier: Carrier | null | undefined): string {
  return carrier ? carrier.toUpperCase() : "Unknown";
}

export function carrierName(carrier: Carrier | null | undefined): string {
  return carrier ? CARRIER_NAMES[carrier] : "Unknown carrier";
}
