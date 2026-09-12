#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_POSTFLIGHT:-}" != "YES" ]]; then
  echo "Refusing postflight without APPLY_PRODUCTION_POSTFLIGHT=YES."
  exit 2
fi

systemctl is-active --quiet trading-api.service || { echo "trading-api is not active"; exit 2; }
curl --fail --silent --show-error http://127.0.0.1:8083/trading-api/actuator/health >/dev/null
sudo -u postgres psql -d trading -Atqc 'SELECT open FROM trading_execution_gate WHERE id=1' | grep -qx false || {
  echo "Execution gate must remain closed after production startup"
  exit 2
}
echo "Postflight passed with execution gate closed."