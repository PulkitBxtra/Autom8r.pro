#!/usr/bin/env bash
# Builds the images and (re)starts the stack on this machine; run from anywhere in the checkout
# after a `git pull`. Images are built one at a time: parallel Maven builds get their downloads
# refused by Maven Central. Extra arguments go to `docker compose up`, e.g. --profile frontend.
#   ./deploy/deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "deploy/.env is missing: copy .env.example and fill it in" >&2
  exit 1
fi
chmod 600 .env

compose=(docker compose -f docker-compose.yml --env-file .env)
for service in pod-backend pod-webhooks pod-workflow pod-processor pod-connector pod-sandbox; do
  echo "== Building $service"
  "${compose[@]}" build "$service"
done

echo "== Starting"
"${compose[@]}" "$@" up -d --remove-orphans
"${compose[@]}" ps
