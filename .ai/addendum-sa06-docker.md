# Addendum: SA-06 — Docker

> Source: `docs/slides/SA-06-Docker.pdf`

---

## What are Containers?

Similar to shipping containers which provided one standardized solution for all transportation platforms, Docker containers provide a standardized way to package and run software across any platform.

- **Developer**: Solomon Hykes (March 2013)
- **Concept**: OS-level virtualization
- **Compared to VMs**: Containers use resource isolation features of the host OS kernel (like cgroups and namespaces in Linux) and share the host kernel, avoiding the heavy overhead of maintaining full virtual machines.

---

## Docker Architecture

- **Docker Daemon**: A background service running on the host OS that manages containers. It exposes a REST API and a Command Line Interface (CLI).
- **Image**: A read-only template with instructions for creating a container. Available from registries.
- **Container**: A running instance of an image.
- **Docker Registry**: Stores Docker images (e.g., Docker Hub). Images can be layered on top of other images.

---

## Docker CLI Cheat Sheet

- `docker build -t name .` — Create an image using the local `Dockerfile`.
- `docker run -p 4000:80 name` — Run container, mapping host port 4000 to container port 80.
- `docker run -d ...` — Run in detached (background) mode.
- `docker ps` / `docker ps -a` — View running / all containers.
- `docker stop <hash>` — Gracefully stop a container.
- `docker rm <hash>` — Remove a container.
- `docker images` — View local images.
- `docker rmi <image>` — Remove an image.
- `docker push username/repo:tag` — Upload image to a registry.

---

## Dockerfiles

A text document containing all the commands a user could call on the command line to assemble an image.
- `FROM`: Define base image (e.g., `ubuntu`)
- `COPY`: Add files from host to the container
- `RUN`: Execute commands during image build
- `CMD`: Specify default commands to run when the container starts

---

## Docker Compose & YAML

Often, multiple containers must be orchestrated together. Docker Compose defines and runs multi-container applications using a `compose.yaml` (or `docker-compose.yml`) file.

**YAML Basics**:
- Human-readable configuration optimized over XML/JSON.
- Uses indentation (2 spaces) and key-value mapping `key: value`.
- Sequences are denoted with `-`.

**Compose Terminology**:
- **Services**: Define the containers, images, and ports.
- **Networks**: Enable communication between the services.
- **Volumes**: Persist data securely outside the container lifecycles.

**Compose Commands**:
- `docker compose up` — Starts the entire system
- `docker compose down` — Stops and removes containers
- `docker compose logs` — Monitor output

*(For larger, production-scale orchestration, organizations transition from Docker Compose to Docker Swarm or Kubernetes).*

---

## Task 5

1. Start each of your micro services using Docker.
2. Then start the entire application using Docker-Compose.
