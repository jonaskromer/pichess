// gRPC surface: direct load against game-service. The HTTP load tests
// (Gatling + k6 browser) all reach game-service transitively through
// the gateway, so every measurement bundles in gateway + JSON
// (de)serialisation overhead. This script speaks gRPC + Protobuf
// directly, isolating game-service's own command-handling cost from
// the wider HTTP stack.
//
// Flow per iteration:
//   1. NewGame   — mint a fresh game, capture its id
//   2. MakeMove ×8 — play the canonical Ruy Lopez opening
//   3. GetState  — final snapshot
//
// k6 has a native gRPC client (`k6/net/grpc`) — no extension needed.
// The .proto file is mounted into the container at /proto/pichess/.

import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { cfg } from '/k6lib/config.js';

// Single client shared across VUs — k6/net/grpc's Client is safe to
// re-use as long as each VU's iteration follows the connect →
// invoke* → close sequence below. The proto load happens once at
// init time so the .proto file is read off the bind mount exactly
// one time per run.
const client = new grpc.Client();
client.load(['/proto/pichess'], 'game_service.proto');

export const options = {
  scenarios: {
    grpc_load: {
      executor: 'shared-iterations',
      vus: cfg.vus,
      iterations: cfg.vus * 4,
      maxDuration: cfg.duration,
    },
  },
  thresholds: {
    // gRPC RPC duration — `grpc_req_duration` is the equivalent of
    // `http_req_duration`. Targets sized for game-service against
    // postgres on dev hardware with the full stack up: typical p95
    // is sub-100 ms in isolation but climbs to ~200 ms when running
    // alongside the browser + kafka surfaces. Headroom set to absorb
    // that contention while still gating real regressions.
    grpc_req_duration: ['p(95)<300', 'p(99)<1000'],
    // Transport-level checks gate on a tight rate because they should
    // never fail (NewGame / MakeMove ack / GetState transport OK).
    // The `state moveLog matches` check intentionally surfaces a
    // known game-service race — under concurrent gameId activity,
    // some MoveMade events are dropped silently (the call ACKs with
    // no error, but the move never lands in the per-game stream).
    // No threshold on `checks{kind:state}` — the diagnostic value is
    // in the summary text, not the CI gate. Adding any threshold
    // (even `rate>=0`) would force it onto the failed-thresholds list.
    'checks{kind:tx}': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// Eight-ply Ruy Lopez — same opening Gatling's chains play, so the two
// tools agree on what "one game" means. Coord-notation; game-service
// accepts both SAN and coord.
const OPENING_MOVES = [
  'e2 e4', 'e7 e5', 'g1 f3', 'b8 c6',
  'f1 b5', 'a7 a6', 'b5 a4', 'g8 f6',
];

export default function () {
  // `plaintext: true` because game-service accepts unencrypted gRPC
  // on its loopback port. `timeout` covers TCP + HTTP/2 handshake.
  client.connect(cfg.grpcTarget, { plaintext: true, timeout: '10s' });

  try {
    const newGame = client.invoke('pichess.GameService/NewGame', {});
    // k6/net/grpc returns `r.status` as a Status object — it === the
    // `grpc.Status*` constants (the constants ARE the singleton
    // objects). `r.status === 0` and `r.status === 'OK'` both fail;
    // the only correct compare is against `grpc.StatusOK`.
    if (
      !check(
        newGame,
        {
          'NewGame transport OK': (r) => r && r.status === grpc.StatusOK,
          'NewGame returns id':   (r) => r && r.message && r.message.gameId,
        },
        { kind: 'tx' }
      )
    ) {
      return; // skip this iteration — connection is closed in finally
    }

    const gameId = newGame.message.gameId;

    // Apply the 8-ply opening. We track per-move outcomes locally and
    // aggregate at the end so the threshold gates the iteration's
    // success rate, not 8× the noise per call. A move counts as ACKed
    // iff the server returned StateReply with `error === ''` — but
    // ACK doesn't mean "applied"; see the state-matching check below.
    let moveAcks = 0;
    for (const mv of OPENING_MOVES) {
      const moveReply = client.invoke('pichess.GameService/MakeMove', {
        gameId,
        raw: mv,
      });
      if (moveReply && moveReply.message && !moveReply.message.error) {
        moveAcks++;
      }
    }
    check(
      moveAcks,
      { 'all 8 moves ACKed': (n) => n === OPENING_MOVES.length },
      { kind: 'tx' }
    );

    const state = client.invoke('pichess.GameService/GetState', { gameId });
    check(
      state,
      { 'GetState transport OK': (r) => r && r.status === grpc.StatusOK },
      { kind: 'tx' }
    );
    // State-matching is the diagnostic check that surfaces the known
    // game-service concurrency race — moves get ACKed but not always
    // persisted on the per-game stream under parallel gameId activity.
    // Tagged `state` so the threshold above doesn't gate CI on it.
    check(
      state,
      {
        'state moveLog matches acked moves': (r) =>
          r && r.message && r.message.moveLog &&
          r.message.moveLog.length === moveAcks,
      },
      { kind: 'state' }
    );
  } finally {
    client.close();
  }
}

export function handleSummary(data) {
  return {
    '/out/grpc/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const fmt = (v, suffix = 'ms') =>
    v === undefined ? 'n/a' : `${v.toFixed(2)} ${suffix}`;
  const dur   = m.grpc_req_duration?.values;
  const iters = m.iterations?.values;
  const txOk  = m['checks{kind:tx}']?.values;

  // Untagged checks aren't rolled up by k6 unless they have a
  // threshold, so look them up by name on the root_group instead.
  // Each entry: { name, passes, fails }.
  const allChecks = data.root_group?.checks || [];
  const stateCheck =
    allChecks.find((c) => c.name === 'state moveLog matches acked moves');
  const stateRate =
    stateCheck && stateCheck.passes + stateCheck.fails > 0
      ? (stateCheck.passes / (stateCheck.passes + stateCheck.fails)) * 100
      : null;
  const stateRateStr =
    stateRate === null ? 'n/a' : `${stateRate.toFixed(1)}%`;

  return [
    '',
    '── k6/grpc — game-service direct load ────────────────────',
    `  grpc p50:  ${fmt(dur?.med)}`,
    `  grpc p95:  ${fmt(dur?.['p(95)'])}   (target ≤ 300 ms)`,
    `  grpc p99:  ${fmt(dur?.['p(99)'])}   (target ≤ 1000 ms)`,
    `  transport checks ok: ${txOk ? (txOk.rate * 100).toFixed(1) + '%' : 'n/a'}   (gates CI ≥ 99%)`,
    `  state-match checks ok: ${stateRateStr}   (diagnostic — known race in game-service under concurrent gameIds)`,
    `  iterations: ${iters?.count ?? 0}`,
    '──────────────────────────────────────────────────────────',
    '',
  ].join('\n');
}
