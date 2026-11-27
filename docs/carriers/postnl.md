# PostNL — consumer track & trace endpoint (reverse-engineered) (G-70)

Research date: 2026-11-27. Scope: the lookup behind the consumer tracking page on
`tracking.postnl.nl` (the flow behind postnl.nl track & trace), so the adapter in
[G-59](https://linear.app/gryphonforge/issue/G-59/postnl-adapter-consumer-tracking-with-postcode-metadata)
can be implemented without repeating discovery. The official Shippingstatus API needs a
business relationship and is out of scope. Research only — no adapter code here.

Verified live on 2026-11-27 against the API with the researcher's own parcel (3S… number
and PT postcode recorded in the G-70 comment), plus format/status documentation from
developer.postnl.nl and published community reverse-engineering (noted per section).
Fixtures live in `postnl/fixtures/` (personal data redacted: barcode, postcode, recipient
and sender names/addresses, e-mail/phone hashes, partner numbers). Reproduce with
`postnl_probe.sh` in this directory.

## TL;DR for the adapter

```
GET https://tracking.postnl.nl/track-and-trace/api/trackAndTrace/<BARCODE>-<COUNTRY>-<POSTCODE>?language=<en|nl|fr|de>
```

- The lookup key is one path segment `<barcode>-<country>-<postal code>`, e.g.
  `3SXXXX00000000-PT-4250-004` or `RR123456789NL-NL-1234AB`. URL-encode if in doubt; the
  barcode/country/postcode characters are all path-safe after uppercasing.
- **No auth, no session bootstrap, no CSRF token, no special headers** — a plain GET with
  `Accept: application/json` and a normal `User-Agent` works cookie-less (verified).
  `language` is effectively required: without it the API answers a non-JSON error.
- `200` + JSON `{"colli": {"<BARCODE>": {…}}, "processed": "…"}` → shipment found; the colli
  object is documented below.
- Unknown barcode **or wrong country/postcode** → `200` with `{"colli": {}}` (empty). The two
  are indistinguishable at this endpoint; both map to `NotFound`.
- Known but not yet moving ("pre-advised") → `200` with a populated colli whose only
  observation is `A01` ("Shipment expected, but not yet arrived or processed at PostNL").
  So `colli` non-empty ⇔ PostNL knows the number — this is what keeps detection probes from
  falling through to another carrier.
- Non-2xx (403 from Akamai bot manager, 429/503 with `Retry-After`) → `CarrierUnavailable`
  with a logged raw sample, per G-59.
- Responses are cached per exact URL (repeat requests return the same `processed`
  timestamp) — the endpoint does not poll for you; the adapter must poll itself.
- ~12 requests within a couple of minutes were all answered `200`; no rate limiting
  observed at low volume. Akamai Bot Manager sits in front (`_abck`/`bm_sz` cookies are set
  on answers) and can start answering 403 without notice — back off, don't retry harder.
  `robots.txt` disallows `/*track-and-trace/*-*` (crawler convention, not auth).

## Hosts and app layout

- `postnl.nl/track-and-trace/` is marketing; the actual consumer app is
  **`https://tracking.postnl.nl/track-and-trace/`** (Angular SPA). `jouw.postnl.nl` is the
  logged-in portal (out of scope).
- Human deeplinks: `https://tracking.postnl.nl/track-and-trace/?barcode=<n>&postcode=<p>`
  (the page also has a destination-country selector for international shipments; without a
  valid postcode it shows client-side "Invalid postcode format"). The path form
  `/track-and-trace/<barcode>-<country>-<postcode>` is the same key the API uses.
- Related API sub-resources seen in the wild (not needed for v1): `…/api/trackAndTrace/<key>/signature`
  (proof-of-delivery image), reroute/pincode/feedback endpoints.
- `robots.txt` on `tracking.postnl.nl`: `Disallow: /*track-and-trace/*-*`.

## The lookup request

### Path key and parameters

