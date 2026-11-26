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

export function carrierTrackingUrl(
  carrier: Carrier | null | undefined,
  trackingNumber: string,
  metadata?: Record<string, string> | null,
): string | null {
  if (!carrier) return null;
  const n = encodeURIComponent(trackingNumber);
  switch (carrier) {
    case "dhl":
      return `https://www.dhl.com/global-en/home/tracking.html?tracking-id=${n}`;
    case "ctt":
      return `https://www.ctt.pt/feapl_2/app/open/objectSearch/objectSearch.jspx?objects=${n}&request_locale=en`;
    case "postnl":
      return `https://postnl.post/track?barcodes=${n}`;
    case "dpd":
      return `https://www.dpdgroup.com/pt/mydpd/my-parcels/search?lang=en&parcelNumber=${n}`;
    case "paack": {
      const postcode = metadata?.postcode;
      return postcode
        ? `https://paack.co/shipments/tracking?tracking_number=${n}&postal_code=${encodeURIComponent(postcode)}`
        : `https://paack.co/shipments/tracking?tracking_number=${n}`;
    }
    case "ecoscooting":
      return `https://www.ecoscooting.com/tracking/${n}`;
  }
}
