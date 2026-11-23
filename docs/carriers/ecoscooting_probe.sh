#!/usr/bin/env sh
# Reproducible EcoScooting/Cainiao consumer-tracking lookup (researched in G-73).
# Usage: ./ecoscooting_probe.sh <trackingNumber> [locale]   (locale: es_ES|en_US|pt_PT, default es_ES)
set -eu

TN=${1:?usage: ecoscooting_probe.sh <trackingNumber> [locale]}
LOCALE=${2:-es_ES}

case "$LOCALE" in
  es_ES|en_US|pt_PT) ;;
  *) echo "locale must be es_ES, en_US or pt_PT" >&2; exit 2 ;;
esac

LI=$(python3 -c 'import json,sys; print(json.dumps({"mailNo": sys.argv[1], "locale": sys.argv[2], "role": "endUser"}))' "$TN" "$LOCALE")

curl -sS -X POST "https://de-link.cainiao.com/gateway/link.do" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "logistics_interface=$LI" \
  --data-urlencode "msg_type=CN_OVERSEA_LOGISTICS_INQUIRY_TRACKING" \
  --data-urlencode "logistic_provider_id=DISTRIBUTOR_30250031" \
  --data-urlencode "data_digest=suibianxie" \
  --data-urlencode "to_code=CNL_EU" \
  -w '\nHTTP %{http_code}\n'
