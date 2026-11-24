#!/bin/sh
# Nightly pg_dump loop. Dumps go to /backups as kinetic-YYYYmmdd-HHMMSS.sql.gz; the newest BACKUP_KEEP are kept.
set -eu
INTERVAL=$(( ${BACKUP_INTERVAL_HOURS:-24} * 3600 ))
KEEP=${BACKUP_KEEP:-14}
mkdir -p /backups
while true; do
  f="/backups/kinetic-$(date +%Y%m%d-%H%M%S).sql.gz"
  if pg_dump --no-owner --clean --if-exists | gzip > "$f.tmp"; then
    mv "$f.tmp" "$f"
    echo "backup written: $f"
  else
    rm -f "$f.tmp"
    echo "backup FAILED" >&2
  fi
  ls -1t /backups/kinetic-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
  sleep "$INTERVAL"
done
