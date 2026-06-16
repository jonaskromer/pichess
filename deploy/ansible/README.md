# piChess deployment (Ansible → k3s)

Local‑driven deploy: a blank Ubuntu 24.04 host → k3s → the pichess stack. The
HTWG VM is reachable only through the campus VPN (which needs 2FA), so this runs
**from your Mac**, not from CI. Everything is idempotent — if the VPN drops
mid‑run, just re‑run; completed steps report `ok`, not `changed`.

## Layout

```
deploy/
  ansible/
    ansible.cfg            # VPN-drop-tolerant SSH (ControlPersist, keepalive, retries)
    inventory.ini          # `local` (Multipass) + `htwg` hosts
    group_vars/all.yml     # tier, image tag, k3s version, firewall, paths
    group_vars/htwg.yml    # HTWG connection — read from .env.local env, nothing committed
    provision.yml          # OS baseline + k3s   (roles: base, k3s)
    deploy.yml             # apply the tier      (role: pichess)
    roles/{base,k3s,pichess}/
  k8s/
    base/                  # MVP: namespace, config, game-service, gateway, ingress
    overlays/mvp/          # = base   (lobbies / full overlays: TODO)
```

## Prerequisites (one‑time)

```bash
brew install ansible
ansible-galaxy collection install -r requirements.yml -p collections
# only needed to talk to HTWG with the password (Multipass uses key auth):
brew install hudochenkov/sshpass/sshpass
```

## Safety: defaults to the local VM

Both playbooks are `hosts: "{{ target | default('local') }}"`, so a bare
`ansible-playbook …` hits **Multipass**, never HTWG. Opt in explicitly with
`-e target=htwg`.

## Key hygiene (the HTWG VM is shared & externally managed — treat it as untrusted)

Never put your personal/git SSH key on that box. Use a **dedicated, throwaway
deploy key** instead:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/pichess_htwg -C pichess-deploy -N ''
```

`inventory.ini` already defaults `[local]` to `~/.ssh/pichess_htwg`. Pass the same
`.pub` to `provision.yml` (see below) to drop the password on later runs. The
`base` role *asserts* the file is a public key and refuses to authorize anything
containing `PRIVATE KEY`, so a typo can't push private-key material to the VM.
The pipeline never forwards your SSH agent, never runs `git` on the VM, and pulls
images from **public** ghcr — so no personal credential ever needs to reach it.

## Validate — no host needed

```bash
ansible-playbook provision.yml --syntax-check
ansible-playbook deploy.yml    --syntax-check
ansible-lint                       # idempotency / best-practice lint
```

## Dry‑run + real run against the Multipass testbed

```bash
multipass launch noble --name pichess-vm --cpus 4 --memory 4G --disk 20G \
  --cloud-init <(echo "ssh_authorized_keys: [\"$(cat ~/.ssh/pichess_htwg.pub)\"]")
# put its IP into inventory.ini [local]:  multipass info pichess-vm

ansible-playbook provision.yml --check --diff      # dry run (module tasks; shell tasks skip)
ansible-playbook provision.yml                     # real
ansible-playbook provision.yml                     # IDEMPOTENCE CHECK → expect changed=0
ansible-playbook deploy.yml                        # apply MVP
```

After `provision.yml` succeeds on the VM, `multipass snapshot pichess-vm` so you
can `multipass restore` back to a clean k3s box and iterate on `deploy.yml`.

## Two provisioning paths

The host you deploy to decides how Kubernetes gets there:

- **`provision.yml`** (roles `base`, `k3s`) — installs **host k3s** via `curl|sh`.
  Needs **root** (apt, systemd, ufw). Use on root-capable boxes (Multipass/Lima).
- **`provision-k3d.yml`** (role `k3d`) — installs **k3d (k3s-in-Docker)** as an
  unprivileged user (userspace k3d+kubectl, cluster in the Docker daemon). **No
  sudo** — for Docker-group-only hosts like the **HTWG fleet** (where `chess` has
  no root). Needs **rootful** Docker (the kubelet can't start under rootless/userns).

`deploy.yml`/`reset.yml` are shared across both — `pichess_kubectl` + `pichess_become`
(set per host in `group_vars/`) point them at host-k3s or the k3d cluster. The
kustomize tiers apply **unchanged** either way. For a lighter, non-k8s option see
`deploy/compose/` (docker-compose, same tiers).

## HTWG (k3d — no sudo there; validate locally first)

```bash
set -a; . ../../.env.local; set +a       # SERVER_IP / SSH_USERNAME / SSH_PW (VPN must be up)
ansible-playbook provision-k3d.yml -e target=htwg          # k3d cluster, unprivileged
ansible-playbook deploy.yml         -e target=htwg -e pichess_tier=full
ansible-playbook reset.yml          -e target=htwg -e pichess_tier=lobbies   # downgrade
```

`group_vars/htwg.yml` carries the k3d profile (`pichess_become: false`,
`pichess_kubectl: kubectl --kubeconfig …`, home-dir `manifests_dest`), so the same
`deploy.yml`/`reset.yml` Just Work against the box. The cluster ingress is on the
host port `k3d_ingress_host_port` (default 80) → browse `http://<host>/`.

## Notes / next

- **All three tiers** (`mvp`/`lobbies`/`full`) + `reset.yml` (downgrade / wipe /
  teardown) are wired and validated on host-k3s, k3d, and compose. No Secret is
  needed — the `full`-tier datastores run unauthenticated by design.
- **Image tag** lives in `image_tag` (`group_vars/all.yml`) + `newTag` in the
  kustomizations (+ the compose `*.env`), kept in sync by hand.
- **k3d/k3s arch** defaults to `amd64` (HTWG); pass `-e k3d_arch=arm64` for an
  Apple-silicon local VM.
