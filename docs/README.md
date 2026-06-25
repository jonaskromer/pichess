# piChess Documentation

Index of the `docs/` tree. **New here? Start with [architecture.md](architecture.md)** —
the canonical system map — then dip into whichever area below you need.

## Architecture & decisions
| Doc | What's in it |
|---|---|
| [architecture.md](architecture.md) | The system map: service graph, the 34-module SBT inventory, gRPC/Kafka contracts, the gateway HTTP/SSE surface, and the ADR index. The first thing to read. |
| [design.md](design.md) | The **web-UI design system** — CSS tokens, Tailwind setup, Laminar component helpers, screen skeletons. (UI, not domain.) |
| [adr/](adr/) | Architecture Decision Records (001–022) — one decision per file, with context/alternatives/consequences. Indexed in architecture.md. |

## Domain & rules
| Doc | What's in it |
|---|---|
| [game-rules.md](game-rules.md) | Implemented chess mechanics — moves, en passant, castling, promotion, check/mate, every draw rule. |
| [notation.md](notation.md) | Coordinate / SAN / FEN / PGN notation parsing & serialization. |

## Bot & engine
| Doc | What's in it |
|---|---|
| [bot.md](bot.md) | The engine (HCE + NNUE hybrid), the Lichess client, and **how the bot's Elo is correctly measured** (the canonical ≈2350 figure + per-flag deltas). |
| [elo-roadmap.md](elo-roadmap.md) | The live Elo-improvement working doc — levers, training recipes, perf-hardening record, findings, session state. |
| [engine-levers.md](engine-levers.md) | The search/eval lever catalogue (with A/B verdicts), the shared training pipeline, and the `make ab-sweep` validation method. |
| [tournament-integration.md](tournament-integration.md) | The `bot-tournament` module — NowChess protocol, verified wire formats, how to run it. |
| [gpu-training-handoff.md](gpu-training-handoff.md) | Runbook to continue NNUE training on a CUDA box — dataset fetch, eb8 hyperparameters, A/B. |

## Persistence
| Doc | What's in it |
|---|---|
| [db-selection-report.md](db-selection-report.md) | The backend × cache × workload selection report behind the **mongo + redis** production default. |

## Performance
| Doc | What's in it |
|---|---|
| [performance.md](performance.md) | The perf/observability **tooling reference** — Gatling, k6, JMH, profilers, Prometheus/Grafana/Jaeger, every env knob. |
| [perf-experiments.md](perf-experiments.md) | The two **experiments** (DB-matrix + optimisation A/B): methodology, how to run, how to read. |
| [performance-test-results.md](performance-test-results.md) | A curated **profiling campaign** — bottlenecks found in game-service + the fixes actually shipped, with before/after. |

> Generated perf artifacts (`perf-reports/<ts>/…`) live at the repo root, are **gitignored**, and regenerate via `make perf` — they're not part of this folder.

## Deployment & planning
| Doc | What's in it |
|---|---|
| [deployment-plan.md](deployment-plan.md) | The k3s/k3d rollout — Kustomize tiers + the local Ansible pipeline + a prod-compose fallback. (Runnable how-tos: `deploy/ansible/README.md`, `deploy/compose/README.md`.) |
| [roadmap.md](roadmap.md) | The 14-phase HTWG lecture plan and its status. |
