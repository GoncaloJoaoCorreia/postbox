#!/usr/bin/env python3
"""Probe script for the CTT (Correios de Portugal) public object-tracking API.

Reverse-engineered on 2026-11-23 (Linear G-69). See ctt.md for the full write-up.

The public "Seguir objeto" page on ctt.pt is a wrapper (appserver2.ctt.pt
.../objectSearch/objectSearch.jspx) that embeds an OutSystems app in an iframe:
    https://appserver.ctt.pt/CustomerArea/PublicArea
The app calls OutSystems "screenservices" endpoints as JSON POSTs.

Two endpoints matter:
  1. search  (last event per object, up to 25 codes per request):
     POST /CustomerArea/screenservices/DF_TrackTrace_CW/TrackTrace/TT_SearchObjectPublicArea_Mobile/Actionnc892c0qmdsyqlc87
     inputParameters: {"ObjectNumbers": "DD506882333PT,LX123456789PT",
                       "IsFromSearch": true, "IsObjectDetail": false, "Token": ""}
  2. detail  (full event timeline for one object):
     POST /CustomerArea/screenservices/CustomerArea/CustomerArea/PublicArea_Detail/DataActionGetObjectEventsByInputObjectCode
     screenData.variables.ObjectCodeInput = "DD506882333PT"

Both need: X-CSRFToken header (the static anonymous value below works without any
session), Content-Type: application/json, and a payload of
{versionInfo, viewName, screenData, inputParameters} as built below.

Usage:
  python3 ctt_probe.py search DD506882333PT [more codes...]
  python3 ctt_probe.py detail DD506882333PT
  python3 ctt_probe.py fixtures        # re-capture the redacted fixtures/*.json
"""
import json
import sys
import urllib.request

BASE = "https://appserver.ctt.pt/CustomerArea"
SEARCH_URL = BASE + "/screenservices/DF_TrackTrace_CW/TrackTrace/TT_SearchObjectPublicArea_Mobile/Actionnc892c0qmdsyqlc87"
SEARCH_API_VERSION = "xMqLBWshH4NPqyhLlM+eaA"          # per-action apiVersion
DETAIL_URL = BASE + "/screenservices/CustomerArea/CustomerArea/PublicArea_Detail/DataActionGetObjectEventsByInputObjectCode"
DETAIL_API_VERSION = "o0WgZ3QtqpbJXXi+QDtVYA"
CSRF_ANON = "T6C+9iB49TLra4jEsMeSckDMNhQ="             # OutSystems anonymous CSRF token
USER_AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36")
ANON_COOKIE = "nr2Users=crf%3dT6C%2b9iB49TLra4jEsMeSckDMNhQ%3d%3buid%3d0%3bunm%3d"

# Minimal screenData captured from the live PublicArea screen (the server
# expects the screen's variable tree; empty lists are fine).
SCREEN_DATA = {"variables": {"ObjectCodeInput": "", "ObjectsToSearch": "",
                             "ObjectsToSearchList": {"List": [], "EmptyListItem": ""},
                             "ShowList": False, "IsRunning": False,
                             "IsIPBlocked": False, "IsCaptchaValid": False,
                             "TokenCapcha": "", "DisableButton_Search": False}}


def module_version():
    req = urllib.request.Request(
        BASE + "/moduleservices/moduleversioninfo?" + str(__import__("time").time_ns() // 1000000),
        headers={"Accept": "application/json", "X-CSRFToken": CSRF_ANON, "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["versionToken"]


def post(url, api_version, view_name, screen_data, input_parameters):
    payload = {
        "versionInfo": {"moduleVersion": module_version(), "apiVersion": api_version},
        "viewName": view_name,
        "screenData": screen_data,
        "inputParameters": input_parameters,
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json; charset=UTF-8")
    req.add_header("Accept", "application/json")
    req.add_header("X-CSRFToken", CSRF_ANON)
    req.add_header("User-Agent", USER_AGENT)
    req.add_header("Origin", "https://appserver.ctt.pt")
    req.add_header("Referer", BASE + "/PublicArea")
    req.add_header("Cookie", ANON_COOKIE)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, json.load(r)


def search(object_numbers):
    sd = json.loads(json.dumps(SCREEN_DATA))
    return post(SEARCH_URL, SEARCH_API_VERSION, "CustomerArea.PublicArea", sd,
                {"ObjectNumbers": object_numbers, "IsFromSearch": True,
                 "IsObjectDetail": False, "Token": ""})


def detail(object_code):
    sd = json.loads(json.dumps(SCREEN_DATA))
    sd["variables"]["ObjectCodeInput"] = object_code
    return post(DETAIL_URL, DETAIL_API_VERSION, "CustomerArea.PublicArea_Detail", sd, {})


REDACT = {  # personal/business identifiers seen in responses
    "RecipientAddress": "[redacted]", "RecipientPostalCodeAndTown": "[redacted]",
    "ReceptorName": "[redacted]", "SenderMobile": "[redacted]", "SenderEmail": "[redacted]",
    "RecipientEmail": "[redacted]", "ContractNumber": "[redacted]", "ClientNumber": "[redacted]",
    "LockerName": "[redacted]",
}


def redact(obj):
    if isinstance(obj, dict):
        return {k: ("[redacted]" if k in REDACT else redact(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "search"
    if mode == "search":
        st, body = search(",".join(sys.argv[2:]))
        print("HTTP", st)
        print(json.dumps(redact(body), indent=2, ensure_ascii=False))
    elif mode == "detail":
        st, body = detail(sys.argv[2])
        print("HTTP", st)
        print(json.dumps(redact(body), indent=2, ensure_ascii=False))
    elif mode == "fixtures":
        import os
        out = os.path.join(os.path.dirname(__file__), "fixtures")
        os.makedirs(out, exist_ok=True)
        cases = [
            ("ctt-search-delivered-DD506882333PT.json", search("DD506882333PT")),
            ("ctt-search-unknown-valid-RR123456785PT.json", search("RR123456785PT")),
            ("ctt-search-malformed-ABC.json", search("ABC")),
            ("ctt-search-multi-delivered-plus-unknown.json", search("DD506882333PT,LX123456789PT")),
            ("ctt-search-lowercase-normalized.json", search("dd506882333pt")),
            ("ctt-search-too-many-26-codes.json",
             search(",".join("RR%08dPT" % i for i in range(26)))),
            ("ctt-detail-delivered-DD506882333PT.json", detail("DD506882333PT")),
            ("ctt-detail-unknown-RR123456785PT.json", detail("RR123456785PT")),
        ]
        for name, (st, body) in cases:
            with open(os.path.join(out, name), "w") as f:
                json.dump({"http": st, "response": redact(body)}, f, indent=2, ensure_ascii=False)
            print("wrote", name)


if __name__ == "__main__":
    main()
