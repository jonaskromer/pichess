# piChess — HTWG Deployment Plan

Plan to deploy piChess to an assigned HTWG virtual server, following lecture
[09‑Deployment](https://markoboger.github.io/HTWG-AIN-BOGER-slides/lectures/software-architecture/09-Deployment.html).
**Status: PARTIALLY IMPLEMENTED.** CI/CD image publishing has landed —
`.github/workflows/release.yml` builds **multi-arch (amd64 + arm64)** images for
`gateway`, `game-service`, `repository`, and `lobby-service` and **pushes them to
GHCR** on every `v*` tag (gated by the full `test.yml` coverage suite). What's
still missing is everything Kubernetes: no k8s/k3s manifests, no cluster, no
deploy job. The k3s rollout below is the remaining work.

---

## 1. What the lecture requires (the assignment)

The deck teaches the progression **Docker → Docker Compose → Kubernetes → k3s → k3d → Keycloak**
and asks students to:

1. Review the project's Dockerfiles.
2. Extend Docker Compose for local multi‑service testing. *(already done — we have a 12‑service compose)*
3. Create a **k3d** cluster locally and deploy the same stack with **Kubernetes manifests** (Deployment + Service, `kubectl apply -f`).
4. **Deploy on the assigned virtual server** (lecture installs k3s via `curl -sfL https://get.k3s.io | sh -`).
5. *Optional:* add **Keycloak** for access management (realm + client + test user).

### What the slides do NOT give us (must come from the instructor)
I fetched the deck twice — it contains only **generic, example** commands. It does **not** specify:

- the assigned VM hostname / IP, SSH user, or whether you have sudo/root;
- any HTWG‑provided container registry (it only references `ghcr.io/example/...` as an example);
- a public domain / ingress hostname pattern;
- firewall/open‑port policy.

**→ Action item: obtain these from Boger / the course org before Phase 4.** See §8.

---

## 2. Decisions for this plan (from your answers + lecture defaults)

| Topic | Decision | Rationale |
|---|---|---|
| **Orchestrator / target** | Bare VM + SSH, **install k3s** ourselves | Lecture's canonical path; assumed until instructor says otherwise |
| **Registry** | **ghcr.io** (`ghcr.io/<gh-user>/pichess-*`) | The registry the slides reference; free for the repo; works with GitHub Actions |
| **Infra scope** | **mongo + redis + kafka + gateway + game‑service + repository + lobby‑service** | "Full capability without obs/analytics." AI bot is embedded in game‑service |
| **CI/CD** | **GitHub Actions: build → push → deploy** | Your choice |
| **Excluded** | opening‑service, analytics‑service, postgres, cassandra, neo4j, clickhouse, prometheus, grafana, jaeger, tui, k6 | Not in scope; opening needs neo4j, analytics needs clickhouse |

---

## 3. Current state vs. what's missing

**We have:** working Dockerfiles via `sbt-native-packager` (`sbt dockerBuildAll` → `pichess-*:latest` on `eclipse-temurin:23-jre`), a full `docker-compose.yml`, a `mongo+redis` production backend already validated (`docs/db-selection-report.md`), all services 12‑factor‑ish (config from env vars), and — new — **GitHub Actions CI/CD that builds multi-arch images and pushes them to GHCR on tagged releases** (`release.yml`, gated by `test.yml`).

**Done since the first draft:**

- ✅ **Registry publishing** — `release.yml` builds `linux/amd64,linux/arm64` via buildx and pushes `ghcr.io/<owner>/pichess-{gateway,game-service,repository,lobby-service}` tagged `:<version>` + `:sha-<sha>`, then cuts a GitHub Release. (build.sbt still only does `Docker/publishLocal`; the multi-arch push lives in the workflow, not sbt's `dockerRepository`.)
- ✅ **GitHub Actions** — `.github/workflows/` now holds `test.yml` (coverage gate, every push/PR), `release.yml` (tag → build-push), and `metrics.yml` (badge data).

**Still missing (the k3s work):**

1. **Kubernetes manifests** — none exist (`find` for k8s/k3s/helm = empty).
2. **k3s on the VM** — not provisioned.
3. **A deploy job** — `release.yml` publishes images but does **not** roll them out to a cluster (no SSH/kubeconfig step yet).
4. **Secrets/config story for k8s** — currently env vars in compose; need ConfigMap + Secret.
5. **Kafka‑in‑k8s listeners**, **Mongo persistence (PVC)**, **Ingress** for the gateway.

---

## 4. Target architecture on k3s (single node)

```
            Internet
               │  :80/:443
        ┌──────▼───────┐  Traefik Ingress (ships with k3s)
        │   Ingress    │  host: pichess.<vm>.  → gateway
        └──────┬───────┘
        ┌──────▼───────┐
        │   gateway    │ Deployment, Svc :8090  (web UI + REST + SSE)
        └──────┬───────┘ gRPC
        ┌──────▼───────┐   Kafka     ┌─────────────┐
        │ game-service │──────────▶  │   kafka     │ StatefulSet :9092 (KRaft, PVC)
        │  :9000 gRPC  │             └──────┬──────┘
        │ (+ AI engine)│                    │ consume
        └──────┬───────┘             ┌──────▼──────┐
               │ mongo               │ repository  │ Deployment, Svc :8091
        ┌──────▼───────┐            └──────┬──────┘
        │   mongodb    │ StatefulSet :27017 ◀────────┘ mongo
        │   (PVC)      │            ┌─────────────┐
        └──────────────┘            │ lobby-svc   │ Deployment, Svc :8092
        ┌──────────────┐            └─────────────┘
        │    redis     │ Deployment/Svc :6379 (cache; PVC optional)
        └──────────────┘
```

**Service exposure:** only `gateway` is public (Ingress). `game-service` (gRPC), `repository`, `lobby-service`, `mongodb`, `redis`, `kafka` are `ClusterIP` (internal). Optionally expose `repository`/`lobby` via extra Ingress paths if you want to demo their REST.

**k8s DNS names** replace the compose hostnames: `game-service:9000`, `kafka:9092`, `mongodb:27017`, `redis:6379` (within the `pichess` namespace).

---

## 5. Work breakdown (phased)

### Phase 0 — Prep & info gathering
- Collect VM details + decide ingress hostname (§8). If no real domain, use `nip.io` (`pichess.<VM-IP>.nip.io`).
- Confirm GitHub repo location → fixes the ghcr namespace `ghcr.io/<owner>/`.

### Phase 1 — Registry‑ready images  ✅ *largely done (see §3)*
> `release.yml` already builds multi-arch images and pushes them to GHCR with
> version + `sha-` tags. The remaining nuance below (driving tags through sbt
> rather than the workflow) is optional polish, not a blocker.

- Add to each Docker‑enabled module in `build.sbt`:
  - `dockerRepository := Some("ghcr.io")`, `dockerUsername := Some("<owner>")`.
  - Tag with the **git SHA** (not just `latest`) so k8s rollouts are deterministic — e.g. drive `Docker / version` from an env var / sbt‑dynver.
- Add a `dockerPublishAll` alias (mirrors `dockerBuildAll` but `Docker/publish`) for the **in‑scope** services only: gateway, game‑service, repository, lobby‑service.
- Verify: `docker login ghcr.io`, push, pull from another host.

### Phase 2 — Local k3d dry‑run (lecture step 3, de‑risks the VM)
- `k3d cluster create pichess --agents 1` → `kubectl config use-context k3d-pichess`.
- Author manifests under **`deploy/k8s/`** (raw YAML, matching the slides; Kustomize base/overlay optional):
  - `00-namespace.yaml` (`pichess`)
  - `10-config.yaml` — ConfigMap: `PICHESS_BACKEND=mongo`, `PICHESS_CACHE=redis`, `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `GAME_SERVICE_GRPC=game-service:9000`, ports, mongo/redis hosts.
  - `11-secrets.yaml` — Secret: mongo user/password, redis password, ghcr `imagePullSecret` (dockerconfigjson), (later) Lichess token.
  - `20-mongodb.yaml` — StatefulSet + headless Service + PVC.
  - `21-redis.yaml` — Deployment + Service (+ optional PVC).
  - `22-kafka.yaml` — StatefulSet + Service, **single‑node KRaft**; set `KAFKA_ADVERTISED_LISTENERS` to the in‑cluster DNS name (the #1 gotcha). PVC for the log dir.
  - `30-game-service.yaml`, `31-repository.yaml`, `32-lobby-service.yaml`, `33-gateway.yaml` — Deployment + Service each; set `resources.requests/limits` and `readiness/liveness` probes.
  - `40-ingress.yaml` — Traefik Ingress → `gateway:8090`, host = chosen domain.
- Bring up DBs/Kafka first, then services; `kubectl apply -f deploy/k8s/`.
- Smoke test: `kubectl port-forward svc/gateway 8090:8090` → play a game vs the bot, confirm Kafka→repository→mongo flow.

### Phase 3 — JVM right‑sizing for one VM
- Four JVM services + Kafka (JVM) + Mongo on a single VM is the real risk. Set `-Xmx` per service (compose/.jvmopts give a starting point) and matching k8s memory `requests/limits`. Budget RAM before Phase 4 (rough: ~512 MB × 4 services + ~1 GB Kafka + ~512 MB Mongo + overhead ≈ **needs ≥ 6–8 GB VM**).

### Phase 4 — Provision the HTWG VM
- SSH in; install k3s: `curl -sfL https://get.k3s.io | sh -` (Traefik ingress + local‑path storage included).
- Copy kubeconfig to your laptop (`/etc/rancher/k3s/k3s.yaml`, rewrite server IP) for `kubectl`.
- Create the ghcr `imagePullSecret` (or make the ghcr packages public to skip auth).
- `kubectl apply -f deploy/k8s/` → verify pods, ingress, public URL.

### Phase 5 — GitHub Actions CI/CD  ✅ *build-push done; deploy job pending*
> The **build‑push** half exists today as `release.yml` (tag‑triggered, not
> push‑to‑`main`): it gates on `test.yml`, builds multi-arch, and pushes to GHCR.
> What's left is the **deploy job** that rolls those images onto the k3s box.

- `.github/workflows/deploy.yml`, on push to `main`:
  - **build‑push job:** checkout → JDK 23 + sbt cache → `make tailwind-build` → `docker/login-action` (ghcr, `GITHUB_TOKEN`) → `sbt dockerPublishAll` (tag = `${{ github.sha }}` + `latest`).
  - **deploy job (needs build‑push):** either (a) **SSH** to the VM (`appleboy/ssh-action`, key in secrets) and `kubectl -n pichess set image ...`/`rollout restart` + `kubectl apply -f deploy/k8s`, or (b) **kubeconfig secret** + `kubectl` from the runner. SSH is simplest for a self‑managed k3s box.
- Secrets needed in GitHub: `GHCR` is covered by `GITHUB_TOKEN`; add `SSH_HOST`, `SSH_USER`, `SSH_KEY` (or `KUBECONFIG`).

### Phase 6 — *(optional)* Keycloak
- Add a `keycloak` Deployment/Service + Ingress; protect the gateway via Traefik forward‑auth or in‑app OIDC. Create realm/client/test user. Defer unless required for the grade.

---

## 6. Manifests to author (inventory)

| File | Kind(s) | Notes |
|---|---|---|
| `deploy/k8s/00-namespace.yaml` | Namespace | `pichess` |
| `deploy/k8s/10-config.yaml` | ConfigMap | non‑secret env (backend, kafka, grpc, ports) |
| `deploy/k8s/11-secrets.yaml` | Secret ×N | mongo/redis creds, ghcr pull secret |
| `deploy/k8s/20-mongodb.yaml` | StatefulSet, Service, PVC | persistent |
| `deploy/k8s/21-redis.yaml` | Deployment, Service | PVC optional |
| `deploy/k8s/22-kafka.yaml` | StatefulSet, Service, PVC | KRaft, advertised listeners |
| `deploy/k8s/30..33-*.yaml` | Deployment, Service ×4 | game‑service, repository, lobby, gateway |
| `deploy/k8s/40-ingress.yaml` | Ingress | gateway public |

---

## 7. Config translation (compose → k8s)

Services already read config from env vars (see `docker-compose.yml`), so the move is mechanical:
`PICHESS_BACKEND`, `PICHESS_CACHE`, `KAFKA_BOOTSTRAP_SERVERS`, `GAME_SERVICE_GRPC`, `HTTP_PORT`,
`REPOSITORY_PORT`, lobby port, mongo/redis connection settings → split into **ConfigMap** (non‑secret)
and **Secret** (credentials), referenced via `envFrom`. Compose hostnames become k8s Service names.

---

## 8. Open items to get from the instructor / HTWG (blockers for Phase 4)

1. **Assigned VM**: hostname/IP, SSH user, key, and whether you have **sudo/root** (needed for k3s).
2. **Public access**: is there a DNS name / domain, or do we use the raw IP + `nip.io`? Are ports **80/443/22** open inbound?
3. **Registry**: is there an HTWG‑provided registry, or is **ghcr.io** fine? (affects pull‑secret + build.sbt).
4. **VM size**: vCPU / RAM / disk — drives the right‑sizing in Phase 3 (need ≥ ~6–8 GB RAM realistically).
5. **Keycloak**: required for the grade, or optional?

---

## 9. Key risks

- **Resource pressure** on a single VM (4 JVMs + Kafka + Mongo). Mitigate with `-Xmx` + k8s limits; drop redis‑cache or co‑locate if RAM is tight.
- **Kafka advertised listeners** in‑cluster — most common single‑node KRaft failure; get the DNS name right.
- **Stateful data** — use PVCs (k3s local‑path) so a pod restart doesn't wipe games; understand it's node‑local (fine for single‑node).
- **gRPC through Ingress** is avoided here (gateway→game‑service stays in‑cluster), so no HTTP/2 ingress headaches.
- **Image pull auth** — simplest is to make the ghcr packages public; otherwise the `imagePullSecret` must exist before apply.

---

## 10. Suggested order of execution

`Phase 0 (info) → 1 (registry images) → 2 (local k3d, prove manifests) → 3 (sizing) → 4 (VM + k3s) → 5 (CI/CD) → 6 (Keycloak, optional)`

Phases 1–3 need no HTWG access and can start immediately. Phase 4 is gated on §8.
