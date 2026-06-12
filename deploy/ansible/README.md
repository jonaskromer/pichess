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

## Validate — no host needed

```bash
ansible-playbook provision.yml --syntax-check
ansible-playbook deploy.yml    --syntax-check
ansible-lint                       # idempotency / best-practice lint
```

## Dry‑run + real run against the Multipass testbed

```bash
multipass launch noble --name pichess-vm --cpus 4 --memory 4G --disk 20G \
  --cloud-init <(echo "ssh_authorized_keys: [\"$(cat ~/.ssh/id_ed25519.pub)\"]")
# put its IP into inventory.ini [local]:  multipass info pichess-vm

ansible-playbook provision.yml --check --diff      # dry run (module tasks; shell tasks skip)
ansible-playbook provision.yml                     # real
ansible-playbook provision.yml                     # IDEMPOTENCE CHECK → expect changed=0
ansible-playbook deploy.yml                        # apply MVP
```

After `provision.yml` succeeds on the VM, `multipass snapshot pichess-vm` so you
can `multipass restore` back to a clean k3s box and iterate on `deploy.yml`.

## HTWG (only once it's solid on Multipass)

```bash
set -a; . ../../.env.local; set +a       # SERVER_IP / SSH_USERNAME / SSH_PW (VPN must be up)
ansible-playbook provision.yml -e target=htwg -e ssh_public_key=~/.ssh/id_ed25519.pub
ansible-playbook deploy.yml    -e target=htwg -e pichess_tier=mvp
```

`provision.yml` fetches the cluster's kubeconfig to
`deploy/ansible/kubeconfig-<host>.yaml` (server rewritten to the VM IP, **gitignored —
it holds cluster credentials**) so you can `kubectl --kubeconfig … get pods` from your Mac.

## Notes / next

- **Tiers:** `pichess_tier=mvp` is wired. `lobbies` and `full` overlays (and the
  SOPS‑encrypted Secret the `full` tier needs for Mongo/Redis) are the next increment.
- **Image tag** lives in two places kept in sync by hand: `image_tag` in
  `group_vars/all.yml` and `newTag` in `k8s/base/kustomization.yaml`.
- **Don't run against HTWG yet** — validate + Multipass first.
