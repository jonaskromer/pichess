# piChess — HTWG Deployment

Deploying piChess to the assigned HTWG virtual server, following lecture
[09‑Deployment](https://markoboger.github.io/HTWG-AIN-BOGER-slides/lectures/software-architecture/09-Deployment.html)
— the progression **Docker → Docker Compose → Kubernetes → k3s → k3d → Keycloak**.

**Status: IMPLEMENTED.** The path is built and validated end‑to‑end (local
Multipass host‑k3s, local k3d, and the prod‑compose fallback), and has been
exercised against the real HTWG box — the `mongo:4.4` pin in §8 was discovered by
watching `mongo:5+` crash‑loop on its AVX‑less QEMU vCPUs. What exists today:

- **CI publishes images** — `.github/workflows/release.yml` builds multi‑arch
  (amd64 + arm64) images for `gateway`, `game-service`, `repository`,
  `lobby-service`, and `bot-tournament`, and pushes them to **GHCR** on every
  `v*` tag (gated by the `test.yml` coverage suite).
- **Kubernetes manifests** — a **Kustomize** base + three nested overlays
  (`mvp` ⊂ `lobbies` ⊂ `full`) under `deploy/k8s/`.
- **A local‑driven Ansible pipeline** (`deploy/ansible/`) — provisions a cluster
  (**host k3s** *or* **k3d / k3s‑in‑Docker** for no‑sudo hosts) and applies a
  tier; `reset.yml` downgrades / wipes / tears down.
- **A prod docker‑compose fallback** (`deploy/compose/`) — mirrors the same three
  tiers without a Kubernetes control plane, for Docker‑only hosts.

The one deviation from the original plan: deployment is **driven locally from the
operator's Mac, not from CI** — the HTWG VM sits behind a 2FA campus VPN a GitHub
runner can't reach (§7).

> This file is the **as‑built** overview. The runnable how‑tos live next to the
> code: [`deploy/ansible/README.md`](../deploy/ansible/README.md) and
> [`deploy/compose/README.md`](../deploy/compose/README.md).

---

## 1. What the lecture required — and how we satisfied it

The deck teaches **Docker → Docker Compose → Kubernetes → k3s → k3d → Keycloak**:

| Lecture step | piChess |
|---|---|
| Review the Dockerfiles | `sbt-native-packager` per service (`eclipse-temurin:23-jre`, layered) |
| Extend Docker Compose | dev `docker-compose.yml` (12 services) + prod `deploy/compose/` (tiered) |
| k3d cluster + k8s manifests (`kubectl apply`) | `deploy/k8s/` Kustomize base+overlays; `provision-k3d.yml` stands up k3d |
| Deploy on the assigned VM (k3s) | `provision*.yml` + `deploy.yml -e target=htwg` (k3d there — no sudo) |
| *(optional)* Keycloak | deferred (§10) |

What the slides do **not** give us still holds — they ship only generic example
commands. The HTWG specifics we had to discover ourselves are now pinned down in §8.

---

## 2. What's built

### 2.1 CI — image publishing (`.github/workflows/`)
- `test.yml` — coverage gate on every push / PR (also `workflow_call`).
- `release.yml` — on a `v*` tag: re‑runs `test.yml`, then for the five deployable
  services runs `sbt <svc>/Docker/stage` and `docker buildx build --platform
  linux/amd64,linux/arm64 --push`, tagging `:<version>` **and** `:sha-<sha>` under
  `ghcr.io/jonaskromer/pichess-*`, and cuts a GitHub Release.
- `metrics.yml` — README badge data.
- **No deploy job** — image build/push and rollout are deliberately separate (§7).

### 2.2 Kubernetes manifests — Kustomize tiers (`deploy/k8s/`)

```
deploy/k8s/
  base/                       # the MVP, as raw Deployments/Services + a ConfigMap generator
    namespace.yaml            #   pichess
    gateway.yaml              #   Deployment + Service :8090 (web UI + REST + SSE), probes, limits
    game-service.yaml         #   Deployment + Service :9000 gRPC (embeds the NNUE engine)
    ingress.yaml              #   Traefik Ingress, host-less (matches the raw IP or *.nip.io)
    kustomization.yaml        #   configMapGenerator pichess-config (inmemory) + image newTag
  overlays/
    mvp/      = base as-is
    lobbies/  = mvp + lobby-service + a gateway patch (PICHESS_LOBBY_URL)
    full/     = lobbies + mongodb + redis + kafka + repository + bot-tournament, ConfigMap merged to mongo+redis+kafka
```

Two Kustomize features carry real weight:

- **Hashed `configMapGenerator` → automatic rollout.** `pichess-config` is a
  *generated* ConfigMap, so the `full` overlay's `inmemory → mongo` flip yields a
  new **hashed** ConfigMap name; Kustomize rewrites every `envFrom` reference, the
  pod spec changes, and the JVM pods roll automatically — no manual
  `rollout restart`, and no churn when nothing changed.
- **`pichess.tier` labels.** `lobbies`/`full` workloads (and their PVCs) carry a
  `pichess.tier` label so `reset.yml` can select and prune the workloads of every
  tier *above* a downgrade target (§2.3).

The image tag is pinned per release via `newTag` in the kustomizations (currently
`0.0.2`), kept in sync **by hand** with `image_tag` in `group_vars/all.yml` and
`PICHESS_IMAGE_TAG` in the compose `*.env` files.

### 2.3 Ansible — the deploy pipeline (`deploy/ansible/`)

Local‑driven (runs from your Mac, not CI), idempotent (re‑run after a dropped VPN —
completed tasks report `ok`, not `changed`), and **defaults to the local Multipass
target** so you can't hit HTWG by accident (`-e target=htwg` opts in).

**Two provisioning paths — the host decides which:**

| Playbook | Roles | Installs | Privilege | For |
|---|---|---|---|---|
| `provision.yml` | `base`, `k3s` | host **k3s** via `curl \| sh` | needs **root** (apt/systemd/ufw) | root‑capable boxes (Multipass/Lima testbed) |
| `provision-k3d.yml` | `k3d` | **k3d** (k3s‑in‑Docker), userspace | **no sudo** (Docker group only) | the **HTWG fleet** — needs rootful Docker |

**Shared apply/reset** — both paths feed the same two playbooks, which stay
runtime‑agnostic through `pichess_kubectl` (`k3s kubectl` vs
`kubectl --kubeconfig …`) and `pichess_become` (set per host in `group_vars/`):

- `deploy.yml` (role `pichess`) — copy the kustomize tree to the host,
  `kubectl apply -k overlays/<tier>`, wait for the rollout. **Additive** — only
  ever converges *up*.
- `reset.yml` (role `pichess`, `pichess_prune=true`) — **authoritative**: apply the
  target tier, then delete the workloads of every higher tier (by `pichess.tier`).
  Data PVCs are **kept** (a later upgrade rebinds them) unless
  `-e pichess_wipe_data=true`; `-e pichess_teardown=true` deletes the whole namespace.

**Targets** (`inventory.ini`): `local` (Multipass) and `htwg`. HTWG connection vars
(`SERVER_IP` / `SSH_USERNAME` / `SSH_PW`) are read from the environment via
`group_vars/htwg.yml` (sourced from `.env.local`) — **nothing secret is committed**.
`group_vars/htwg.yml` also carries the k3d profile (`pichess_become: false`,
`pichess_kubectl: kubectl --kubeconfig …`, a home‑dir `manifests_dest`).

**Key hygiene** (the HTWG box is shared & externally managed): use a dedicated
throwaway deploy key, never your git key; the `base` role *asserts* the authorized
file is a public key (refuses anything containing `PRIVATE KEY`); the pipeline never
forwards your SSH agent, never runs `git` on the VM, and pulls only from **public**
ghcr — so no personal credential ever reaches the host.

### 2.4 Prod compose fallback (`deploy/compose/`)

A root‑less, Docker‑only path for hosts with the `docker` group but no k3s/k3d. It
pulls the same ghcr images and mirrors the three tiers one‑for‑one via per‑tier
env‑files (`mvp.env` / `lobbies.env` / `full.env`, each setting `COMPOSE_PROFILES`
**and** the backend env). Only the gateway is published (`:8090`); a downgrade is
two steps (bring up the lower tier, then `rm -sf` the higher‑tier services — named
data volumes survive). Distinct from the **root `docker-compose.yml`**, which is the
dev rig (local builds, polyglot persistence + obs + perf profiles).

---

## 3. Target architecture on the cluster (the `full` tier)

```
            Internet  :80
        ┌──────▼───────┐  Traefik Ingress (ships with k3s/k3d), host-less
        │   Ingress    │  → gateway:8090
        └──────┬───────┘
        ┌──────▼───────┐
        │   gateway    │ Deployment, Svc :8090  (web UI + REST + SSE)   [mvp]
        └──┬────────┬──┘
     gRPC  │        │ HTTP (PICHESS_LOBBY_URL)
   ┌───────▼──┐  ┌──▼───────────┐
   │  game-   │  │ lobby-service│ Deployment, Svc :8092               [lobbies]
   │ service  │  └──────────────┘
   │ :9000    │      Kafka     ┌─────────────┐
   │ (+engine)│──────────────▶ │   kafka     │ StatefulSet :9092/:9093 (KRaft, PVC) [full]
   └────┬─────┘    publish     └──────┬──────┘
        │ mongo                       │ consume
   ┌────▼─────┐                ┌──────▼──────┐
   │ mongodb  │ StatefulSet    │ repository  │ Deployment, Svc :8091          [full]
   │ :27017   │ (PVC, 4.4)     └──────┬──────┘
   └──────────┘ ◀──────────────────── ┘ mongo
   ┌──────────┐
   │  redis   │ Deployment, Svc :6379 (cache decorator; ephemeral, no PVC)    [full]
   └──────────┘
```

**Exposure:** only `gateway` is public (Ingress). Everything else
(`game-service` gRPC, `repository`, `lobby-service`, `mongodb`, `redis`, `kafka`) is
`ClusterIP`/headless and internal to the `pichess` namespace. Compose hostnames
become k8s Service names (`game-service:9000`, `kafka:9092`, `mongodb:27017`,
`redis:6379`). **gRPC stays in‑cluster** (gateway→game‑service), so there's no
HTTP/2‑through‑Ingress headache.

---

## 4. The tier model

The overlays are **strictly nested**, so a deploy/upgrade/downgrade is just "pick a
tier". Each higher tier needs more RAM (the table below sizes the JVMs + datastores;
`full` is why the HTWG VM was bumped 4 → 12 GB, see §8).

