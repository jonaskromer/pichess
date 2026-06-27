#!/usr/bin/env bash
# Seed a real NowChess tournament (../tournament-server) with a started
# tournament + round-1 games, so the gateway's tournament-spectate path has live
# games to mirror under load. Writes the tournament id + game ids to an env file
# the perf orchestration sources to parameterise TournamentSpectateSimulation.
#
# Flow (all via the tournament server's public API):
#   1. register a director (user) + N bots
#   2. director creates a tournament; bots join; director starts it
#      → round-1 pairings + games are created
#   3. read the round-1 gameIds
#   4. (best-effort) play a short opening into each game so snapshots carry moves
#      and the follower's makeMove-replay path is exercised — failures are
#      non-fatal (the games already exist, which is enough for the load test)
#
# Usage:
#   TOURNAMENT_URL=http://localhost:8086 SEED_BOTS=4 scripts/tournament-seed.sh
# Output (stdout + $SEED_OUT, default /tmp/pichess-tournament-seed.env):
#   PICHESS_TOURNAMENT_ID=<id>
#   PICHESS_SPECTATE_GAME_IDS=<g1,g2,...>
set -euo pipefail

TS_URL="${TOURNAMENT_URL:-http://localhost:8086}"
NBOTS="${SEED_BOTS:-4}"
SEED_MOVES="${SEED_MOVES:-1}"
OUT="${SEED_OUT:-/tmp/pichess-tournament-seed.env}"
STAMP="$$-$RANDOM"

echo "seeding tournament on $TS_URL ($NBOTS bots)…" >&2

register() { # name isBot -> JSON {id, token}
  curl -fsS -X POST "$TS_URL/api/auth/register" \
    -H 'content-type: application/json' \
    -d "{\"name\":\"$1\",\"isBot\":$2}"
}

dir_token=$(register "perf-dir-$STAMP" false | jq -r .token)

declare -a BOT_TOKEN BOT_ID
for i in $(seq 1 "$NBOTS"); do
  resp=$(register "perf-bot-$i-$STAMP" true)
  BOT_TOKEN[$i]=$(echo "$resp" | jq -r .token)
  BOT_ID[$i]=$(echo "$resp" | jq -r .id)
done

tid=$(curl -fsS -X POST "$TS_URL/api/tournament" \
  -H "Authorization: Bearer $dir_token" \
  --data-urlencode "name=perf-$STAMP" \
  --data-urlencode "nbRounds=1" \
  --data-urlencode "clockLimit=300" \
  --data-urlencode "clockIncrement=3" | jq -r .id)

for i in $(seq 1 "$NBOTS"); do
  curl -fsS -X POST "$TS_URL/api/tournament/$tid/join" \
    -H "Authorization: Bearer ${BOT_TOKEN[$i]}" >/dev/null
done

curl -fsS -X POST "$TS_URL/api/tournament/$tid/start" \
  -H "Authorization: Bearer $dir_token" >/dev/null

pairings=$(curl -fsS "$TS_URL/api/tournament/$tid/round/1")
game_ids=$(echo "$pairings" | jq -r '.pairings[].gameId' | paste -sd, -)

# token for a given bot id (maps pairing.white.id / .black.id back to its token)
token_for() {
  local botId="$1"
  for i in $(seq 1 "$NBOTS"); do
    [ "${BOT_ID[$i]}" = "$botId" ] && { echo "${BOT_TOKEN[$i]}"; return; }
  done
}

if [ "$SEED_MOVES" = "1" ]; then
  # Ruy Lopez, UCI; white plays even plies, black odd. Best-effort: a rejected
  # move (illegal in this server's rules, wrong turn) just stops that game early.
  MOVES=(e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6)
  echo "$pairings" | jq -c '.pairings[]' | while read -r p; do
    gid=$(echo "$p" | jq -r .gameId)
    wtok=$(token_for "$(echo "$p" | jq -r .white.id)")
    btok=$(token_for "$(echo "$p" | jq -r .black.id)")
    if [ -z "$wtok" ] || [ -z "$btok" ]; then continue; fi
    ply=0
    for mv in "${MOVES[@]}"; do
      if [ $((ply % 2)) -eq 0 ]; then tok="$wtok"; else tok="$btok"; fi
      code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        "$TS_URL/api/tournament/$tid/game/$gid/move/$mv" \
        -H "Authorization: Bearer $tok" || echo 000)
      [ "$code" = "200" ] || { echo "  game $gid: stopped at ply $ply (HTTP $code)" >&2; break; }
      ply=$((ply + 1))
    done
  done
fi

{
  echo "PICHESS_TOURNAMENT_ID=$tid"
  echo "PICHESS_SPECTATE_GAME_IDS=$game_ids"
} | tee "$OUT"
echo "seed written to $OUT" >&2
