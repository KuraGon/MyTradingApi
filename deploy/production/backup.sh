#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_BACKUP:-}" != "YES" ]]; then
  echo "Refusing backup without APPLY_PRODUCTION_BACKUP=YES."
  exit 2
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/trading-api/$stamp"
umask 077
install -d -m 0700 "$backup_dir"
[[ ! -e "$backup_dir/trading-api.war" ]] || { echo "Backup collision"; exit 2; }
backup_files=()
if [[ -f /opt/trading-api/trading-api.war ]]; then
  install -m 0600 /opt/trading-api/trading-api.war "$backup_dir/trading-api.war"
  backup_files+=(trading-api.war)
fi
if [[ -f /etc/trading-api/trading-prod.env ]]; then
  install -m 0600 /etc/trading-api/trading-prod.env "$backup_dir/trading-prod.env"
  backup_files+=(trading-prod.env)
fi
if sudo -u postgres psql -Atqc "SELECT 1 FROM pg_database WHERE datname='trading'" | grep -qx 1; then
  sudo -u postgres pg_dump -Fc trading > "$backup_dir/trading.dump"
  chmod 0600 "$backup_dir/trading.dump"
  backup_files+=(trading.dump)
fi
(( ${#backup_files[@]} > 0 )) || { echo "Nothing to back up"; exit 2; }
( cd "$backup_dir" && sha256sum "${backup_files[@]}" > SHA256SUMS )
printf '%s\n' "$backup_dir"
