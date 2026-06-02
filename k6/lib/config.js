// Centralised env-var reading. Every script imports `cfg` and uses
// `cfg.gatewayUrl` etc. — no script touches __ENV directly so the
// fallback defaults live in one place.

export const cfg = {
  gatewayUrl: __ENV.K6_GATEWAY_URL || 'http://localhost:8090',
  lobbyUrl:   __ENV.K6_LOBBY_URL   || 'http://localhost:8092',
  kafkaBrokers: (__ENV.K6_KAFKA_BROKERS || 'localhost:9092').split(','),
  grpcTarget: __ENV.K6_GRPC_TARGET || 'localhost:8091',

  vus:      parseInt(__ENV.K6_VUS || '5', 10),
  duration: __ENV.K6_DURATION || '30s',
};
