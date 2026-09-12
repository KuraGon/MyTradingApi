#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_POSTGRES:-}" != "YES" ]]; then
  echo "Refusing PostgreSQL creation without APPLY_PRODUCTION_POSTGRES=YES."
  exit 2
fi
: "${TRADING_DB_PASSWORD:?Provide the production database password through the protected runtime environment}"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if sudo -u postgres psql -Atqc "SELECT 1 FROM pg_database WHERE datname='trading'" | grep -qx 1; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_dir="/var/backups/trading-api/$stamp-postgres"
  umask 077
  install -d -m 0700 "$backup_dir"
  sudo -u postgres pg_dump -Fc trading > "$backup_dir/trading.dump"
  chmod 0600 "$backup_dir/trading.dump"
  ( cd "$backup_dir" && sha256sum trading.dump > SHA256SUMS )
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 -v trading_db_password="$TRADING_DB_PASSWORD" -f "$root_dir/postgresql-bootstrap.sql.template"
sudo -u postgres psql -d trading -Atqc 'SELECT current_database()'
echo "Dedicated PostgreSQL database verified. No UAT data was imported."
