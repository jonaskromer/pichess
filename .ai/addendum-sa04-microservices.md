# Addendum: SA-04 — Microservices

> Source: `docs/slides/SA-04-Microservices.pdf`

---

## Monolith vs Microservices

**Monolith**: all components in one deployment unit — compiled and shipped together.

**Microservices**: each component is its own deployable process. Components communicate via HTTP/IPC. Independent deployability is the key property.

---

## 9 Characteristics of Microservices

*(Martin Fowler)*

| # | Characteristic | What it means |
|---|---------------|---------------|
| 1 | **Componentization via services** | Components are independently deployable services, not libraries |
| 2 | **Business capability teams** | Teams own a full vertical slice (UI → DB) for one business domain |
| 3 | **Products not projects** | "You build it, you run it" — teams own production services |
| 4 | **Smart endpoints, dumb pipes** | Logic lives in services; REST/messaging is just transport |
| 5 | **Decentralized governance** | Each service chooses the best technology for its job |
| 6 | **Decentralized data management** | Each service owns its own database (polyglot persistence) |
| 7 | **Infrastructure automation** | CI/CD pipelines; automated test and deploy |
| 8 | **Design for failure** | Every service must handle downstream failures gracefully |
| 9 | **Evolutionary design** | Services can be replaced or upgraded independently |

---

## Library vs Service

| Aspect | Library | Service |
|--------|---------|---------|
| Process | In-process (same JVM) | Own process |
| Coupling | Compile-time | Runtime (HTTP/IPC) |
| Swap | Requires recompile | Independent deploy |
| Failure isolation | No (crashes host) | Yes |

Use **libraries** for tightly coupled utilities. Use **services** for independently deployable components.

---

## Case Studies

**Spotify**: ~810 microservices, ~600 developers. Each squad owns one service end-to-end.

**Netflix**: Invented chaos engineering. The **Simian Army** (Chaos Monkey, Chaos Gorilla, Latency Monkey, etc.) deliberately kills production instances to prove services handle failure. "Design for failure" is not optional here.

---

## SBT Multi-Project Structure

Split the πChess monolith into SBT sub-projects:

```
root/
├── model/    ← pure domain logic (chess rules, no I/O)
├── rest/     ← HTTP REST layer (Akka HTTP)
└── tui/      ← terminal UI (view layer)
```

Each sub-project becomes a Docker container. They communicate via REST API.

---

## Monolith vs Microservices Tradeoffs

| Monolith | Microservices |
|----------|---------------|
| Simple to deploy and debug | Independent deployability |
| No network overhead | Per-service technology choice |
| Good starting point | Team autonomy and ownership |
| Harder to scale individual parts | Scale only what needs scaling |

---

## Task 3

1. Split the monolith into **SBT multi-project** modules
2. Expose services via **REST** (HTTP)
3. Package each service as a **Docker** container
4. Modules communicate between Docker instances via REST API (prepared in Task 4)
