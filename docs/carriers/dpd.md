# DPD — per-country tracking endpoints, PT first, NL second (G-71 research)

Research date: 2026-11-26. Scope: the parcel-status lookups behind [dpd.pt](https://tracking.dpd.pt) (DPD
Portugal) and [dpd.nl](https://www.dpd.com/nl/en/receiving/track/) (DPD Netherlands), so the adapter in
`G-60` can be implemented
directly. Research only — no adapter code here. Fixtures live in `docs/carriers/dpd/fixtures/`; all fixture
identifiers, names, addresses, coordinates and clocks are synthetic (invented numbers such as `09550000000101`
were never issued). Reproduce with `dpd_probe.sh` in this directory. Sources at the bottom.

DPD is one group (DPDgroup/Geopost) with a national subsidiary per country, and there are **two distinct
consumer surfaces**:

1. **DPD Portugal** runs its own tracker on `tracking.dpd.pt` (a server-rendered Drupal site) — HTML only.
2. **DPD Netherlands** (and CH, BE, HU, …) use the group's **myDPD** portal on `www.dpdgroup.com`, which has
   both public deep-link pages (Cloudflare-fronted HTML) and an **anonymous JSON "guest" API** under
   `/concept/webservice` that the myDPD mobile app uses.

## TL;DR for the adapter

```
# PT — server-rendered HTML, no auth, no session, no tokens
GET https://tracking.dpd.pt/pt?reference=<number>
# (the page form POSTs to /?reference=<number>; / and /track-and-trace?reference= redirect to /pt)

# NL (and every other DPDgroup BU) — anonymous guest JSON API on the group host
POST https://www.dpdgroup.com/concept/webservice/v10/parcels/details/<number>
     ?parcelType=INCOMING&businessUnit=DPD-NL&lang=en&continueWithoutVerification=true
# with Authorization: Bearer <guest token> (token chain below); optional
#   &dataForVerification=<recipient postcode>&continueWithoutVerification=false
#   unlocks verified scans (parcelEvents), sender/receiver, weight, delivery window

# Public deep links (for sharing / detection hints), per BU:
https://www.dpdgroup.com/{bu}/mydpd/my-parcels/search?lang={lang}&parcelNumber=<number>   # e.g. bu=nl
https://www.dpdgroup.com/{bu}/mydpd/my-parcels/track?lang={lang}&parcelNumber=<number>
```

- PT not found → page with `.current-status-notfound` and **"No results were found for &lt;number&gt;"**
  (verified live 2026-11-26 with an invented number). Known number with no scans yet → empty history table,
  no notice. A 404 or a redirect says nothing about the parcel (indeterminate); a page without the tracker's
  heading can be a bot check.
- NL not found → HTTP **404**, or **400 with `exceptionType: "PARCEL_NOT_FOUND"`** (definitive only without a
  postcode; a real parcel looked up without one returns its history). Wrong postcode → 400
  `exceptionType: "PROVIDE_ZIP_CODE"` — retry once without verification. 503 = service down (never about the
  parcel); 429 = rate limit; 401 = refresh the guest token once. The HTML pages sit behind Cloudflare
  (challenge page / 429 for non-browser clients) — use the JSON API from a server.
- The guest API is **group-wide**: any business unit answers any DPD parcel, and the response names
  `status.countryCode` (DPD's current country for the parcel) and `shipmentBUCode`. A NL adapter must only
  claim a number when `status.countryCode == "NL"`, otherwise it double-claims cross-border parcels.
- No rate limiting was observed on `tracking.dpd.pt` itself; the myDPD guest API answers 429 + `retry-after`,
  and replays 502/503/504 once after 1–3 s. Poll at most every 5–15 min while in transit, like the other
  adapters.

## DPD Portugal — `tracking.dpd.pt` (HTML)

Verified live 2026-11-26: `GET https://tracking.dpd.pt/pt?reference=09990000000404` returns the server-rendered
page directly (no cookie, token or browser needed; a normal browser User-Agent is enough). The page's search
form is `POST /?reference=<number>` with fields `reference`, `form_build_id`,
`form_id=dpd_integrations_dpd_parceltracking_form`, submit `op=Procurar`; `/`, `/track-and-trace?reference=`
and the marketing page `/en/getting-parcel/track-trace` all build/redirect to the same lookup. An older
`/status/pt-PT/<number>` path appears in legacy scrapers. The form accepts the 14-digit parcel number **or** a
reference number (maxlength 128).

### Page structure (parse targets)

- `.reference-label span` — `Nº de encomenda: <number>`; the heading must name the asked number.
- `.current-status` — `.current-description` (current status wording, PT) + `.current-date`
  (`DD-MM-YYYY HH:mm`, wall clock, **no zone**: PT also delivers in the Azores/Madeira and hub scans can be
  abroad, so keep them as wall clocks, e.g. Europe/Lisbon).
- `.current-status-notfound` — `<strong>No results were found for <number></strong>` (EN; PT wording exists
  too) → `NotFound`.
- Progress rail `.flow` with `.flow-event` (`.flow-description`, `.flow-date`, `.flow-map` map link).
- History table `.table-responsive table.table`, **newest scan first**, columns
  `Código | Data | Hora | Descrição | Comentário | Localização`.
  - `Localização` is a Google-Maps-embed link with coordinates; for a delivery or failed attempt the
    coordinates point at the recipient's address — **do not keep them**.
  - `Comentário` mixes receiver name (on POD), depot numbers, country codes, pickup-point codes, references,
    and the delivery window (on code 706). Keep only what the adapter needs; the POD scan's comment names the
    signatory.

### PT scan codes (`Código` column) → postbox `TrackingStatus`

Codes observed on the page (via third-party live captures; wording column is Portuguese):

| Code | Meaning (PT wording) | postbox status |
|---|---|---|
| 007 | sender dropped at pickup point | IN_TRANSIT |
| 012 | pickup point handed parcel to DPD | IN_TRANSIT |
| COL | courier collected (accepts the parcel unless an earlier scan already moved it) | IN_TRANSIT |
| INB / HUB / PEC | depot/hub scans in | IN_TRANSIT |
| 402 / 403 / 410 | back at depot (again / after failed attempt / held) | IN_TRANSIT |
| 723 / 509 | address corrected after failed attempt / delivery moved to another day | IN_TRANSIT |
| OFD | out for delivery (`EM DISTRIBUIÇÃO`) | OUT_FOR_DELIVERY |
| 001 / 002 | left at pickup point / locker (point took it in; comment names the point) | IN_TRANSIT (pickup point) |
| 500 / 501 / 506 / 508 / 510 / 521 | failed attempt: bad/unknown address, incomplete address, recipient absent, closed for holidays, DPD's own reason, locker/point full | EXCEPTION (retry usually follows) |
| 008 / 424 | not collected in time / return-to-sender **ordered** — delivery stops | EXCEPTION (return) |
| 611 | back with the sender (unless later scans still move it → return under way) | EXCEPTION (returned) |
| POD | proof of delivery | DELIVERED |
| 700 / 706 / 709 / 714 / 741 / 743 | notices to the recipient / delivery window (706, window in comment) / next delivery / expected day set (714, row names no day) / new delivery requested / address change requested — no movement | keep stage of previous scan |

Context rules that matter: `COL` reads as acceptance only if nothing moved the parcel before; `POD` after a
return was ordered is the delivery back to the sender (returned, not delivered); a `POD` that later scans
take back (back at depot, out for delivery, missed attempt) keeps the stage of the scan before it.

## DPD Netherlands / myDPD — guest JSON API (`www.dpdgroup.com/concept/webservice`)

The myDPD Android app (`com.dpdgroup.chatbot.lemny.prod`) talks to an anonymous guest API. Every value below
is shipped publicly in the application bundle; none of it is an account secret. Token chain (cache each step):

```
1) POST https://firebaseinstallations.googleapis.com/v1/projects/consignee-portal/installations
     headers: X-Goog-Api-Key: <public app-restricted Firebase key from the app bundle>,
              X-Android-Package: com.dpdgroup.chatbot.lemny.prod, X-Android-Cert: <app SHA-1>
     body:    {"fid": <random 22-char>, "appId": "1:959401347543:android:8d1a84133332291109e392",
               "authVersion": "FIS_v2", "sdkVersion": "a:18.0.0"}
   → {"fid": ..., "authToken": {"token": ..., "expiresIn": "604800s"}}

2) POST https://firebaseremoteconfig.googleapis.com/v1/projects/959401347543/namespaces/firebase:fetch
     headers: same + X-Goog-Firebase-Installations-Auth: <authToken.token>
     body:    {"appId": <appId>, "appInstanceId": <fid>, "appInstanceIdToken": <authToken.token>,
               "languageCode": "en-US", "countryCode": "NL", "platformVersion": "36",
               "appVersion": "3.79.14", "packageName": "com.dpdgroup.chatbot.lemny.prod",
               "sdkVersion": "22.1.2", "analyticsUserProperties": {}}
   → {"entries": {"basic_dpd_token": "<base64 client credential>"}}

3) POST https://www.dpdgroup.com/concept/webservice/oauth/token?grant_type=client_credentials
     headers: Authorization: Basic <basic_dpd_token>, User-Agent: myDPD/3.79.14 (Android)
   → {"access_token": ..., "expires_in": 3600}        (cache ~expires_in - 60s; on 401 refresh once)

4) POST https://www.dpdgroup.com/concept/webservice/v10/parcels/details/<number>
        ?parcelType=INCOMING&businessUnit=DPD-NL&lang=en&continueWithoutVerification=true
     headers: Authorization: Bearer <access_token>, Content-Type: application/json,
              Accept: application/json, User-Agent: myDPD/3.79.14 (Android)
     body:    empty
   With the recipient postcode: add &dataForVerification=<zip>&continueWithoutVerification=false
```

`businessUnit` selects the national network: `DPD-NL`, `DPD-CH`, `DPD-BE`, `DPD-HU`, `CHR-PT` (Chronopost
Portugal — answers current PT parcels with country PT but only a few milestones; use `tracking.dpd.pt` for PT),
`BRT` (IT), `JADLOG` (BR). DPD UK is separate: `GET https://apis.track.dpd.co.uk/v1/reference` (keyless).

### Response shape — `POST /v10/parcels/details/<number>` → 200 JSON

```
{
  "parcelNumber": "...", "shipmentId": "...",
  "status": { "status": <int>, "description": "DELIVERED"|..., "deliveryType": "HOME",
              "eventDateAndTime": "2026-07-16T10:41:30", "eventDateAndTimeZoneId": "Europe/Zurich"|"+02:00",
              "city": ..., "countryCode": "NL", "homeDelivery": true },
  "deliveryDate": "2026-07-16",
  "parcelSearchType": "BY_PARCEL_NUMBER",
  "sender": {...}, "receiver": {...}, "weight": 1.4,          // verified (postcode) lookups only
  "parcelHistory": [ { "status": <int>, "description": "PARCEL_HANDED"|...,
                       "eventDateAndTime": "...", "eventDateAndTimeZoneId": "+02:00"|"Europe/...",
                       "city": ..., "countryCode": ... } ],   // milestone enumeration, may arrive out of order
  "parcelEvents": [ { "date": "2026-07-16", "time": "10:12:00", "eventType": "DEY", "eventTypeText": "...",
                      "translation": "Your parcel has been delivered successfully",
                      "country": "CH"|"UNDEFINED", "buShortName": "DPD-CH", "depotCountry": "CH",
                      "city": ..., "pudoId": ..., "podUrl": ... } ],   // scan codes, verified lookups only
  "shipmentBUCode": "036", "returning": false, "refused": false, "isMasterParcel": true, ...
}
```

- Without a postcode (`continueWithoutVerification=true`) the reply has `parcelHistory` only: each milestone
  once, newest date, offset but **no place**, and `sender`/`receiver` are empty — mark the history truncated.
  With a postcode, `parcelEvents` adds the full scan list (newest first) with places, `pudoId`, `podUrl`.
- `eventDateAndTimeZoneId` is an IANA zone (`Europe/Zurich`) on `status` and a numeric offset (`+02:00`) on
  history entries; a clock with no zone is read in the BU's local zone (NL: Europe/Amsterdam).
- `status.countryCode` is DPD's **current** country for the parcel — it changes as the parcel travels; use it
  for per-country recognition (NL adapter claims only `NL`).

### myDPD status enumeration (`parcelHistory[].description` / `status.description`)

| description | meaning | postbox status |
|---|---|---|
| ORDER_CREATED | order/label data received | PENDING |
| PARCEL_HANDED | handed to DPD (re-dated when another DPD unit takes over cross-border) | IN_TRANSIT |
| IN_TRANSIT | on its way | IN_TRANSIT |
| AT_DELIVERY_CENTER | at delivery depot | IN_TRANSIT |
| PARCEL_OUT_FOR_DELIVERY | on the van today | OUT_FOR_DELIVERY |
| AVAILABLE_FOR_COLLECTION | at pickup parcelshop (unlocks the shop name) | OUT_FOR_DELIVERY (pickup point) |
| UNSUCCESSFUL_DELIVERY_ATTEMPT | failed attempt — a retry, not terminal | EXCEPTION |
| RETURN_TO_SENDER | terminal return | EXCEPTION (returned) |
| DELIVERED | delivered | DELIVERED |
| OTHER | catch-all, keeps the previous stage | UNKNOWN (keep previous) |

Scan codes (`parcelEvents[].eventType`, verified lookups): `CNP`/`CNA`/`CRY` pre-acceptance (label data /
parcel data / collection booked → PENDING), `PKY` collected from sender (→ IN_TRANSIT), `ORI` origin depot in,
`CCO` customs cleared out, `SPL`/`LHI` line haul, `HUI`/`HUS` hub in/out, `DLI` delivery depot in, `DLS`/`DLQ`
sorted / waiting at delivery depot, `DLO` out for delivery (→ OUT_FOR_DELIVERY), `DEY` delivered,
`DEYY` proof of delivery (carries `podUrl`), `DEX`/`DLR`/`DLW` failed round / back to depot / held (→ EXCEPTION),
`DOPKY`/`DODEH`/`DODEI`/`DODEY` pickup-point hand-in / collected / waiting (twin of AVAILABLE_FOR_COLLECTION) /
collected by recipient (twin of DELIVERED), `ENA` internal data exchange, `MSDLX`/`MSDLO`/`MIDLI`/`MII`/`MIA`
notices. Unmapped codes keep the stage of the scan before them; `UNDEFINED` country means no place.

### Error shapes (NL/guest API)

| case | signal |
|---|---|
| unknown number | HTTP 404 (empty), or 400 `{"exceptionType":"PARCEL_NOT_FOUND"}` without postcode → NotFound |
| wrong postcode | 400 `{"exceptionType":"PROVIDE_ZIP_CODE"}` → retry once without verification |
| other 400 / reply without milestones | inconclusive (not NotFound) |
| 401 | token expired → refresh chain once |
| 429 | rate limited, respect `retry-after` |
| 502/503/504 | service unavailable (503 is never about the parcel) → one replay after 1–3 s |
| Cloudflare challenge page on the HTML pages | use the JSON API instead |

## Tracking number formats (for the detection engine)

- Every DPD network issues the same **14-digit numeric** parcel number; labels may append **one check
  character** computed per ISO/IEC 7064 MOD 37,36 over the label's routing string. A number typed with the
  check character is looked up by its 14 digits; a non-matching character is a typo (the PT tracker answers
  not-found for it). The 14 digits themselves carry no check.
- The **first 4 digits are the sending depot**. Known ranges: PT 0955–0988, CH 0600–0619, AT 0620+, BE
  customers carry `034` in the label's GeoData. So the number hints the *origin* country only — never the
  delivering one.
- Detection rule to add with the adapter: `^\d{14}([0-9A-Z])?$` → DPD candidate (all countries; optional PT
  prefix rule `^09(5[5-9]|[6-8]\d)` as a weak hint). Format alone cannot pick the country — the parallel probe
  decides (PT via `tracking.dpd.pt`, others via the guest API with the right `businessUnit`, claiming only when
  `status.countryCode` matches). PT also accepts free-form reference numbers on its form.
- Do **not** claim `JVGL…` (see below) and note the existing 12–18-digit numeric overlap with EcoScooting —
  DPD's 14 digits fall inside that range, so the numeric rule must stay below the probe in priority.

## Country inference & cross-border

- The country is **not** reliably inferable from the number; supply it (config per adapter instance) or probe:
  guest API with `businessUnit=DPD-<CC>` and check `status.countryCode`; `tracking.dpd.pt` only knows parcels in
  DPD Portugal's network (unknown otherwise — verified live).
- A single guest-API endpoint covers cross-border DPD parcels **if the right BU is used**; any BU answers
  group-wide but reports the parcel's current country. From the delivery depot on, a parcel placed in another
  country names that country's unit (`delivery_carrier` derivable from `status.countryCode` + delivery-side
  description).

## Overlap hazard: DPD ↔ DHL eCommerce (JVGL…)

- `JVGL` + 12–20 digits is a **DHL Parcel/eCommerce piece ID** (the label's bottom barcode); postbox already
  routes `JVGL\d{12,20}` to DHL. DPD parcels frequently hand off to/from DHL eCommerce on cross-border lanes,
  and a handoff can create a secondary partner tracking ID — both numbers may appear in tracking.
- Which page is authoritative: the **carrier doing the physical last mile** — if the parcel has been handed to
  DHL eCommerce, DHL's page (the JVGL number) carries the final events; if DPD keeps it, `tracking.dpd.pt` /
  myDPD do. In myDPD responses a handoff to another DPD unit shows as `PARCEL_HANDED` re-dated *after* later
  milestones plus a `status.countryCode` change.
- So detection probes do not double-claim: 14-digit numbers go to DPD candidates only, JVGL numbers to DHL
  only, and postbox's first-hit-wins parallel probe keeps one carrier per parcel. The DPD NL adapter must
  return NotFound unless `status.countryCode == "NL"` (the guest API is group-wide); the PT tracker already
  answers not-found for parcels outside its network.

## Fixtures

In `docs/carriers/dpd/fixtures/` (all synthetic — invented numbers, names, coords, clocks; shapes mirror live
responses captured 2026-11-26 and third-party live captures):

- `pt-in-transit.html` — `GET /pt?reference=` page, out-for-delivery parcel (OFD/PEC/HUB/INB rows).
- `pt-delivered.html` — delivered parcel: POD row with signatory in the comment, `.current-status` = delivered.
- `pt-failed-attempt.html` — 506 (recipient absent) then 403 back at depot → EXCEPTION.
- `pt-unknown-number.html` — the page's not-found answer (`No results were found for …`), captured live.
- `nl-in-transit-200.json` — guest API details, unverified (postcode-less): `parcelHistory` only.
- `nl-delivered-200-verified.json` — verified lookup: `parcelEvents` scan codes (ORI/DLI/DLO/DEY/DEYY with
  `podUrl`) + `parcelHistory` twins + sender/receiver/weight.
- `nl-exception-200.json` — `UNSUCCESSFUL_DELIVERY_ATTEMPT` milestone (EXCEPTION, retry follows).
- `nl-unknown-400-parcel-not-found.json` — 400 `exceptionType: PARCEL_NOT_FOUND` (no postcode).
- `nl-unknown-404.txt` — plain 404 variant.

## TODOs

- Capture real in-transit/delivered/exception fixtures with own parcels (redacted) to confirm the PT code
  table and the `CHR-PT` guest-BU behaviour for PT.
- Measure actual rate-limit thresholds on the guest API and on `tracking.dpd.pt` (bot-check frequency).
- Confirm whether `tracking.dpd.pt` exposes any JSON endpoint; today the lookup is HTML-only (Drupal form).
- POD image (`podUrl`) and the map-link coordinates are deliberately not read.

## Sources

- DPD NL integrations docs — public myDPD tracking URL
  `https://www.dpdgroup.com/{BU}/mydpd/my-parcels/search?lang={lang}&parcelNumber={14-digit}`:
  https://integrations.dpd.nl/dpd-shipper/other-information/dpd-tracking/
- DPD BE integration docs — same generic link format: https://it-integration.dpd.be/dpd-shipper/tracking/public-url-tracking/
- DPD Parcel Label Specification 2.4.1 (check digit = ISO/IEC 7064 MOD 37,36):
  https://esolutions.dpd.com/dokumente/DPD_Parcel_Label_Specification_2.4.1_EN.pdf
- Live probes 2026-11-26: `GET https://tracking.dpd.pt/pt?reference=09990000000404` (server-rendered not-found
  page); `tracking.dpd.pt/en/getting-parcel/track-trace`; `www.dpdgroup.com/nl/mydpd/my-parcels/search?…`
  (Cloudflare-blocked to non-browser clients).
- Third-party implementations read for endpoint shapes: `plhery/universal-parcel-scraper`
  (`carriers/dpd`, `carriers/dpd-nl`, `carriers/dpd-pt` incl. scan-code tables and synthetic fixtures),
  `ha-parcel-integrations/ha-dpd` (`countries/general` Keycloak/guest-token chain, const URLs),
  `LRvdLinden/nl.lrvdlinden.myparcel` (`lib/dpd-tracking.js` — BU map incl. `CHR-PT`, myDPD guest flow),
  `thekumi/dpdtrack` (mydpd.at `jws.php/parcel/search` JSON shape for AT), `michelmelo/dpd-track-trace`
  (legacy dpd.pt form), `trackingdb.com/carriers/dpd`, `trackingnumber.fyi/format/dpd/dpd`.
