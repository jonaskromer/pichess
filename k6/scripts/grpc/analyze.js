// gRPC surface: the post-game ANALYSIS + REPLAY path, direct against
// game-service — the new, heaviest rpcs the original grpc game-loop script
// (game-service.js) doesn't touch. Isolates their cost from the gateway/HTTP
// stack and from the cheap command rpcs.
//
// Flow per iteration:
//   1. NewGame      — mint a game
//   2. MakeMove ×8  — play the canonical Ruy Lopez opening (build real history)
//   3. ReplayGame   — read-only projection of every position (cheap)
//   4. ExportGame   — serialise as PGN (the input AnalyzeGame consumes)
//   5. AnalyzeGame  — engine-rates the game: ≈2 searches/ply, single-threaded
//                     on game-service → the CPU-bound rpc this script exposes
//   6. ListActiveGames — the spectate-index source rpc
//
// AnalyzeGame dominates wall-clock (seconds at depth ≥ 6), so this runs at low
// VU / few iterations: the goal is the latency-vs-concurrency curve, not RPS.
// Tune cost with PICHESS_K6_ANALYZE_DEPTH. Run it ISOLATED (CPU contention with
// other surfaces distorts the numbers).

import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { cfg } from '/k6lib/config.js';

const client = new grpc.Client();
client.load(['/proto/pichess'], 'game_service.proto');

const ANALYZE_DEPTH = parseInt(__ENV.PICHESS_K6_ANALYZE_DEPTH || '6', 10);

export const options = {
  scenarios: {
    analyze_load: {
      executor: 'shared-iterations',
      vus: cfg.vus,
      iterations: cfg.vus * 2,
      maxDuration: cfg.duration,
    },
  },
  thresholds: {
    // Analyze is measured in seconds — loose guardrails, not an SLA. The value
    // is the trend, surfaced in the summary text.
    grpc_req_duration: ['p(95)<60000'],
    'checks{kind:tx}': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const OPENING_MOVES = [
  'e2 e4', 'e7 e5', 'g1 f3', 'b8 c6',
  'f1 b5', 'a7 a6', 'b5 a4', 'g8 f6',
];

export default function () {
  client.connect(cfg.grpcTarget, { plaintext: true, timeout: '10s' });

  try {
    const newGame = client.invoke('pichess.GameService/NewGame', {});
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
      return;
    }
    const gameId = newGame.message.gameId;

    for (const mv of OPENING_MOVES) {
      client.invoke('pichess.GameService/MakeMove', { gameId, raw: mv });
    }

    const replay = client.invoke('pichess.GameService/ReplayGame', { gameId });
    check(
      replay,
      {
        'ReplayGame transport OK': (r) => r && r.status === grpc.StatusOK,
        'ReplayGame has frames':   (r) =>
          r && r.message && r.message.frames && r.message.frames.length > 0,
      },
      { kind: 'tx' }
    );

    const exported = client.invoke('pichess.GameService/ExportGame', {
      gameId,
      format: 'pgn',
    });
    check(
      exported,
      { 'ExportGame transport OK': (r) => r && r.status === grpc.StatusOK },
      { kind: 'tx' }
    );
    const pgn =
      exported && exported.message && exported.message.body
        ? exported.message.body
        : '1. e4 e5 2. Nf3 Nc6 *';

    const analysis = client.invoke('pichess.GameService/AnalyzeGame', {
      pgn,
      depth: ANALYZE_DEPTH,
    });
    check(
      analysis,
      {
        'AnalyzeGame transport OK': (r) => r && r.status === grpc.StatusOK,
        'AnalyzeGame returns json': (r) =>
          r && r.message && r.message.analysisJson &&
          r.message.analysisJson.length > 0,
      },
      { kind: 'tx' }
    );

    const active = client.invoke('pichess.GameService/ListActiveGames', {});
    check(
      active,
      { 'ListActiveGames transport OK': (r) => r && r.status === grpc.StatusOK },
      { kind: 'tx' }
    );
  } finally {
    client.close();
  }
}

export function handleSummary(data) {
  return {
    '/out/grpc-analyze/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const fmt = (v, suffix = 'ms') =>
    v === undefined ? 'n/a' : `${v.toFixed(2)} ${suffix}`;
  const dur = m.grpc_req_duration?.values;
  const iters = m.iterations?.values;
  const txOk = m['checks{kind:tx}']?.values;
  return [
    '',
    '── k6/grpc — analyze + replay (game-service direct) ───────',
    `  rpc p50:  ${fmt(dur?.med)}`,
    `  rpc p95:  ${fmt(dur?.['p(95)'])}`,
    `  rpc p99:  ${fmt(dur?.['p(99)'])}`,
    `  rpc max:  ${fmt(dur?.max)}   (AnalyzeGame dominates; depth=${ANALYZE_DEPTH})`,
    `  transport checks ok: ${txOk ? (txOk.rate * 100).toFixed(1) + '%' : 'n/a'}`,
    `  iterations: ${iters?.count ?? 0}`,
    '──────────────────────────────────────────────────────────',
    '',
  ].join('\n');
}