| Part | Required | Notes |
|---|---|---|
| `BARCODE` | yes | Uppercased. 3S… (13/15 chars) or S10 …NL (13 chars); see formats below. |
| `COUNTRY` | yes | ISO-3166 alpha-2 of the **delivery** country, uppercased (`NL`, `PT`, `BE`, `LU`, …). |
| `POSTCODE` | yes | Recipient postal code in the country's local format, uppercased, no spaces: NL `1234AB` (digits **and** 2 letters — digits-only is not a valid NL code), PT `4250-004` (hyphen optional: both `4250-004` and `4250004` matched with `language=en`; send the local display format), BE `1234`, LU `L-1234`/`1234`. |
| `language` (query) | effectively yes | `en|nl|fr|de` (case-insensitive observed: `en`, `nl`, `NL` all worked). Omitting it returns a non-JSON error. Controls the language of `description`/`statusPhase.message` texts. |

The key is echoed back in the response as `identification` (`"3S…-PT-4250-004"`), with the
postcode re-rendered in the country's display format.

Country derivation from the postcode shape when only the postcode is known (the adapter's
`postcode` metadata, G-59): `^[1-9]\d{3}[A-Z]{2}$` → NL, `^[1-9]\d{3}$` → BE,
`^L-?\d{4}$` → LU, `^\d{4}-?\d{3}$` → PT. Any other shape needs the country explicitly
(five digits could be DE/FR/IT/…). For an S10 …NL number the country is `NL` from the
suffix. The consumer page does exactly this with its country selector.

### Session / bootstrap

None for the API. Verified with a cookie-less client. The HTML page sets Akamai Bot
Manager cookies (`_abck`, `bm_sz`); the API answers without them at low volume but may
escalate to 403 for bot-looking clients. No CSRF token, no API key, no login.

## Response shape

Top level: `{"colli": {"<BARCODE>": {…}}, "processed": "<ISO with offset>"}`. Take
`colli[barcode]`, falling back to the first value. Selected fields of one collo:

| Field | Meaning |
|---|---|
| `barcode`, `partnerBarcode` | PostNL's own barcode; the last-mile partner's number (e.g. 12-digit PT localizador). Querying PostNL with the partner number returns empty `colli`. |
| `identification` | The `<barcode>-<country>-<postcode>` key. |
| `context` | `{countryIsoNumber, shipmentType: "Parcel"\|"LetterboxParcel"\|"Letter"}`. |
| `description` | Sender reference, e.g. `ESNL371477340/698292` (S10-like partner reference). |
| `deliveryAddressType` | `Recipient` \| `ServicePoint` \| `Collect` \| `Rerouted` \| `Sender` \| `Return` — distinguishes pickup-point and return flows. |
| `recipient`, `deliveryAddress`, `sender`, `emailHash`, `phoneHash` | **PII — never persist or log.** Only town/country are safe to keep. |
| `eta` | `{type: "Specific"\|"WholeDay"\|"OnlyFromTime"\|"MultipleDaySpan", start, end, minutesOverdue}` — expected delivery window, ISO with offset. |
| `isDelivered`, `isAtRetailLocation`, `isPickedUpAtRetailLocation`, `isReturnShipment`, `beforeFirstDeliveryAttempt`, `deliveryDate`, `hasProofOfDelivery` | Boolean/status flags; `deliveryDate` is set on handover (the handover is a date+phase, not an observation). |
| `details` | `{productCode, isRegistered, isInsured, cashOnDelivery, dimensions{height,width,depth,weight}, businessPartnerCode/Number, …}`. |
| `statusPhase` | `{index: 0–4, message, route}` — 0 announced, 1 received, 2 sorted, 3 out for delivery, 4 delivered; `message` is the localized headline. |
| `lastObservation`, `observations[]` | `observations` is the UI timeline (newest first); `analyticsInfo.allObservations` is the full list incl. non-timeline notices. Each: `{observationDate (ISO with offset), observationCode, description (localized)}`. |
| `analyticsInfo.shipmentStage` | `InTransit`, `Delivered`, … (machine-ish stage). |
| `retailDeliveryLocation`, `retailPickupBarcode` | Pickup-point info (`retailPickupBarcode` = `O` + barcode). |
| `networkPartnerName`, `networkPartnerCountry`, `linkToNetworkPartner` | Last-mile partner for international shipments, with a deep link whose `localizador=` is the `partnerBarcode` (e.g. Sending/CTT in PT). |
| `internationalStatus` | `{isForeignShipment, phase: "ReceivedByPostNl"\|"DeliveredAtRecipient"\|…, delay}`. |
| `alerts[]` | UI alerts (e.g. delivered notice). |

