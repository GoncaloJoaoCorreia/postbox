# CTT (Correios de Portugal) — public object-tracking endpoint research (G-69)

Research date: 2026-11-23. Scope: the "Seguir objeto" (track object) flow on ctt.pt, so the
adapter in G-58 can be implemented from documentation instead of guesswork.
Research only — no adapter code here. Fixtures live in `fixtures/ctt-*.json` (personal data
redacted: recipient town, receptor name, sender mobile, contract/client numbers). Reproduce
with `ctt_probe.py` in this directory.

## TL;DR

The legacy endpoint from `jupitern/ctt` (`POST https://www.ctt.pt/feapl_2/app/open/objectSearch/objectSearch.jspx`
with `showResults=true&objects=...`, HTML-scraped) is **stale**: today that JSPX page is only a
wrapper — it embeds an iframe `https://appserver.ctt.pt/CustomerArea/PublicArea` (an OutSystems 11
React app), and the result rows are no longer rendered into the JSPX HTML server-side.

The real API is the OutSystems app's JSON "screenservices" calls. Plain HTTP from a server works —
**no session, no login, no captcha token needed** (verified): the static anonymous CSRF token
suffices. Two endpoints matter:

```
# 1) search — last event per object, up to 25 codes per request
POST https://appserver.ctt.pt/CustomerArea/screenservices/DF_TrackTrace_CW/TrackTrace/TT_SearchObjectPublicArea_Mobile/Actionnc892c0qmdsyqlc87

# 2) detail — full event timeline for one object
POST https://appserver.ctt.pt/CustomerArea/screenservices/CustomerArea/CustomerArea/PublicArea_Detail/DataActionGetObjectEventsByInputObjectCode
```

The UI gates the search button behind a reCAPTCHA v2 checkbox (site key
`6LdjViIsAAAAAK-Q8ut3lM2SspVMwSKZFkE_znvA`), but the server action accepted an empty `Token`
over plain HTTP — the captcha is client-side gating, not enforced server-side at low volume.

## Working request recipe (verified 2026-11-23, HTTP 200)

```bash
MODVER=$(curl -sS "https://appserver.ctt.pt/CustomerArea/moduleservices/moduleversioninfo?$(date +%s%3N)" \
  -H "Accept: application/json" -H "X-CSRFToken: T6C+9iB49TLra4jEsMeSckDMNhQ=" | python3 -c 'import sys,json;print(json.load(sys.stdin)["versionToken"])')

curl -sS -X POST "https://appserver.ctt.pt/CustomerArea/screenservices/DF_TrackTrace_CW/TrackTrace/TT_SearchObjectPublicArea_Mobile/Actionnc892c0qmdsyqlc87" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Accept: application/json" \
  -H "X-CSRFToken: T6C+9iB49TLra4jEsMeSckDMNhQ=" \
  -H "User-Agent: <browser UA>" \
  --data "{\"versionInfo\":{\"moduleVersion\":\"$MODVER\",\"apiVersion\":\"xMqLBWshH4NPqyhLlM+eaA\"},\"viewName\":\"CustomerArea.PublicArea\",\"screenData\":{\"variables\":{...}},\"inputParameters\":{\"ObjectNumbers\":\"DD506882333PT\",\"IsFromSearch\":true,\"IsObjectDetail\":false,\"Token\":\"\"}}"
```

Request skeleton (all screenservices POSTs share it):

- `versionInfo.moduleVersion` — fetch per run from `GET /CustomerArea/moduleservices/moduleversioninfo?<ts>`
  (currently `7GsbNO4iKdHWyY3Hqp3wFA`); the response flags `hasModuleVersionChanged` if stale.
- `versionInfo.apiVersion` — **per action**: `xMqLBWshH4NPqyhLlM+eaA` (search),
  `o0WgZ3QtqpbJXXi+QDtVYA` (detail). Both change on redeploy; the response flags `hasApiVersionChanged`.
- `viewName` — fully-qualified screen: `CustomerArea.PublicArea` (search) / `CustomerArea.PublicArea_Detail` (detail).
- `screenData.variables` — the screen's variable tree; an (almost) empty one works (template captured
  from the live page is embedded in `ctt_probe.py`). For the detail action set `ObjectCodeInput`.
- `inputParameters` — search: `ObjectNumbers` (comma-separated), `IsFromSearch`, `IsObjectDetail`, `Token`.
- Headers: `Content-Type: application/json; charset=UTF-8`, `Accept: application/json`,
  `X-CSRFToken: T6C+9iB49TLra4jEsMeSckDMNhQ=` (the static anonymous token hardcoded in OutSystems.js;
  the `nr2Users` cookie carries the same `crf=` value, so no cookie is needed).
- A **browser-like User-Agent is required** — Cloudflare in front of appserver.ctt.pt rejects
  default tool UAs with `403 Error 1010 (browser signature banned)`.