| Tier | Adds | Backend | Roughly needs |
|---|---|---|---|
| `mvp` | gateway + game‑service | in‑memory | ~1.5 GB |
| `lobbies` | + lobby‑service | in‑memory | ~2 GB |
| `full` | + repository + kafka + mongo + redis | mongo + redis, Kafka event log | **~8–12 GB** (5 JVMs + Kafka + Mongo) |

Per‑pod `resources.requests/limits` are set on every workload (e.g. game‑service
`512Mi–1Gi` and up to 2 CPU for search; mongo/kafka `~1Gi` each) — see the manifests.

---

## 5. Config & secrets wiring

Services already read everything from env vars, so the compose→k8s move was
mechanical and lives entirely in the **`configMapGenerator`**: `PICHESS_BACKEND`,
`PICHESS_CACHE`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP`,
`GAME_SERVICE_GRPC`, mongo/redis hosts, and the service ports. The base sets the
in‑memory values; the `full` overlay *merges* the durable ones over them (which is
what triggers the hashed‑ConfigMap rollout in §2.2).

**No Secret object exists — by design.** The `full`‑tier datastores (Mongo, Redis,
Kafka) run **unauthenticated**: they're ClusterIP‑only, never exposed past the
Ingress, and carry demo data. That removes the credential‑management surface
entirely for the assignment. (If Mongo/Redis auth or a Lichess token were ever
needed, a `Secret` referenced via `envFrom` is the drop‑in spot.)

Image pulls need **no** pull‑secret either: the ghcr packages are **public**.

---

## 6. Run it

> **Pre-deploy checklist** (each of these has bitten a real deploy):
> 1. **The release images must exist on GHCR.** A deploy pulls
>    `ghcr.io/jonaskromer/pichess-{gateway,game-service,repository,lobby-service,bot-tournament}:<version>`,
>    published by `release.yml` **only if its 100%-coverage gate passed** — a coverage
>    failure on the tagged commit means no images and the deploy `ImagePullBackOff`s.
>    Verify first: `for i in gateway game-service repository lobby-service bot-tournament; do docker manifest inspect ghcr.io/jonaskromer/pichess-$i:<version> >/dev/null 2>&1 && echo "$i ok" || echo "$i MISSING"; done`
> 2. **Bump the image tag** to `<version>`: set `newTag` in `deploy/k8s/base/kustomization.yaml`
>    (gateway, game-service), `overlays/lobbies/kustomization.yaml` (lobby-service), and
>    `overlays/full/kustomization.yaml` (repository, bot-tournament). The kustomize `newTag` is what the
>    deploy applies — `image_tag` in `group_vars/all.yml` is reference-only. Keep
>    `deploy/compose/*.env` in sync.
> 3. **HTWG uses key auth** (no `sshpass` on the Mac): append
>    `-e ansible_password= -e ansible_ssh_private_key_file=~/.ssh/pichess_htwg` to the
>    `target=htwg` runs below.

```bash
# one-time
brew install ansible
cd deploy/ansible
ansible-galaxy collection install -r requirements.yml -p collections

# validate — no host needed
ansible-playbook provision.yml --syntax-check
ansible-playbook deploy.yml    --syntax-check
ansible-lint

# local Multipass testbed (host k3s — default target, no -e needed)
ansible-playbook provision.yml          # OS baseline + k3s
ansible-playbook deploy.yml             # apply mvp
ansible-playbook deploy.yml -e pichess_tier=full   # upgrade to full

# HTWG (k3d — no sudo; VPN must be up; validate locally first)
set -a; . ../../.env.local; set +a      # SERVER_IP / SSH_USERNAME / SSH_PW
ansible-playbook provision-k3d.yml -e target=htwg
ansible-playbook deploy.yml        -e target=htwg -e pichess_tier=full
ansible-playbook reset.yml         -e target=htwg -e pichess_tier=lobbies   # downgrade, keep data

# no-Kubernetes fallback (Docker-only host)
cd ../compose
docker compose --env-file full.env -f docker-compose.prod.yml up -d --remove-orphans
```

Browse the result at `http://<host>/` (cluster ingress on host port 80) or
`http://<host>:8090/` (compose). On the k3s path the playbook fetches the kubeconfig
back to `deploy/ansible/kubeconfig-<host>.yaml` for `kubectl` from your Mac.

---

## 7. Why local Ansible, not a CI deploy job

The original plan's Phase 5 imagined a GitHub Actions `deploy.yml` (push to `main` →
SSH/kubeconfig → `kubectl set image`). We **deliberately did not build that**: the
HTWG VM is reachable only through the **campus VPN, which requires 2FA**, so a GitHub
runner can't reach it. Instead:

- **CI's job stops at "an image exists"** — `release.yml` builds & pushes to ghcr on
  a tag. That keeps *tested* (every push) and *released* (a tagged image) cleanly
  separate.
- **Rollout is a human‑initiated, idempotent Ansible run** from inside the VPN. If
  the VPN drops mid‑run, re‑run — completed steps are `ok`, not `changed`.

If HTWG ever exposed a reachable endpoint (or a self‑hosted runner inside the VPN),
the deploy job becomes a thin wrapper over the exact same `deploy.yml`.

---

## 8. HTWG specifics (the things the slides didn't tell us)

| Item | Reality | Consequence |
|---|---|---|
| Access | VPN + 2FA, SSH as `chess` | deploy from the Mac, not CI (§7) |
| Privileges | `chess` is in the **docker group, no sudo** | can't install host k3s → **k3d** path (`provision-k3d.yml`) |
| Docker mode | must be **rootful** | k3d's kubelet can't start under rootless/userns (`/dev/kmsg`) — the role fails fast if it detects rootless |
| CPU | QEMU vCPUs **without AVX** | `mongo:5+` crash‑loops (SERVER‑54407) → pinned **`mongo:4.4`** + the legacy `mongo` shell health probe |
| RAM | bumped **4 → 12 GB** | enough for the `full` tier (5 JVMs + Kafka + Mongo) |
| Registry | public **ghcr** is fine | no pull‑secret needed |
| Ingress | Traefik on host port 80, **host‑less** rule | reach it at the raw IP (or a `*.nip.io` name) |

Pinned versions: **k3s `v1.31.5+k3s1`**, **k3d `v5.8.3`** + **kubectl `v1.31.5`**,
arch `amd64` (pass `-e k3d_arch=arm64` for an Apple‑silicon local VM).

---

## 9. Key risks — and how they're handled now

- **Resource pressure** on one VM (5 JVMs + Kafka + Mongo) → the **tier model** lets
  you run `mvp`/`lobbies` cheaply; `full` is gated on the 12 GB upgrade, with per‑pod
  `requests/limits` set.
- **Kafka advertised listeners** (the #1 single‑node‑KRaft failure) → solved: a
  **headless Service with `publishNotReadyAddresses: true`** so DNS resolves `kafka`
  before the broker is Ready, advertised + controller‑quorum both at `kafka:9092/:9093`.
- **Stateful data** → k3s local‑path PVCs for Mongo & Kafka (2 Gi each); a tier
  downgrade keeps them so games survive (node‑local, fine for single‑node).
- **Mongo on AVX‑less HTWG QEMU** → `mongo:4.4` (§8).
- **Image pull auth** → sidestepped: public ghcr packages.

---

## 10. What's left / optional

- **Keycloak** access management (lecture's optional step) — not built; a
  `keycloak` Deployment + Traefik forward‑auth is the drop‑in spot. Deferred unless
  required for the grade.
- **CI‑driven deploy** — intentionally not built (§7); revisit only if a runner can
  reach the VPN‑gated box.
- **Image‑tag automation** — the release tag is propagated to three places
  (`group_vars/all.yml`, the kustomizations' `newTag`, the compose `*.env`) **by
  hand**; a small sync script/`make` target would remove the foot‑gun.
- **game‑service restart resilience** — replay `chess.game-events` on startup to
  rebuild in‑memory state (tracked in the roadmap, not strictly a deployment item).
</content>
</invoke>
