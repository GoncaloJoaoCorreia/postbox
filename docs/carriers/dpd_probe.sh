#!/usr/bin/env sh
# Reproducible DPD lookup probes (researched in G-71).
# Usage: dpd_probe.sh pt <reference>            -> HTML page from tracking.dpd.pt
#        dpd_probe.sh nl <14-digit-number> [postcode]
#              -> myDPD guest JSON API; needs the public app-restricted Firebase key and
#                 Android cert SHA-1 from the myDPD app bundle in FIREBASE_API_KEY /
#                 FIREBASE_ANDROID_CERT (never stored in this repo)
set -eu

MODE=${1:?usage: dpd_probe.sh pt <reference> | nl <14-digit-number> [postcode]}
shift

UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

probe_pt() {
  REF=${1:?reference required}
  case "$REF" in
    *[!0-9A-Za-z]*) echo "reference must be alphanumeric" >&2; exit 2 ;;
  esac
  curl -sS -G "https://tracking.dpd.pt/pt" \
    --data-urlencode "reference=$REF" \
    -H "Accept: text/html" -A "$UA" \
    -w '\nHTTP %{http_code}\n'
}

probe_nl() {
  NR=${1:?14-digit parcel number required}
  case "$NR" in
    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
    *) echo "parcel number must be exactly 14 digits" >&2; exit 2 ;;
  esac
  POSTCODE=${2:-}
  : "${FIREBASE_API_KEY:?set FIREBASE_API_KEY to the public app-restricted Firebase key from the myDPD app bundle}"
  : "${FIREBASE_ANDROID_CERT:?set FIREBASE_ANDROID_CERT to the myDPD app SHA-1 from the app bundle}"

  APP_ID="1:959401347543:android:8d1a84133332291109e392"
  PKG="com.dpdgroup.chatbot.lemny.prod"
  VERSION="3.79.14"

  FID=$(python3 -c 'import os,base64; b=bytearray(os.urandom(17)); b[0]=0x70|(b[0]&0x0f); print(base64.urlsafe_b64encode(bytes(b)).decode().rstrip("=")[:22])')
  INSTALL=$(curl -sS -X POST "https://firebaseinstallations.googleapis.com/v1/projects/consignee-portal/installations" \
    -H "Content-Type: application/json" -H "X-Goog-Api-Key: $FIREBASE_API_KEY" \
    -H "X-Android-Package: $PKG" -H "X-Android-Cert: $FIREBASE_ANDROID_CERT" \
    --data "{\"fid\":\"$FID\",\"appId\":\"$APP_ID\",\"authVersion\":\"FIS_v2\",\"sdkVersion\":\"a:18.0.0\"}")
  INSTALL_TOKEN=$(printf '%s' "$INSTALL" | python3 -c 'import sys,json; print(json.load(sys.stdin)["authToken"]["token"])')

  RC=$(curl -sS -X POST "https://firebaseremoteconfig.googleapis.com/v1/projects/959401347543/namespaces/firebase:fetch" \
    -H "Content-Type: application/json" -H "X-Goog-Api-Key: $FIREBASE_API_KEY" \
    -H "X-Android-Package: $PKG" -H "X-Android-Cert: $FIREBASE_ANDROID_CERT" \
    -H "X-Goog-Firebase-Installations-Auth: $INSTALL_TOKEN" \
    --data "{\"appId\":\"$APP_ID\",\"appInstanceId\":\"$FID\",\"appInstanceIdToken\":\"$INSTALL_TOKEN\",\"languageCode\":\"en-US\",\"countryCode\":\"NL\",\"platformVersion\":\"36\",\"appVersion\":\"$VERSION\",\"packageName\":\"$PKG\",\"sdkVersion\":\"22.1.2\",\"analyticsUserProperties\":{}}")
  BASIC=$(printf '%s' "$RC" | python3 -c 'import sys,json; print(json.load(sys.stdin)["entries"]["basic_dpd_token"])')

  OAUTH=$(curl -sS -X POST "https://www.dpdgroup.com/concept/webservice/oauth/token?grant_type=client_credentials" \
    -H "Authorization: Basic $BASIC" -H "Accept: application/json" -H "Content-Type: application/json" \
    -A "myDPD/$VERSION (Android)" --data '')
  TOKEN=$(printf '%s' "$OAUTH" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

  URL="https://www.dpdgroup.com/concept/webservice/v10/parcels/details/$NR?parcelType=INCOMING&businessUnit=DPD-NL&lang=en"
  if [ -n "$POSTCODE" ]; then
    URL="$URL&continueWithoutVerification=false&dataForVerification=$POSTCODE"
  else
    URL="$URL&continueWithoutVerification=true"
  fi
  curl -sS -X POST "$URL" \
    -H "Authorization: Bearer $TOKEN" -H "Accept: application/json" -H "Content-Type: application/json" \
    -A "myDPD/$VERSION (Android)" --data '' \
    -w '\nHTTP %{http_code}\n'
}

case "$MODE" in
  pt) probe_pt "$@" ;;
  nl) probe_nl "$@" ;;
  *) echo "mode must be pt or nl" >&2; exit 2 ;;
esac
