# EcoScooting — consumer tracking endpoint research (G-73)

Research date: 2026-11-23. Scope: the lookup behind the consumer tracking page for
EcoScooting (e-bike last-mile carrier, ES/PT), so the adapter in G-62 can be implemented.
Research only — no adapter code here. Fixtures live in `fixtures/` (personal data redacted:
recipient postal code, POD photo URLs, delivery GPS coordinates).

## TL;DR

The original consumer page `play.ecoscooting.com/tracking` is **dead** (DNS gone, see below).
Today the public tracking on `www.ecoscooting.com/tracking/:id` does **not** call an
EcoScooting API at all — it calls **Cainiao's public "link" gateway**:

```
POST https://de-link.cainiao.com/gateway/link.do
Content-Type: application/x-www-form-urlencoded

logistics_interface={"mailNo":"<trackingNumber>","locale":"es_ES|en_US|pt_PT","role":"endUser"}
&msg_type=CN_OVERSEA_LOGISTICS_INQUIRY_TRACKING
&logistic_provider_id=DISTRIBUTOR_30250031
&data_digest=suibianxie
&to_code=CNL_EU
```

No session, no bootstrap, no captcha, no auth headers. Plain HTTP from a server works.
`data_digest` is **not verified** for this message type (a wrong digest still returns 200),
but the other four parameters are required (see "Error shapes"). Reproduce with
`ecoscooting_probe.sh` in this directory.

## Working request recipe (verified 2026-11-23, HTTP 200)

```bash
TN=CNPRT42500041234024382386   # example number from G-73
curl -sS -X POST "https://de-link.cainiao.com/gateway/link.do" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "logistics_interface={\"mailNo\":\"$TN\",\"locale\":\"es_ES\",\"role\":\"endUser\"}" \
  --data-urlencode "msg_type=CN_OVERSEA_LOGISTICS_INQUIRY_TRACKING" \
  --data-urlencode "logistic_provider_id=DISTRIBUTOR_30250031" \
  --data-urlencode "data_digest=suibianxie" \
  --data-urlencode "to_code=CNL_EU"
```

