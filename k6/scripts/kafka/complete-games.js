// Kafka surface: drives COMPLETE game sequences onto `chess.game-events` so the
// terminal-event consumers actually fire — the path the raw-throughput producer
// (game-events.js, unique gameId per message) never triggers.
//
// Each iteration produces one whole game in a single batch, keyed by a unique
// gameId: GameStarted → MoveMade ×8 → Forfeited. That makes:
//   - repository `GameArchiver` accumulate 8 plies then finalize → ONE archive
//     UPSERT to the prod store (mongo/redis) — the write path under load
//   - spark sessionize the game → emit a summary on `chess.analytics`
//   - analytics-service + opening-service fold the per-event + completed-game
//     metrics
// i.e. it saturates the consumer + persistence-write side directly, without the
// gateway/engine ceiling that bounds the Gatling CompleteGameSimulation.
//
// The synthetic FENs vary only the side-to-move + fullmove fields (the archiver
// parses just those to derive each ply index — ArchiveProjection.plyIndex), so
// the 8 moves land as 8 distinct plies even though the board string is constant.

import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING } from 'k6/x/kafka';
import { cfg } from '/k6lib/config.js';

const TOPIC = 'chess.game-events';

const writer = new Writer({
  brokers: cfg.kafkaBrokers,
  topic: TOPIC,
  autoCreateTopic: true,
});
const registry = new SchemaRegistry();

export const options = {
  scenarios: {
    kafka_complete_games: {
      executor: 'shared-iterations',
      vus: cfg.vus,
      iterations: cfg.vus * 40, // each VU produces 40 complete games
      maxDuration: cfg.duration,
    },
  },
  thresholds: {
    kafka_writer_write_seconds: ['p(95)<0.2', 'p(99)<2'],
    kafka_writer_error_count: ['count<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// Board string is irrelevant to the archiver (it parses only side + fullmove);
// keep it constant and vary the FEN tail per ply.
const BOARD = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR';

// 8-ply Ruy Lopez — real SANs so the analytics opening signature is meaningful.
const MOVES = [
  { san: 'e4', coord: 'e2 e4' }, { san: 'e5', coord: 'e7 e5' },
  { san: 'Nf3', coord: 'g1 f3' }, { san: 'Nc6', coord: 'b8 c6' },
  { san: 'Bb5', coord: 'f1 b5' }, { san: 'a6', coord: 'a7 a6' },
  { san: 'Ba4', coord: 'b5 a4' }, { san: 'Nf6', coord: 'g8 f6' },
];

// FEN whose side-to-move + fullmove fields make ArchiveProjection.plyIndex
// resolve to `ply` (0-based half-move index of the move that produced it).
function fenAtPly(ply) {
  const side = ply % 2 === 0 ? 'b' : 'w'; // after a white move it's black to move
  const k = Math.floor(ply / 2);
  const full = ply % 2 === 0 ? k + 1 : k + 2;
  return `${BOARD} ${side} KQkq - 0 ${full}`;
}

function msg(gameId, value) {
  return {
    key: registry.serialize({ data: gameId, schemaType: SCHEMA_TYPE_STRING }),
    value: registry.serialize({ data: value, schemaType: SCHEMA_TYPE_STRING }),
  };
}

export default function () {
  const gameId = `perf-game-${__VU}-${__ITER}`;
  const now = Date.now();
  const messages = [];

  messages.push(
    msg(gameId, JSON.stringify({
      type: 'GameStarted', gameId, resultingFen: fenAtPly(0), occurredAt: now,
    }))
  );
  MOVES.forEach((m, i) => {
    messages.push(
      msg(gameId, JSON.stringify({
        type: 'MoveMade', gameId, resultingFen: fenAtPly(i),
        moveCoord: m.coord, san: m.san, occurredAt: now + i + 1,
      }))
    );
  });
  // Terminal: final FEN has plyCount 8 (plyIndex 7) so all 8 plies are kept.
  messages.push(
    msg(gameId, JSON.stringify({
      type: 'Forfeited', gameId, resultingFen: fenAtPly(7),
      winner: 'white', occurredAt: now + 100,
    }))
  );

  writer.produce({ messages });
}

export function teardown() {
  writer.close();
}

export function handleSummary(data) {
  return {
    '/out/kafka-complete/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const fmt = (v, suffix = 'ms') =>
    v === undefined ? 'n/a' : `${(v * 1000).toFixed(2)} ${suffix}`;
  const w = m.kafka_writer_write_seconds?.values;
  const err = m.kafka_writer_error_count?.values;
  const iters = m.iterations?.values;
  return [
    '',
    '── k6/kafka — complete-game sequences (consumer + write load) ──',
    `  produce/game p50:  ${fmt(w?.med)}`,
    `  produce/game p95:  ${fmt(w?.['p(95)'])}`,
    `  producer errors: ${err?.count ?? 0}`,
    `  games produced: ${iters?.count ?? 0}   (= archive upserts + spark summaries)`,
    '───────────────────────────────────────────────────────────────',
    '',
  ].join('\n');
}
