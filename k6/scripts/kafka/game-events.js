// Kafka surface: direct producer load against the `chess.game-events`
// topic. game-service publishes here in production; the repository,
// opening-service, and analytics-service all consume.
//
// Gatling can only reach this topic transitively (gateway → game-service
// → producer), where the upstream HTTP path becomes the bottleneck long
// before consumers are stressed. Producing directly with xk6-kafka
// lets us saturate the consumers and measure their lag in isolation.
//
// Payload: `GameDomainEvent` JSON exactly as `chess.events.GameDomainEvent`
// emits — `@jsonDiscriminator("type")` carries the variant. The
// repository consumer cares only about `gameId` (partition key) and
// `resultingFen` (canonical state to persist); the other fields are
// preserved for the analytics projection.

import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING } from 'k6/x/kafka';
import { cfg } from '/k6lib/config.js';

const TOPIC = 'chess.game-events';

const writer = new Writer({
  brokers: cfg.kafkaBrokers,
  topic: TOPIC,
  // Sane defaults for a perf rig — leave compression off so producer-side
  // CPU isn't confounded with broker throughput.
  autoCreateTopic: true,
});

const registry = new SchemaRegistry();

export const options = {
  scenarios: {
    kafka_produce: {
      executor: 'shared-iterations',
      vus: cfg.vus,
      iterations: cfg.vus * 100,        // each VU produces 100 events
      maxDuration: cfg.duration,
    },
  },
  thresholds: {
    // xk6-kafka exposes producer-side latency as `kafka_writer_write_seconds`
    // (units: seconds) and per-call errors as `kafka_writer_error_count`.
    //
    // The first batch waits on topic creation + leader election + the
    // producer's first metadata fetch, which routinely takes ~1.5 s
    // against a freshly-started cluster. The p95 threshold gates the
    // steady-state behaviour; p99 is loosened to be cold-start tolerant
    // so the smoke test doesn't fail on the first iteration's outlier.
    kafka_writer_write_seconds: ['p(95)<0.1', 'p(99)<2'],
    kafka_writer_error_count:   ['count<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// Pre-allocate FENs so the iteration body stays a hot loop — no
// string-building cost biases the producer measurement.
const START_FEN  = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const E4_FEN     = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';

function gameStartedEvent(gameId) {
  return JSON.stringify({
    type:          'GameStarted',
    gameId,
    resultingFen:  START_FEN,
    occurredAt:    Date.now(),
  });
}

function moveMadeEvent(gameId) {
  return JSON.stringify({
    type:          'MoveMade',
    gameId,
    resultingFen:  E4_FEN,
    moveCoord:     'e2 e4',
    san:           'e4',
    occurredAt:    Date.now(),
  });
}

export default function () {
  // Use the iteration scenario counter as the game id so partitions
  // distribute (Kafka hashes the key). `__VU` + `__ITER` is unique
  // within a run; prefix marks it as a perf-test event so consumers
  // can filter if needed.
  const gameId = `perf-${__VU}-${__ITER}`;

  // Half the messages are GameStarted, half MoveMade — gives the
  // consumer pattern-matching code a realistic workload mix.
  const payload =
    __ITER % 2 === 0 ? gameStartedEvent(gameId) : moveMadeEvent(gameId);

  // xk6-kafka's Writer.produce takes a `messages` array; each entry
  // is a {key, value, headers, time?}. Encoding `STRING` because we
  // hand it the already-serialised JSON bytes — no Avro/registry hop.
  // produce() returns undefined on success and throws on failure;
  // failures show up in `kafka_writer_error_count` (gated by the
  // threshold above), so there's no return value worth check()ing.
  writer.produce({
    messages: [
      {
        key:   registry.serialize({ data: gameId, schemaType: SCHEMA_TYPE_STRING }),
        value: registry.serialize({ data: payload, schemaType: SCHEMA_TYPE_STRING }),
      },
    ],
  });
}

export function teardown() {
  // Flush + close so the in-flight batch is acked before the run ends.
  writer.close();
}

export function handleSummary(data) {
  return {
    '/out/kafka/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const fmt = (v, suffix = 'ms') =>
    v === undefined ? 'n/a' : `${(v * 1000).toFixed(2)} ${suffix}`;
  const w   = m.kafka_writer_write_seconds?.values;
  const err = m.kafka_writer_error_count?.values;
  const iters = m.iterations?.values;
  return [
    '',
    '── k6/kafka — direct producer load ───────────────────────',
    `  produce p50:  ${fmt(w?.med)}`,
    `  produce p95:  ${fmt(w?.['p(95)'])}   (target ≤ 100 ms)`,
    `  produce p99:  ${fmt(w?.['p(99)'])}   (cold-start tolerance ≤ 2000 ms)`,
    `  producer errors: ${err?.count ?? 0}`,
    `  iterations: ${iters?.count ?? 0}   (≈ messages produced)`,
    '──────────────────────────────────────────────────────────',
    '',
  ].join('\n');
}
