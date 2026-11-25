#!/bin/sh
# Prepare OSRM routing data for the optional "routing" compose profile.
#
# Usage:  ./osrm/prepare.sh <geofabrik-region> [car|bicycle|foot]
#   ./osrm/prepare.sh europe/liechtenstein
#   ./osrm/prepare.sh europe/germany/bayern bicycle
#
# <geofabrik-region> is the path of an extract on https://download.geofabrik.de/
# (without the -latest.osm.pbf suffix). Profile defaults to "car"; use "car" for
# motorcycles too (there is no official motorcycle profile).
#
# Output: ./osrm-data/region.osm.pbf and ./osrm-data/region.osrm* (mounted by compose).
# Idempotent: an existing download is reused (set FORCE_DOWNLOAD=1 to refetch) and
# processing is skipped when region.osrm.mldgr already matches region + profile
# (set FORCE=1 to redo it). Switching region or profile re-processes automatically.
#
# Environment: OSRM_IMAGE overrides the image; OSRM_DATA_DIR the data directory;
# OSM_PBF_URL downloads from another mirror/extract instead of Geofabrik (the
# region argument is then just a label used for the idempotency check).
# Requires: docker, curl or wget. Map data (c) OpenStreetMap contributors, ODbL.
set -eu

REGION="${1:-}"
PROFILE="${2:-car}"
[ -n "$REGION" ] || { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
case "$REGION" in
  *[!A-Za-z0-9/_.-]*|/*|*..*) echo "Invalid region: $REGION" >&2; exit 1 ;;
esac
case "$PROFILE" in
  car|bicycle|foot) ;;
  motorcycle) PROFILE=car ;;
  *) echo "Invalid profile: $PROFILE (car|bicycle|foot)" >&2; exit 1 ;;
esac

IMAGE="${OSRM_IMAGE:-ghcr.io/project-osrm/osrm-backend:v6.0.0}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
DATA="${OSRM_DATA_DIR:-$DIR/osrm-data}"
URL="${OSM_PBF_URL:-https://download.geofabrik.de/${REGION}-latest.osm.pbf}"
STAMP="$DATA/.prepared"
WANT="$REGION $PROFILE $IMAGE"

mkdir -p "$DATA"

if [ ! -s "$DATA/region.osm.pbf" ] || [ "${FORCE_DOWNLOAD:-0}" = 1 ] || \
   [ "$(cat "$DATA/.region" 2>/dev/null || true)" != "$REGION" ]; then
  echo "Downloading $URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$DATA/region.osm.pbf.part" "$URL"
  else
    wget -O "$DATA/region.osm.pbf.part" "$URL"
  fi
  mv "$DATA/region.osm.pbf.part" "$DATA/region.osm.pbf"
  echo "$REGION" > "$DATA/.region"
  rm -f "$STAMP"
else
  echo "Reusing existing $DATA/region.osm.pbf ($REGION)"
fi

if [ -s "$DATA/region.osrm.mldgr" ] && [ "$(cat "$STAMP" 2>/dev/null || true)" = "$WANT" ] && [ "${FORCE:-0}" != 1 ]; then
  echo "Routing data already prepared for $WANT (FORCE=1 to rebuild)"
  exit 0
fi

rm -f "$STAMP"
find "$DATA" -maxdepth 1 -name 'region.osrm*' -exec rm -f {} +

run() { docker run --rm -v "$DATA:/data" "$IMAGE" "$@"; }
echo "osrm-extract (profile: $PROFILE)"
run osrm-extract -p "/opt/$PROFILE.lua" /data/region.osm.pbf
echo "osrm-partition"
run osrm-partition /data/region.osrm
echo "osrm-customize"
run osrm-customize /data/region.osrm

echo "$WANT" > "$STAMP"
echo "Done. Start routing with:  docker compose --profile routing up -d"
