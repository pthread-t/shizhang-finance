#!/bin/sh
set -eu
set -o pipefail
umask 077

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${BACKUP_PASSWORD:?BACKUP_PASSWORD is required}"

interval="${BACKUP_INTERVAL_SECONDS:-86400}"
mkdir -p /backups

while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  database_final="/backups/postgres-${stamp}.dump.enc"
  attachments_final="/backups/attachments-${stamp}.tar.gz.enc"
  database_partial="${database_final}.partial"
  attachments_partial="${attachments_final}.partial"
  manifest_final="/backups/backup-${stamp}.sha256"
  manifest_partial="${manifest_final}.partial"
  trap 'rm -f "$database_partial"; rm -f "$attachments_partial"; rm -f "$manifest_partial"' INT TERM EXIT
  pg_dump --host "$POSTGRES_HOST" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom \
    | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD \
      -out "$database_partial"
  tar -C /attachments -czf - . \
    | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD \
      -out "$attachments_partial"
  mv "$database_partial" "$database_final"
  mv "$attachments_partial" "$attachments_final"
  sha256sum "$database_final" "$attachments_final" > "$manifest_partial"
  mv "$manifest_partial" "$manifest_final"
  trap - INT TERM EXIT
  printf '%s backup completed\n' "$stamp"
  if [ "${BACKUP_ONCE:-false}" = "true" ]; then
    exit 0
  fi
  sleep "$interval"
done
