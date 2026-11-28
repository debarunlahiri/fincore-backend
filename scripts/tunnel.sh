#!/usr/bin/env bash
# Publishes the api-gateway (localhost:9780) to the internet through a Cloudflare Tunnel, so a web app hosted on
# Vercel can reach the backend running on this Mac. No router or firewall changes are needed: the Mac only makes an
# outbound connection to Cloudflare.
#
#   scripts/tunnel.sh              start the tunnel (quick mode, or your named tunnel if .env.tunnel has a token)
#   scripts/tunnel.sh --up         start the backend with scripts/up.sh first, then the tunnel
#
# Quick mode needs no account: it prints a random https://<words>.trycloudflare.com address that changes on every
# start, so you would have to update Vercel each time. For a fixed address use a named tunnel, see
# docs/CLOUDFLARE_TUNNEL.md. Press Ctrl+C to stop; the Mac is kept awake while the tunnel runs.
set -euo pipefail
cd "$(dirname "$0")/.."

GATEWAY="http://localhost:9780"
UP=false
for a in "$@"; do [ "$a" = "--up" ] && UP=true; done

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared is not installed. Install it with:  brew install cloudflared" >&2
  exit 1
fi

if $UP; then
  scripts/up.sh
fi

# the gateway answers 401/404 on unknown paths, any HTTP status means it is up
if ! curl -s -o /dev/null --max-time 3 "$GATEWAY/"; then
  echo "The gateway is not answering on $GATEWAY. Start the backend first:  scripts/up.sh   (or run this with --up)" >&2
  exit 1
fi

TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-}"
PUBLIC_URL="${TUNNEL_PUBLIC_URL:-}"
if [ -f .env.tunnel ]; then
  # shellcheck disable=SC1091
  set -a; source .env.tunnel; set +a
  TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-$TOKEN}"
  PUBLIC_URL="${TUNNEL_PUBLIC_URL:-$PUBLIC_URL}"
fi

# keep the Mac from sleeping while the tunnel is up (sleep would drop the connection)
KEEP_AWAKE=()
command -v caffeinate >/dev/null 2>&1 && KEEP_AWAKE=(caffeinate -i)

print_next_steps() {
  cat <<MSG

==> The backend is public at: $1

Point the Vercel web app at it:
  1. Vercel > your project > Settings > Environment Variables:  GATEWAY_URL = $1
  2. Redeploy (the value is read at build time):  Deployments > ... > Redeploy
     or:  vercel env add GATEWAY_URL production   then   vercel --prod

Anyone with that address can reach the gateway, so keep the rate limits on and never share it publicly.
Press Ctrl+C to stop the tunnel.
MSG
}

if [ -n "$TOKEN" ]; then
  echo "==> starting your named Cloudflare tunnel"
  if [ -n "$PUBLIC_URL" ]; then print_next_steps "$PUBLIC_URL"; fi
  exec "${KEEP_AWAKE[@]+"${KEEP_AWAKE[@]}"}" cloudflared tunnel --no-autoupdate run --token "$TOKEN"
fi

echo "==> starting a quick tunnel (random address, changes on every start)"
LOG="$(mktemp -t fincore-tunnel.XXXXXX)"
"${KEEP_AWAKE[@]+"${KEEP_AWAKE[@]}"}" cloudflared tunnel --no-autoupdate --url "$GATEWAY" >"$LOG" 2>&1 &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; rm -f "$LOG"; }
trap cleanup EXIT INT TERM

URL=""
for _ in $(seq 1 40); do
  URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" | head -n 1 || true)"
  [ -n "$URL" ] && break
  kill -0 "$PID" 2>/dev/null || { echo "cloudflared stopped unexpectedly:" >&2; cat "$LOG" >&2; exit 1; }
  sleep 1
done
if [ -z "$URL" ]; then
  echo "No tunnel address appeared after 40 seconds. cloudflared output:" >&2
  cat "$LOG" >&2
  exit 1
fi
print_next_steps "$URL"
wait "$PID"