Quirk observed once: a response contained the key `expectedReturn\nDate` with a literal
newline inside the JSON string — strict parsers may choke on that; normalize keys
whitespace-tolerantly.

### Not found vs no-events vs error

- `200` + `{"colli":{}}` → `NotFound` (unknown number **or** wrong postcode/country — same
  answer, verified: real 3S number with `NL-1234AB` → empty).
- `200` + populated colli with only `A01` → known, pre-advised, no events yet (NOT
  NotFound — detection must treat this as "PostNL knows it").
- `4xx/5xx` (403 Akamai, 429/503 with `Retry-After`) → `CarrierUnavailable`, log a raw
  sample. Missing `language` → non-JSON error.

## Status vocabulary and normalization (for G-59)

Normalized enum in this repo: `TrackingStatus { PENDING, IN_TRANSIT, OUT_FOR_DELIVERY,
DELIVERED, EXCEPTION, UNKNOWN }`; events keep PostNL's original `description` text.

Observed `observationCode` values (EN text / NL text) on a real NL→PT parcel, plus the
stage vocabulary:

| Code | EN description | NL description | Suggested status |
|---|---|---|---|
| `A01` | Shipment expected, but not yet arrived or processed at PostNL | Pakket is nog niet door PostNL ontvangen of verwerkt | PENDING |
| `A98` | Pre-alerted shipment enriched by PostNL management. | Voorgemelde zending verrijkt door PostNL regie. | (ignore) |
| `A95` | Delivery cannot be changed | Bezorging wijzigen niet mogelijk | (ignore) |
| `A04` | PIOS notice | PIOS melding | (ignore) |
| `B01` | Shipment received by PostNL | Pakket is ontvangen door PostNL | IN_TRANSIT |
| `J01`/`J06` | sorting (sorted in sorting centre) | Zending gesorteerd in sorteercentrum | IN_TRANSIT |
| `K50` (and `K*`) | RCS notice / problem or revised delivery moment | RCS melding | EXCEPTION if problem |
| `Q11` | Parcel linked to packaging tool | Pakket aan emballagemiddel gekoppeld | (ignore) |
| `Q12` | Your parcel has crossed the border | Pakket onderweg naar land van bestemming | IN_TRANSIT |
| `X02` | Your shipment has arrived in the destination country | …aangekomen in het land van bestemming | IN_TRANSIT |
| `X08` | Your parcel's at a sorting centre in the destination country | …ligt op een sorteercentrum in land van bestemming | IN_TRANSIT |
| `Y05` | Your shipment is on its way to the recipient | Je zending is onderweg naar de ontvanger | OUT_FOR_DELIVERY (last-mile) |
| `J02` | waiting at a PostNL point | ligt klaar bij PostNL-punt | IN_TRANSIT (at pickup point) |
| `J05` | courier on the road | Bezorger is onderweg | OUT_FOR_DELIVERY |
| `Z01` | Yes! Your shipment has been delivered to the recipient | Yes! Je zending is bezorgd bij de ontvanger | DELIVERED |
| `H04` | Parcel damaged, going back to sender | Pakket is beschadigd, gaat retour afzender | EXCEPTION/return |