`locale` is one of `es_ES`, `en_US`, `pt_PT` (mapped from the site's `es`/`en`/`pt`); it only
localizes `statusName`/`description` in the response. Lookups are **case-sensitive**
(lowercase input → `B00604`).

### Parameter requirements (probed)

| params present (all with valid `logistics_interface`) | result |
|---|---|
| all 5 (as above) | 200, timeline |
| missing `data_digest` | 200 XML `S12` "request param DataDigest can not be null" |
| missing `logistic_provider_id` | 200 XML `S12` "request param fromCode can not be null" |
| missing `to_code` | 200 JSON `S16` "Service route failed" |
| wrong `to_code` (e.g. `XX`) | 200 JSON `S23` (route/entitlement error) |
| GET instead of POST, or missing `logistics_interface` | 200 XML `S12` |
| wrong `data_digest` value | still 200 OK (digest not checked for this msg_type) |

## Response shape (HTTP 200, `application/json`)

```json
{
  "success": "true",
  "packageParam": {
    "trackingNumber": "CNPRT42500041234024382386",
    "outOrderId": "AP00100198198308",
    "toZipcode": "[redacted]",
    "dimWeight": {"weight": "285", "weightUnit": "g"}
  },
  "cainiaoId": "2220461610059",
  "feature": "{}",
  "solutionParam": {"bizSource": "other", "logisticsMode": "other", "solutionCode": "PES012"},
  "statuses": [
    {
      "datetime": "2026-11-23 08:40:51 UTC+0",
      "opTimestamp": "1795423251000",
      "timezone": "0",
      "statusName": "Out for Delivery",
      "description": "Your shipment is out for delivery",
      "actionCode": "LM_DELIVERY_DEPART",
      "opCode": "0",
      "location": "PT"
    }
  ]
}
```

- `statuses` is newest-first. Each event: `datetime` (string with UTC offset), `opTimestamp`
  (epoch ms), `timezone` (offset hours as string), localized `statusName`/`description`,
  machine-readable `actionCode`, `opCode`, `location` (city or country code).
- Delivered events additionally carry `deliveryLatitude`/`deliveryLongitude` and `imgUrl`
  (signed POD photo URL — redact before storing; expires).
- Failed attempts carry the reason in `description` in brackets, e.g.
  `"Your shipment delivery attempt failed [Parcel cannot be delivered outside business hours]"`,
  plus a numeric `opCode` (e.g. `5576`).

### Observed `actionCode` values

| actionCode | meaning | fixture |
|---|---|---|
| `CREATE_ORDER_CES` | order created / pre-advised by merchant (oldest possible event) | delivered fixtures |
| `SC_INBOUND` | accepted at sorting center ("Llegada al centro de clasificación") | out-for-delivery example |
| `TRANSFER_DEPART` / `TRANSFER_ARRIVE` | international transit leg departed/arrived | out-for-delivery example |
| `SC_OUTBOUND` | left sorting center | out-for-delivery example |
| `SL_ACCEPT` | accepted by last-mile carrier | out-for-delivery example |
| `LM_DELIVERY_DEPART` | out for delivery (scooter) | out-for-delivery example |
| `LM_DELIVERY_FAILURE` | failed delivery attempt, reason in `description` + `opCode` | delivered-with-failed-attempt |
| `LM_SIGN_SUCCESS` | delivered (recipient signature) | delivered fixtures |
| `PUDO_INBOUND` / `PUDO_DELIVERY` / `PUDO_SIGN_SUCCESS` | parcel locker (PUDO) flow variants | pudo fixture |

## Error / edge shapes (all HTTP 200 — status is in the body)

| situation | body | adapter mapping (G-62) |
|---|---|---|
| unknown / expired / out-of-scope number | `{"success":"false","errorCode":"B00604","errorMsg":"Logistics tracking number out of scope"}` | `NotFound` |
| number pre-advised but no scans yet | `success:"true"` with `statuses` containing only `CREATE_ORDER_CES` (or empty) | "not found yet" — keep distinct from `NotFound` |
| transient backend error | `{"success":"false","errorCode":"B00502","errorMsg":"System error"}` | `CarrierUnavailable` |
| malformed request (missing params, GET) | XML: `<response><success>false</success><errorCode>S12</errorCode>...` | `CarrierUnavailable` / bad request |
| route errors (bad/missing `to_code`) | JSON `S16` / `S23` | `CarrierUnavailable` |
| HTTP-level failure (TLS/DNS/5xx) | curl error / non-200 | `CarrierUnavailable` without crashing |

Rate limiting: 10 rapid sequential requests all returned 200 — none observed at this volume
(single IP, short window). Re-test under sustained load before assuming none exists.

## ES/PT last-mile vs international leg

The consumer lookup returns **both legs** in one timeline: the example parcel shows the
international leg (`TRANSFER_DEPART` madrid → `SC_INBOUND`/`SC_OUTBOUND` San Fernando de
Henares → `TRANSFER_ARRIVE` Porto) **and** the EcoScooting last-mile leg in PT
(`SL_ACCEPT` → `LM_DELIVERY_DEPART`). So upstream (Cainiao/CN→EU) events **are** visible
through this endpoint — no separate upstream query needed. The marketing site additionally
offers a raw Cainiao embed at `https://track.cainiao.com/orderTrack?mailNoList=<n>`
(site route `/track/eu?mailNoList=<n>`), which we did not need for the API.

## Legacy EcoScooting own-API (play.ecoscooting.com) — DEAD as of 2026-11-23

The original consumer page `https://play.ecoscooting.com/tracking` (Angular SPA) no longer
resolves: public DNS (Google/Quad9/Cloudflare DoH) returns only
`CNAME d5qvbq9ed2nvi.cloudfront.net`, which has **no A record** — the CloudFront distribution
was deleted. A headless-Chrome probe via REA confirmed `net::ERR_NAME_NOT_RESOLVED`.

From the last archived build (Wayback 2025-04-08, `main-es2015.74c98d92a25ac0758b46.js` +
tracking chunk 61), the app called its own API:

- Base URL: `https://api.ecoscooting.com:3002` (`api.ecoscooting.com` → AWS ELB
  `v2-ecoscooting-api-1978737728.eu-west-1.elb.amazonaws.com`).
- Main lookup: `GET /tracking/{id}/?language={es|en|pt}` — **public** (interceptor list
  `["words/","auth/login/","geolocation/","tracking/","delivery-confirmation/"]` skips
  `Authorization` for these) but always sends header **`X-Source: 1`**.
- Other public endpoints found in the bundle: `GET /tracking/validate/{id}/{token}`,
  `GET /tracking/{id}/{token}/canUpdate`, `GET/PATCH /tracking/{id}/{token}/deliveryInfo/`,
  `GET /tracking/service/options/?serviceId=`, `GET /tracking/{id}/service/availableDeliveryDates/`,
  `GET /tracking/{id}/appearance/`, `GET /tracking/document/validation/?documentTypeId=&personID=`,
  `PATCH /tracking/v2/{id}/{action}`.
- SPA routes: `/tracking/:id` and `/tracking/:id/:token`; the page polls every 100 s.
- Status 2026-11-23: port 3002 answers TLS but nginx returns **502 Bad Gateway** on every
  path (upstream down); port 443/3000 answer a bare Express echo `{"ip":"...","meto":"GET"}`.
  So even the legacy API is currently unreachable → an adapter hitting it degrades to
  `CarrierUnavailable` today.

## Tracking number formats (observed in the wild, via Wayback URL captures + fixtures)

- `CN` + 3 uppercase letters + 18–20 digits, total 23–25 chars. Prefixes seen: `CNESP`
  (Spain), `CNPRT` (Portugal), `CNG`. Examples: `CNPRT42500041234024382386`,
  `CNESP46800123400064760037`, `CNG00829792593428`.
- Pure numeric: 12, 13, 16 and 18 digits seen (`460357243181`, `1234000674573`,
  `3066075695109783`, `035500000109544699`).
- The marketing site appends `001` to numbers starting with `ICP` before querying Cainiao
  (and strips a trailing `001` for display).
- Suggested detection rule (check letter-prefixed first to avoid collisions with numeric
  carriers): `^(CN[A-Z]{3}\d{18,20}|\d{12,18})$`, case-sensitive.

## Evidence / provenance

- REA `analyze_javascript_application` on the archived bundles (chunk61.js, mkt.js):
  evidence `ev_38bd9d2675da3380c394ec748de250944bfb8d85d109941618543542fcaa784c` — recovered
  SPA routes `/`, `/:id`, `/:id/:token` for the tracking module.
- REA `capture_browser_scenario` (headless Chrome 157) against `https://play.ecoscooting.com/tracking`:
  request failed with `net::ERR_NAME_NOT_RESOLVED` — confirms the page host is dead.
- Source bundles pulled from the Wayback Machine (2025-04-08 snapshot of
  `play.ecoscooting.com`) and the live `www.ecoscooting.com/static/js/main.c7031648.js`.
- All `link.do` responses in `fixtures/` were captured live on 2026-11-23 with the recipe
  above; personal data redacted.
