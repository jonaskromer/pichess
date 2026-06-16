# piChess — production compose stack (tier-equivalent to k3d/k8s)

A root-less, Docker-only deploy path for hosts where you have the `docker` group
but **no sudo/k3s** (e.g. the shared HTWG VM). It pulls the released **ghcr**
images and mirrors the `deploy/k8s` tiers one-for-one — same services, ports,
env, and single-node KRaft Kafka — without a Kubernetes control plane.

## Tiers

| Tier | Services | Backend |
|------|----------|---------|
| `mvp` | gateway + game-service | in-memory |
| `lobbies` | + lobby-service | in-memory |
| `full` | + repository + mongo + redis + kafka | mongo + redis, Kafka event log |

The tiers are **nested** (mvp ⊂ lobbies ⊂ full), exactly like the kustomize
overlays. Each `<tier>.env` is the single source of truth for a tier: it sets
`COMPOSE_PROFILES` (which services start) **and** the backend env (inmemory vs
mongo/redis/kafka), so you only ever pass one `--env-file`.

## Run a tier

```bash
cd deploy/compose
docker compose --env-file mvp.env     -f docker-compose.prod.yml up -d --remove-orphans
docker compose --env-file lobbies.env -f docker-compose.prod.yml up -d --remove-orphans
docker compose --env-file full.env    -f docker-compose.prod.yml up -d --remove-orphans
```

Only the **gateway** is published — browse `http://<host>:8090/`. Everything else
talks over the internal `pichess` network (same as ClusterIP services in k8s).

## Lifecycle ↔ the Ansible k3d pipeline

| k8s/k3d | compose equivalent |
|---------|--------------------|
| `deploy.yml -e pichess_tier=T` (upgrade/converge up) | `up -d --remove-orphans` with `T.env` |
| `reset.yml -e pichess_tier=T` (downgrade, keep data) | `up -d` with the lower `T.env`, **then** `rm -sf` the higher-tier services (see below) |
| `reset.yml -e pichess_wipe_data=true` | `down -v` (drops `pichess-prod_pichess-mongo-data`, `…-kafka-data`) |
| `reset.yml -e pichess_teardown=true` | `--profile mvp --profile lobbies --profile full down` |
| configMapGenerator backend auto-roll | compose recreates a service whose env changed on the next `up` |

### Downgrade (important)

`up` with a lower tier flips the backend env, but compose does **not** stop
services from profiles you dropped (and `--remove-orphans` only removes services
deleted from the file, not profile-disabled ones). So a downgrade is two steps —
bring up the target, then remove the higher-tier services. The named data volumes
survive the `rm` (the analog of `reset.yml` keeping the PVCs):

```bash
# full -> lobbies
docker compose --env-file lobbies.env -f docker-compose.prod.yml up -d --remove-orphans
docker compose -f docker-compose.prod.yml rm -sf mongodb redis kafka repository

# -> mvp also drops lobby-service
docker compose --env-file mvp.env -f docker-compose.prod.yml up -d --remove-orphans
docker compose -f docker-compose.prod.yml rm -sf mongodb redis kafka repository lobby-service
```

## Notes

- **Kafka is first-class in `full`**, not tied to a projection profile. game-service
  publishes `chess.game-events` whenever `KAFKA_BOOTSTRAP_SERVERS` is set (it picks
  `KafkaGameEventProducer` over the in-memory one); repository consumes them. The
  opening/analytics projections from the root dev compose are intentionally **not**
  part of these tiers.
- **Images** are pinned via `PICHESS_REGISTRY` / `PICHESS_IMAGE_TAG` in each
  `*.env`; bump them in lockstep with `deploy/k8s` and `group_vars/all.yml` when a
  new version is released. The host pulls them straight from public ghcr.
- **Data** lives in named volumes (`pichess-mongo-data`, `pichess-kafka-data`); a
  downgrade or `down` keeps them, `down -v` wipes them.
- This is distinct from the **root `docker-compose.yml`**, which is the dev rig
  (local image builds, polyglot persistence + obs + perf profiles).
