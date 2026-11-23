# Paack — consumer tracking endpoint (reverse-engineered)

Research for [G-72](https://linear.app/gryphonforge/issue/G-72), feeds the adapter in
[G-61](https://linear.app/gryphonforge/issue/G-61). Captured 2026-11-23 against the live
public tracking page using a headless browser (REA `capture_browser_scenario`) plus plain
`curl` probes. Paack's merchant API (paack.readme.io) needs merchant credentials and is
out of scope; everything below works **without any auth**.

## TL;DR for the adapter

```
GET https://paack.co/shipments/tracking/order
    ?tracking_number=<number>&postal_code=<postal code>
    &_data=routes%2Ftracking.order        # Remix data request -> raw JSON
```

- No cookies, no session bootstrap, no tokens, no special headers required (a normal
  `User-Agent` is enough). Verified with cookie-less curl.
- `200` + `application/json` + `x-remix-response: yes` → order found, body is the loader
  JSON documented below.
- Unknown number **or** wrong postal code → `204 No Content` with
  `x-remix-redirect: /tracking?…&err=true` and `x-remix-status: 302` (empty body).
  Without `_data`, the same case is a plain `302` document redirect to
  `/shipments/tracking?…&err=true`.
- Missing parameter (e.g. no `postal_code`) → `400` with `x-remix-catch: yes` and body
  `Missing required parameters`.
- The order page does **not** poll; the adapter must poll the endpoint itself (e.g. every
  5–15 min while the parcel is in transit).
- No rate limiting observed (24 rapid requests, all answered). The site is
  Cloudflare-fronted (`server: cloudflare`, `cf-cache-status: DYNAMIC`).

## Hosts and app layout

- `mydeliveries.paack.app/tracking` → `302` → `https://paack.co/shipments/tracking`.
  All API calls go to **`paack.co`** with basePath `/shipments` (asset requests on
  `mydeliveries.paack.app` also 302 to `paack.co`).
- The app is a Remix (React Router) SSR app. Client bundle: `/shipments/build/*.js`;
  the lookup route module is `routes/tracking.order`.
- The home form on `/shipments/tracking` is a plain `GET` form with fields
  `tracking_number`, `postal_code`, optional hidden `email` / `phone` (present when
  arriving from an SMS/email tracking link), and `lang_select`.
- Locale is client-side only (i18next; `lang_select` in the header, translations at
  `/shipments/locales/<lng>/{translations,tracking}.json?v=v1.30.0`). Extra query params
  `store`, `culture`, `locale`, `lang` are ignored by the payload — verified.

## Request parameters

| Param | Required | Notes |
|---|---|---|
| `tracking_number` | yes | The consumer-facing order/parcel number (see formats below). |
| `postal_code` | yes | Delivery postal code, country-specific format. PT works as `4250-004` (hyphen required; `4250004` → not found). ES is 5 digits, e.g. `28001`. |
| `email` / `phone` | no | Passed through from tracking links; when they match the order, personal fields (`full_name`, `email`, `phone_number`, address) are populated and `profileMismatch` stays false. |
| `_data=routes/tracking.order` | no | Remix data request: returns JSON instead of HTML. Use this in the adapter. |

Client-side validation is only "non-empty and length > 3" for both fields
(`orderNumberErrorInputHint` / `postalCodeErrorInputHint`); the server does the real check.

## Response shapes

### Found — `200 application/json` (data request)

Top level (loader data of `routes/tracking.order`):

```json
{
  "activeEvent": { "…": "latest event, see below" },
  "eventList": [ "…timeline events, newest last; last entry is often the pending next step" ],
  "triggeredEventList": [ "…all events incl. non-timeline ones" ],
  "orderTrackData": { "…raw order, see below" },
  "title": "Paack | Track your order",
  "feedbackSuccess": false,
  "profileMismatch": false,
  "trackingContactProvided": false,
  "__meta__": { "…": "date/undefined markers used by the serializer" }
}
```

`orderTrackData`:

```json
{
  "retailer_name": "Amazon",
  "retailer_id": "<uuid>",
  "order_id": "<uuid>",            // == family_id usually
  "family_id": "<uuid>",
  "external_id": "<retailer order ref>",   // NOT the tracking number
  "tracking_id": "<uuid>",
  "service_type": "",
  "parcel_amount": 1,
  "delivery_type": "direct",       // or "reverse" (returns)
  "expected_delivery_ts": { "start": "…ISO", "end": "…ISO" },
  "expected_pickup_ts": { "start": "…ISO", "end": "…ISO" },
  "full_name": null, "email": null, "phone_number": null,   // populated if email/phone matched
  "delivery_address": { "country": "PT", "post_code": "4250-004" },
  "pudo_id": null, "pudo_name": null, "pudo_expiration_date": null, "pudo_address": "",
  "events": [ { "uniq_id": "…", "id": "SM001", "timestamp": "…ISO", "location": { "city": null, "country": "PT" } } ],
  "passcode": null, "pudo_passcode": null
}
```

Events (`eventList` / `triggeredEventList` entries):

```json
{
  "id": "SP002",                   // stage code, see enum below
  "label": "inDelivery",           // normalized status key, see vocabulary below
  "event_type": "direct",          // "direct" | "reverse"
  "uniq_id": "2026-11-23 10:13:13 +0000 UTCSP0020",
  "timestamp": "2026-11-23T10:13:13.000Z",   // null while the event is still pending
  "next_id": "SP003",
  "timeline": true,                // false for non-timeline events (inProgress, driverAssigned)
  "icon": "Truck-Side",
  "status": "success",             // present on the final/pending event
  "secondary_section": "review",   // e.g. triggers the rating widget on delivered
  "variables": {
    "Header":  [ { "poe": "hourTimeslotStarts", "path": "expected_delivery_ts.start", "field_type": "time" } ],
    "Text":    [ { "poe": "retailerName", "path": "retailer_name" } ],
    "Summary": [ … ], "Meta": [ … ]
  },
  "location": { "city": null, "country": "PT" },
  "action": null
}
```

`variables.*` entries are template bindings: `poe` is the translation variable name,
`path` points into `orderTrackData` (e.g. `retailer_name`, `expected_delivery_ts.start`,
or `events.timestamp` with a `condition` like `{"sourceField":"id","operator":"eq","value":"SP003"}`),
`field_type` is `date|time`. **Failure free text is carried the same way** — a
`variables.*[]` entry whose `path` resolves to the reason field on the event/order
(exact path to be confirmed with a real failed-attempt fixture, see TODOs).

The HTML document variant embeds the identical structure at
`window.__remixContext.state.loaderData["routes/tracking.order"]`.

### Not found — `204` (data request) / `302` (document request)

```
HTTP/2 204
x-remix-redirect: /tracking?postal_code=28001&tracking_number=XX000000000000&err=true
x-remix-status: 302
```
(empty body). UI then shows "Order not found. Incorrect order number or postal code."
→ map to `NotFound`.

### Validation error — `400`

```
HTTP/2 400
x-remix-catch: yes

Missing required parameters
```
→ missing/invalid params. Treat as a client error, not NotFound.

### Server error / endpoint change

Not observed. Remix marks thrown errors with `x-remix-catch: yes` (5xx) — degrade to
`CarrierUnavailable` with a logged raw sample, per G-61.

## Status vocabulary (`label`)

`delivery_type: "direct"`: `manifested`, `scannedAtOrigin`, `inProgress`, `driverAssigned`,
`inDelivery`, `delivered`, `delivered3rdParty`, `deliveredHousehold`, `deliveredSafePlace`,
`droppedInPudo`, `pudoAssigned`, plus failure/return labels: `absent` (customer absent),
`incorrectAddress`, `codNotAccepted` (missing cash on delivery), `attemptedOther` (failed
delivery attempt), `rejected` (rejected by customer), `cancelled`, `logisticIssueOpen`
(rescheduled), `logisticIssueClosed`, `orderReactivated`,
`appointmentChangedToNextAvailableDay`, `additionalDeliveryAttemptScheduled`,
`returnAbsent`, `returnOther`, `returnCodNotAccepted`, `incorrectAddressReturn`,
`returnedToSender`, and PUDO failures `pudoFull`, `pudoClosed`, `rejectedByPudo`,
`lostByPudo`, `inPudoToReturn*`. `delivery_type: "reverse"` has its own set (`collected`,
`collectionPlanned`, `notCollected*`, …).

Event stage-code enum seen in the client bundle: `SA001–SA005`, `SC000/SC001/SC007`,
`SI000–SI015`, `SM001/SM002/SM003/SM005`, `SP001/SP007/SP008/SP009/SP015/SP021/SP031/SP034`,
`SR001/SR002`. Observed mapping: `SM001=manifested`, `SM002=scannedAtOrigin`,
`SP001=inProgress`, `SP021=driverAssigned`, `SP002=inDelivery`, `SP003=delivered`.

Suggested normalization for G-61 (confirm against postbox's enum): `manifested` /
`scannedAtOrigin` / `inProgress` / `driverAssigned` → in transit; `inDelivery` →
out-for-delivery; `delivered*` / `droppedInPudo` → delivered; `absent` /
`incorrectAddress` / `codNotAccepted` / `attemptedOther` / `logisticIssue*` → EXCEPTION
(delivery failure, reason text preserved from the event's `variables` bindings, retry
usually follows); `rejected` / `cancelled` → exception/cancelled; `return*` /
`returnedToSender` → returned.

## Tracking number formats

- Observed in practice: **18-digit numeric** (e.g. `534000021268935007`).
- The site accepts any string of length > 3 client-side; formats vary by merchant
  (per G-72). For the detection engine: accept alphanumeric, length ≥ 4; don't hard-code
  18 digits.
- The lookup pairs the number with the delivery **postal code** — both are required and
  both act as the "secret". Wrong postal → same not-found redirect as an unknown number.

## Fixtures

In `docs/carriers/paack/fixtures/`:

- `unknown-number.txt` — 204 + `x-remix-redirect` headers (data request) and the 302
  document variant, unknown number `XX000000000000`.
- `missing-params-400.txt` — 400 + `x-remix-catch` + `Missing required parameters`.
- `out-for-delivery-200.json` + `out-for-delivery-200.headers.txt` — real 200 response
  (parcel out for delivery; eventList `manifested → scannedAtOrigin → inDelivery →
  delivered(pending)`), personal data redacted (ids, retailer, post_code, tracking
  number replaced with placeholders).

## TODOs

- Capture a real **delivered** fixture (parcel `534000021268935007` was out for delivery
  on 2026-11-23, window 08:00–21:00 UTC — a re-poll is scheduled) and a real
  **failed attempt** fixture to confirm the exact `variables.*[].path` that carries the
  free-text failure reason.
- Related resource routes seen in the bundle (not needed for tracking): `POST
  /shipments/api/push-subscribe`, `POST /shipments/api/tracker/rating` (`action=store_rating`).
