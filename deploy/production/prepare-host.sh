#!/usr/bin/env bash
# Local template only. It performs no action unless explicitly armed on the future production host.
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_PREPARE:-}" != "YES" ]]; then
  echo "Refusing to modify a host. Review first, then set APPLY_PRODUCTION_PREPARE=YES explicitly."
  exit 2
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if grep -q '__FRONT_ROOT_A_COMPLETER__' "$root_dir/nginx-trading.saamp.com.conf"; then
  echo "Refusing host preparation: set the reviewed front root in nginx-trading.saamp.com.conf first."
  exit 2
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/trading-api/$stamp-host"
umask 077
install -d -m 0700 "$backup_dir"
backup_files=()
for file in /etc/systemd/system/trading-api.service /etc/nginx/sites-available/trading.saamp.com.conf; do
  if [[ -f "$file" ]]; then
    install -m 0600 "$file" "$backup_dir/$(basename "$file")"
    backup_files+=("$(basename "$file")")
  fi
done
if [[ -L /etc/nginx/sites-enabled/trading.saamp.com.conf ]]; then
  readlink /etc/nginx/sites-enabled/trading.saamp.com.conf > "$backup_dir/sites-enabled.target"
  chmod 0600 "$backup_dir/sites-enabled.target"
  backup_files+=(sites-enabled.target)
fi
if (( ${#backup_files[@]} > 0 )); then
  ( cd "$backup_dir" && sha256sum "${backup_files[@]}" > SHA256SUMS )
fi

if ! getent group trading >/dev/null; then
  groupadd --system trading
fi
if ! id -u trading >/dev/null 2>&1; then
  useradd --system --gid trading --home-dir /opt/trading-api --shell /usr/sbin/nologin trading
fi

install -d -o trading -g trading -m 0750 /opt/trading-api
install -d -o root -g root -m 0750 /etc/trading-api
install -d -o trading -g trading -m 0750 /var/backups/trading-api
install -d -o root -g root -m 0755 /var/www/certbot

install -o root -g root -m 0644 "$root_dir/trading-api.service" /etc/systemd/system/trading-api.service
install -o root -g root -m 0644 "$root_dir/nginx-trading.saamp.com.conf" /etc/nginx/sites-available/trading.saamp.com.conf
ln -sfn /etc/nginx/sites-available/trading.saamp.com.conf /etc/nginx/sites-enabled/trading.saamp.com.conf

if [[ -f /etc/trading-api/trading-prod.env ]]; then
  chown root:root /etc/trading-api/trading-prod.env
  chmod 0600 /etc/trading-api/trading-prod.env
else
  echo "Missing /etc/trading-api/trading-prod.env; install a reviewed secret-backed file before service start."
fi

systemctl daemon-reload
echo "Host structure prepared. Do not enable/start trading-api or reload nginx until the separate deployment approval."
