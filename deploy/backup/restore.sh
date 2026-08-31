#!/bin/sh
set -eu
set -o pipefail

usage() {
  echo "usage: restore.sh verify|database|attachments /backups/file.enc"
  exit 2
}

[ "$#" -eq 2 ] || usage
mode="$1"
backup_file="$2"
case "$backup_file" in /backups/*.enc) ;; *) echo "backup path must be an .enc file under /backups"; exit 2 ;; esac
[ -f "$backup_file" ] || { echo "backup not found: $backup_file"; exit 2; }
: "${BACKUP_PASSWORD:?BACKUP_PASSWORD is required}"

case "$mode" in
  verify)
    case "$backup_file" in
      /backups/postgres-*.dump.enc)
        openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD -in "$backup_file" \
          | pg_restore --list >/dev/null
        ;;
      /backups/attachments-*.tar.gz.enc)
        openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD -in "$backup_file" \
          | tar -tzf - >/dev/null
        ;;
      *) echo "unrecognized backup filename"; exit 2 ;;
    esac
    echo "backup decrypts and archive structure is valid"
    ;;
  database)
    [ "${CONFIRM_RESTORE:-}" = "YES" ] || { echo "set CONFIRM_RESTORE=YES; restore is intended for an empty database"; exit 3; }
    : "${POSTGRES_HOST:?POSTGRES_HOST is required}"
    : "${POSTGRES_DB:?POSTGRES_DB is required}"
    : "${POSTGRES_USER:?POSTGRES_USER is required}"
    : "${PGPASSWORD:?PGPASSWORD is required}"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD -in "$backup_file" \
      | pg_restore --host "$POSTGRES_HOST" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --exit-on-error
    ;;
  attachments)
    [ "${CONFIRM_RESTORE:-}" = "YES" ] || { echo "set CONFIRM_RESTORE=YES; existing attachment names may be replaced"; exit 3; }
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSWORD -in "$backup_file" \
      | tar -C /attachments -xzf -
    ;;
  *) usage ;;
esac
