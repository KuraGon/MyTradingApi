#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_PREREQUISITES:-}" != "YES" ]]; then
  echo "Refusing package installation without APPLY_PRODUCTION_PREREQUISITES=YES."
  exit 2
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-21-jre-headless postgresql postgresql-client nginx certbot python3-certbot-nginx curl
java -version
psql --version
nginx -v
echo "Prerequisites installed. TLS issuance remains a separate approved operation."