## Response shapes (HTTP 200, `application/json`)

Search:

```json
{
  "versionInfo": {"hasModuleVersionChanged": false, "hasApiVersionChanged": false},
  "data": {
    "Result": {"isIpLocked": false, "IsObjectsLengthBiggerThan25": false,
               "Result": {"Success": true, "ErrorMessage": ""}},
    "ObjectLastEvents": {"List": [ { "ObjectCode": "DD506882333PT", "Found": true, "CTTObject": true,
      "RecipientCountryCode": "PT", "RecipientPostalCodeAndTown": "PORTO",
      "LastEvent": {"DateTime": "2026-11-21T10:40:46Z", "State": "Entregue", "StateId": 12,
                    "Event": "O envio foi entregue. O processo de envio terminou.", "EventCode": "EMI",
                    "Local": "Centro operacional Porto", "Progress": 100, "ReceptorName": "..."} ,
      ... } ]},
    "ObjectsToSearchList": {"List": ["DD506882333PT"]}
  },
  "rolesInfo": ""
}
```

Object record fields (`ObjectLastEvent_LibRec`): `ObjectCode, ObjectImageURL, ObjectSenderImageURL,
RelabelObjectCode, ObjectName, IsTracked, Found, CustomsPurposes, Sender, SenderCountryCode,
Recipient, RecipientCountryCode, RecipientAddress, RecipientPostalCodeAndTown, RecipientTimeSlot,
UserEmailMatch/UserSenderMatch/UserRecipientMatch/UserPhoneMatches, IsLocker, LockerName,
CreationDate, ClearanceDate, DeliveryTimeSlot, DeliveryDeadlineEnd, DeliveryDeadlineDayAndMonthForStories,
CTTObject, Carrier, CarrierID, CarrierLogoURL, ContractNumber, ClientNumber, FollowObject_Status,
ShipmentProduct, ClientReference, IsB2C, ObjectNexus, LastEvent{...}, JoinedDeliveries, RelatedObjects`.

Detail adds `data.ObjectEventsFromQuery` = same object record plus `Events.List` (full timeline,
**newest first**) and `SpecialServices.List`. Event record: `DateTime` (ISO-8601 Z), `State` (PT text),
`StateId`, `Event` (PT text), `EventCode`, `EventColorType`, `Local`, `Situation`, `Reason`,
`Progress` (0–100), `PointCode`, `ReceptorName` (only on delivery/POD), `LockerName`, `WithdrawalDate`,
`TypeOfTimelineEvent`, `Nexus`, `DestinationNodeName`.

Observed `StateId` / `State` / `EventCode` pairs (from fixtures):

| StateId | State (PT) | EventCode | Progress | meaning |
|---|---|---|---|---|
| 1 | Aguarda entrada nos CTT | EMO | 0 | pre-advised, no scans yet |
| 2 | Aceite | EMA | 3 | accepted |
| 11 | Em trânsito | EMB | 50 | at operational centre / in transit |
| 7 | Em entrega | EMZ | 80 | out for delivery |
| 13 | Não entregue | EMH | 90 | delivery attempt failed (`Reason` set) |
| 12 | Entregue | EMI | 100 | delivered (`ReceptorName` = POD) |

## Error / edge shapes (all HTTP 200 — status is in the body)

| situation | body | adapter mapping (G-58) |
|---|---|---|
| unknown or malformed number | `Result.Success=true`, `ObjectLastEvents.List[i].Found=false` (not an error) | `NotFound` |
| pre-advised, no scans yet | `Found=true`, `LastEvent.StateId=1` (EMO) — keep distinct from `NotFound` | "no events yet" |
| >25 codes in one request | `IsObjectsLengthBiggerThan25=true`, `Success=false`, PT `ErrorMessage` ("...só pode pesquisar até 25..."), empty list | bad request; chunk to ≤25 |
| empty input | `ObjectLastEvents.List=[]` | bad request / NotFound |
| lowercase input | normalized to uppercase (`dd506882333pt` → found as `DD506882333PT`) | uppercase before sending |
| internal spaces | not stripped server-side (`"DD506882333 PT"` → Found=false); client trims | trim client-side |
| S10 check digit | **not validated** server-side (`RR000000000PT` → Found=false, no error) | validate client-side for detection rules |
| wrong payload/viewName/apiVersion | HTTP 200 with `{"data":{},"exception":{"name":"ServerException","message":"No role validation found"}}` (apiVersion mismatch → `hasApiVersionChanged:true`) | `CarrierUnavailable`; refresh tokens |
| Cloudflare blocks non-browser UA | HTTP 403 `Error 1010` | send a real browser UA |
| HTTP-level failure (TLS/DNS/5xx) | curl error / non-200 | `CarrierUnavailable` without crashing |

