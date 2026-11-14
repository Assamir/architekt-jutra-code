#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# --- Shared OAuth wiring (host + MCP server must agree; existing values win) ---
: "${APP_OAUTH2_ISSUER:=http://localhost:8180}"
: "${AJ_OAUTH_SERVER_URL:=$APP_OAUTH2_ISSUER}"
: "${AJ_BACKEND_URL:=http://localhost:8180}"
: "${AJ_MCP_BASE_URL:=http://localhost:8181}"
: "${AJ_OAUTH_CLIENT_ID:=aj-mcp-server}"
# The client secret is reused from the gitignored .aj-dev.env, so either app can be restarted on its own.
DEV_ENV_FILE=.aj-dev.env
if [ -z "${AJ_OAUTH_CLIENT_SECRET:-}" ] && [ -f "$DEV_ENV_FILE" ]; then
  AJ_OAUTH_CLIENT_SECRET=$(sed -n 's/^AJ_OAUTH_CLIENT_SECRET=//p' "$DEV_ENV_FILE" | tail -n 1 | tr -d '\r')
fi
if [ -z "${AJ_OAUTH_CLIENT_SECRET:-}" ]; then
  AJ_OAUTH_CLIENT_SECRET=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
  (umask 077 && printf 'AJ_OAUTH_CLIENT_SECRET=%s\n' "$AJ_OAUTH_CLIENT_SECRET" >> "$DEV_ENV_FILE")
  echo "Generated AJ_OAUTH_CLIENT_SECRET and stored it in $DEV_ENV_FILE (gitignored)."
fi
# AJ_OAUTH_CLIENT_SECRET is not exported: only the host and MCP processes receive it (see below).
export APP_OAUTH2_ISSUER AJ_OAUTH_SERVER_URL AJ_BACKEND_URL AJ_MCP_BASE_URL AJ_OAUTH_CLIENT_ID
# APP_JWT_SECRET is deliberately not generated: it is inherited when set, otherwise the host's committed fallback applies.
echo "OAuth issuer: $APP_OAUTH2_ISSUER"
echo "MCP base URL: $AJ_MCP_BASE_URL"

# --- Docker Compose ---
echo "Ensuring docker-compose services are up..."
if docker compose ps --status running 2>/dev/null | grep -q postgres; then
  echo "  Services already running."
else
  docker compose up -d --wait
  echo "  Postgres, LiteLLM, and Langfuse started."
fi

# --- Cleanup on exit ---
cleanup() {
  echo ""
  echo "Shutting down..."
  kill 0 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT INT TERM

# --- Host app (Spring Boot) ---
echo "Starting host app..."
AJ_OAUTH_CLIENT_SECRET="$AJ_OAUTH_CLIENT_SECRET" ./mvnw -q spring-boot:run &

# --- MCP app (Spring Boot) ---
echo "Starting MCP app..."
(cd mcp-server && AJ_OAUTH_CLIENT_SECRET="$AJ_OAUTH_CLIENT_SECRET" ./mvnw -q spring-boot:run) &

# Plugins never see the shared client secret, not even when it was inherited from the caller's environment.
unset AJ_OAUTH_CLIENT_SECRET

# --- Plugins ---
for dir in plugins/*/; do
  if [ -f "$dir/package.json" ]; then
    name=$(basename "$dir")
    echo "Starting plugin: $name"
    (cd "$dir" && npm run dev) &
  fi
done

echo ""
echo "All services starting. Press Ctrl+C to stop everything."
wait