Headline/stage fields: `statusPhase.index` 0–4 as above; `analyticsInfo.shipmentStage`
(`InTransit`, `Delivered`, …); `internationalStatus.phase` (`ReceivedByPostNl`,
`DeliveredAtRecipient`, …); `deliveryAddressType` (`Recipient`, `ServicePoint`, `Collect`,
`Rerouted`, `Sender`, `Return`) distinguishes pickup-point collection and returns —
`isPickedUpAtRetailLocation`/`isAtRetailLocation` tell a collected-at-point delivery.
Delivery itself is `statusPhase.index == 4` / `shipmentStage == "Delivered"` /
`isDelivered == true` + `deliveryDate` (+ `Z01` in `allObservations`), not an observation
entry in the UI list.

## Tracking number formats (for the detection engine rules)

- **3S barcodes**: `3S` + 1–4 letter customer range + 7–12 digits, total 13 or 15 chars
  (developer.postnl.nl). A real 13-char 3S code was used for the live probes (recorded in
  the G-70 comment). "Subtypes" like `3SKGJD…`
  are just different customer ranges — the prefix rule is the same:
  `^3S[A-Z]{1,4}\d{7,12}$` (case-insensitive).
- **2S** letterbox parcels share the 3S shape with a 2S type.
- **S10 / UPU**: 13 chars, 2 letters + 9 digits + 2-letter country, ending `NL` for PostNL
  (e.g. `CC123442066NL`, `RR123456789NL`); check digit per UPU S10 (the repo already has
  `hasValidS10CheckDigit` in `TrackingNumberRules`). Rule: `^[A-Z]{2}\d{9}NL$` + valid
  check digit → PostNL.
- PostNL↔partner linkage: for international shipments PostNL couples the 3S with a
  partner/S10 barcode, exposed as `partnerBarcode`, `description` and
  `linkToNetworkPartner?localizador=<partnerBarcode>`. The same last-mile digits are
  **not** known to PostNL (querying them returns empty `colli`) — e.g. the 12-digit PT
  localizador belongs to the PT last-mile partner (CTT/Sending), whose own S10…PT numbers
  the existing CTT rule already claims. So: S10 ending `NL` → PostNL (country `NL` +
  NL postcode), S10 ending `PT` → CTT, 3S… → PostNL. First-hit-wins probing plus these
  rules prevent double-claiming.
- The lookup pairs barcode + country + postcode: both act as the secret. The adapter's
  `postcode` metadata (G-59) plus the country derivable from the postcode shape (or `NL`
  from an S10 suffix) is enough.

## Fixtures

In `docs/carriers/postnl/fixtures/`:

- `delivered-3s-pt-200.json` — real `200` response for a 3S number with a PT postcode
  (`language=en`), delivered NL→PT parcel: full observation timeline (`A01` → `B01` →
  `J06` → `X02` → `Q12` → `Y05`/`X08` → `Z01`), `statusPhase.index=4`,
  `shipmentStage="Delivered"`, `eta` MultipleDaySpan, partner link fields. Personal data
  redacted (barcode, postcode, names, addresses, hashes, partner numbers).
- `pre-advised-200.json` — synthetic "pre-advised, no events yet" response (A01-only
  colli, `statusPhase.index=0`, `isInPreAnnouncedStatus=true`), built from the A01 shape
  observed live; replace with a captured live one when available.
- `unknown-number-200-empty-colli.json` — unknown 3S + NL postcode → `{"colli":{}}`.
- `wrong-postcode-200-empty-colli.json` — real 3S number with a wrong country/postcode →
  same empty `colli` (indistinguishable from unknown).

## TODOs

- Capture a **live** pre-advised fixture and a live **S10 …NL** fixture (none at hand).
- Capture response **headers** (`_abck`/`bm_sz` Set-Cookie, `Retry-After` on 429/503) —
  not observable from the research environment used here; secondary sources report them.
- Full observation-code table: PostNL publishes scan codes only in the Shippingstatus API
  docs (business API); extend the table above from live parcels as they are seen.
- Confirm whether digits-only PT postcodes (`4250004`) are reliable across `language`
  values — one `language=nl` request with the digits-only form returned an empty `colli`
  while the hyphenated form worked in all languages; send the local display format.