Rate limiting: 30 rapid sequential requests all returned 200 with `isIpLocked=false` — none
observed at this volume (single IP, short window). The flag `Result.isIpLocked` (and
`DataActionCheckIPLocked` on the detail screen) exists, so re-test under sustained load before
assuming none. Re-test with a real `Token` too if volume grows.

## Language parameter and ETA

- Event texts (`State`, `Event`, `ErrorMessage`) came back in **PT** for `OutSystems-locale`
  `pt-PT`/`en-US`/`es-ES` and matching cookie — the API returns PT strings; the site localizes in
  the UI (wrapper `?request_locale=` / site language). Plan: map the PT `State`/`EventCode`
  pairs above, or localize client-side.
- No explicit final ETA field on delivered objects. The object record carries
  `DeliveryTimeSlot`, `DeliveryDeadlineEnd`, `DeliveryDeadlineDayAndMonthForStories`
  (empty/default on the delivered sample; populated for scheduled deliveries) — treat
  `DeliveryDeadlineEnd`/`DeliveryTimeSlot` as the ETA-ish fields.

## Tracking number formats (CTT help pages + observed)

- **S10 alphanumeric**: 2-letter prefix (shipping product) + 9 digits (8 digits + check digit) +
  2-letter suffix (country that created the code, usually `PT`). CTT example: `RD123456789PT`.
  Check digit (UPU S10): weights `8,6,4,2,3,5,9,7` over the 8 digits, `check = 11 - (sum mod 11)`,
  with `10 → 0`, `11 → 5`. `DD506882333PT` (sample from G-69) validates.
- **Numeric**: 22 or 25 digits (CTT help example `0010000082809458634407`).
- Prefix letters called out in CTT's own FAQ: `D…` has a separate change-delivery flow; `C, G, L,
  O, R, U, V…` another. Homepage placeholder: `RD123456789PT`.
- Not all shipments have a trackable code (some codes are customs-only and return no result).
- Suggested detection rule: `^[A-Z]{2}\d{9}[A-Z]{2}$` (S10; normalize to uppercase) or
  `^\d{22}(\d{3})?$` (numeric). Keep the `…PT` suffix check separate for origin.

## Legacy jupitern/ctt endpoint — stale as of 2026-11-23

`POST https://www.ctt.pt/feapl_2/app/open/objectSearch/objectSearch.jspx` (and the
`appserver2.ctt.pt` mirror) with `showResults=true&objects=DD506882333PT` still returns the search
**form** HTML, but no `objectSearchResult` rows — results are rendered inside the embedded
OutSystems iframe (`<iframe id="objectSearchFrame" src="https://appserver.ctt.pt/CustomerArea/PublicArea">`).
So the HTML-scraping approach of `jupitern/ctt` no longer yields results; use the screenservices
JSON endpoints above.

## Evidence / provenance

- REA `capture_browser_scenario` (Chromium 156, ms-playwright build) against
  `https://appserver2.ctt.pt/feapl_2/app/open/objectSearch/objectSearch.jspx?request_locale=pt` —
  evidence `ev_3ca41012550a0d8a9618a668cc84870593788a21e3bf295658060aa5f2705a69`: page embeds the
  `appserver.ctt.pt/CustomerArea/PublicArea` iframe.
- REA scenario against `https://appserver.ctt.pt/CustomerArea/PublicArea` — evidence
  `ev_52b9874ac1e5c311812589c86b6e58f830263bce20bee17e757d6964c6604ed4`: form textbox
  "Insira o código. Exemplo: RD123456789PT", search button disabled until reCAPTCHA; captured the
  exact screenservices POST (headers incl. `x-csrftoken`, body with `versionInfo`/`viewName`/
  `screenData`/`inputParameters`) and the `DataActionGetLinkHelpPage` response revealing the
  reCAPTCHA site key.
- REA scenario against `.../CustomerArea/PublicArea_Detail?ObjectCodeInput=DD506882333PT` —
  evidence `ev_86b3be2b4c1f09041975e870ad778fb1183cda50c79466b893620469fcce6e8b`.
- Static analysis of the served OutSystems bundles (`OutSystems.js` — anonymous CSRF token
  `T6C+9iB49TLra4jEsMeSckDMNhQ=`, header `X-CSRFToken`, cookie `nr2Users` with `crf=`;
  `DF_TrackTrace_CW.TrackTrace.TT_SearchObjectPublicArea_Mobile.mvc.js` — search action wiring
  `Actionnc892c0qmdsyqlc87(objects, true, false, token)`; `CustomerArea.CustomerArea.PublicArea_Detail.mvc.js`
  — `DataActionGetObjectEventsByInputObjectCode`); module screen list from
  `moduleservices/moduleinfo`.
- `jupitern/ctt` on GitHub (`src/CttTracking.php`) for the legacy request shape.
- All `fixtures/ctt-*.json` captured live on 2026-11-23 via `ctt_probe.py`; personal data redacted.
