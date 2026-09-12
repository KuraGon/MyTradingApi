#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_SERVICE:-}" != "YES" ]]; then
  echo "Refusing service installation without APPLY_PRODUCTION_SERVICE=YES."
  exit 2
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f /etc/trading-api/trading-prod.env ]] || { echo "Missing protected production environment"; exit 2; }
if grep -q '__FRONT_ROOT_A_COMPLETER__' "$root_dir/nginx-trading.saamp.com.conf"; then
  echo "Front root remains [À COMPLÉTER]."
  exit 2
fi
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/trading-api/$stamp-service"
umask 077
install -d -m 0700 "$backup_dir"
if [[ -f /etc/systemd/system/trading-api.service ]]; then
  install -m 0600 /etc/systemd/system/trading-api.service "$backup_dir/trading-api.service"
  ( cd "$backup_dir" && sha256sum trading-api.service > SHA256SUMS )
fi
getent group trading >/dev/null || groupadd --system trading
id -u trading >/dev/null 2>&1 || useradd --system --gid trading --home-dir /opt/trading-api --shell /usr/sbin/nologin trading
install -d -o trading -g trading -m 0750 /opt/trading-api /var/backups/trading-api
install -d -o root -g root -m 0750 /etc/trading-api
chown root:root /etc/trading-api/trading-prod.env
chmod 0600 /etc/trading-api/trading-prod.env
install -o root -g root -m 0644 "$root_dir/trading-api.service" /etc/systemd/system/trading-api.service
systemctl daemon-reload
echo "Service unit installed but not started."
