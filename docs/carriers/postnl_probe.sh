#!/usr/bin/env sh
# Reproducible PostNL consumer track & trace lookup (researched in G-70).
# Usage: ./postnl_probe.sh <barcode> <country> <postcode> [language]
#   barcode  e.g. 3SXXXX00000000 (3S...) or an S10 ending NL (RR123456789NL)
#   country  ISO-2 delivery country, e.g. NL, PT, BE, LU
#   postcode delivery postal code in the country's local format, e.g. 1234AB (NL), 4250-004 (PT)
#   language en|nl|fr|de (default en); the param is effectively required
set -eu

BARCODE=${1:?usage: postnl_probe.sh <barcode> <country> <postcode> [language]}
COUNTRY=${2:?usage: postnl_probe.sh <barcode> <country> <postcode> [language]}
POSTCODE=${3:?usage: postnl_probe.sh <barcode> <country> <postcode> [language]}
LANG=${4:-en}

case "$LANG" in
  en|nl|fr|de) ;;
  *) echo "language must be en, nl, fr or de" >&2; exit 2 ;;
esac

KEY=$(printf '%s-%s-%s' "$BARCODE" "$COUNTRY" "$POSTCODE" | tr -d ' ' | tr '[:lower:]' '[:upper:]')

curl -sS -G "https://tracking.postnl.nl/track-and-trace/api/trackAndTrace/$KEY" \
  --data-urlencode "language=$LANG" \
  -H 'Accept: application/json' \
  -w '\nHTTP %{http_code}\n'
