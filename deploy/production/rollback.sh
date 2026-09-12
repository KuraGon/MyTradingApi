#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_ROLLBACK:-}" != "YES" ]]; then
  echo "Refusing rollback without APPLY_PRODUCTION_ROLLBACK=YES."
  exit 2
fi
: "${TRADING_ROLLBACK_BACKUP_DIR:?Set TRADING_ROLLBACK_BACKUP_DIR to a reviewed backup directory}"
[[ -f "$TRADING_ROLLBACK_BACKUP_DIR/trading-api.war" ]] || { echo "Rollback WAR missing"; exit 2; }
[[ -f "$TRADING_ROLLBACK_BACKUP_DIR/trading-prod.env" ]] || { echo "Rollback environment missing"; exit 2; }
sha256sum -c "$TRADING_ROLLBACK_BACKUP_DIR/SHA256SUMS"
systemctl stop trading-api.service
install -o trading -g trading -m 0644 "$TRADING_ROLLBACK_BACKUP_DIR/trading-api.war" /opt/trading-api/trading-api.war
install -o root -g root -m 0600 "$TRADING_ROLLBACK_BACKUP_DIR/trading-prod.env" /etc/trading-api/trading-prod.env
systemctl start trading-api.service
echo "Application rollback completed. Liquibase and PostgreSQL data were intentionally not rolled back."