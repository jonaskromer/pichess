// Centralised env-var reading. Every script imports `cfg` and uses
// `cfg.gatewayUrl` etc. — no script touches __ENV directly so the
// fallback defaults live in one place.
//
// Note: load-shape env vars are PICHESS_K6_VUS / PICHESS_K6_DURATION,
// not the reserved K6_VUS / K6_DURATION names. Setting K6_VUS at the
// k6 level forces a default `constant-vus` scenario and overrides
// every script's own `scenarios: { … }` block — which would silently
// disable the browser type for the lobby-flow surface.

export const cfg = {
  gatewayUrl: __ENV.K6_GATEWAY_URL || 'http://localhost:8090',
  lobbyUrl:   __ENV.K6_LOBBY_URL   || 'http://localhost:8092',
  // Kafka host-side listener — the kafka service advertises
  // localhost:29092 on its PLAINTEXT_HOST listener (the in-network
  // kafka:9092 doesn't resolve from a network_mode: host container).
  kafkaBrokers: (__ENV.K6_KAFKA_BROKERS || 'localhost:29092').split(','),
  // game-service maps gRPC on host port 9000.
  grpcTarget: __ENV.K6_GRPC_TARGET || 'localhost:9000',

  vus:      parseInt(__ENV.PICHESS_K6_VUS || '5', 10),
  duration: __ENV.PICHESS_K6_DURATION || '30s',
};
