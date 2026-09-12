#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_NGINX:-}" != "YES" ]]; then
  echo "Refusing nginx configuration without APPLY_PRODUCTION_NGINX=YES."
  exit 2
fi

root_dir="$(cd -- "${BASH_SOURCE[0]%/*}" && pwd)"
config="$root_dir/nginx-trading.saamp.com.conf"
[[ -r "$config" ]] || { echo "Nginx template missing or unreadable"; exit 2; }
if [[ "$(<"$config")" == *'__FRONT_ROOT_A_COMPLETER__'* ]]; then
  echo "Front root remains [À COMPLÉTER]."
  exit 2
fi
[[ -f /etc/letsencrypt/live/trading.saamp.com/fullchain.pem ]] || { echo "TLS certificate missing"; exit 2; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/trading-api/$stamp-nginx"
umask 077
install -d -m 0700 "$backup_dir"
backup_files=()
if [[ -f /etc/nginx/sites-available/trading.saamp.com.conf ]]; then
  install -m 0600 /etc/nginx/sites-available/trading.saamp.com.conf "$backup_dir/trading.saamp.com.conf"
  backup_files+=(trading.saamp.com.conf)
fi
if [[ -L /etc/nginx/sites-enabled/trading.saamp.com.conf ]]; then
  readlink /etc/nginx/sites-enabled/trading.saamp.com.conf > "$backup_dir/sites-enabled.target"
  chmod 0600 "$backup_dir/sites-enabled.target"
  backup_files+=(sites-enabled.target)
fi
if (( ${#backup_files[@]} > 0 )); then
  ( cd "$backup_dir" && sha256sum "${backup_files[@]}" > SHA256SUMS )
fi
install -d -o root -g root -m 0755 /var/www/certbot
install -o root -g root -m 0644 "$config" /etc/nginx/sites-available/trading.saamp.com.conf
ln -sfn /etc/nginx/sites-available/trading.saamp.com.conf /etc/nginx/sites-enabled/trading.saamp.com.conf
nginx -t
systemctl reload nginx
echo "Nginx configuration applied."
