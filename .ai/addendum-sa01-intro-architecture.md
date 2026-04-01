# Addendum: SA-01 — Introduction to Software Architecture

> Source: `docs/slides/SA-01 Introduction to Software Architecture.pdf`

---

## Core Principle

Architecture is **not** an upfront fixed design. It is an **ongoing iterative process** managed by the architect over the life of the project. Software evolves like cities.

> "Making the arrows point in the right direction." — Robert Martin

The right architecture is the linchpin for project success. The wrong one is a recipe for disaster. *(Carnegie Mellon SEI)*

---

## Product Quality Attributes

Architecture largely determines:
- Maintainability
- Extensibility
- Durability
- Scalability
- Robustness
- Performance

---

## Architecture Evolution Taxonomy

### Scope of software engineering (background)
`Monolith/Ball of Mud` → `Library` / `Framework` → `Layered` → `Component-based`

### Scope of this lecture (what πChess will implement)
| Style | Description |
|-------|-------------|
| Extendable | Plug-in mechanism |
| Persistent | Layered with database |
| Distributed | Separate deployable units |
| Scalable | Multiple replicated instances |

### Communication patterns covered
- Synchronous / Local
- Synchronous / Remote
- Asynchronous / Local
- Asynchronous / Remote

### Beyond this lecture (awareness only)
Product Line, Product Family, Generative Architecture, Enterprise Architecture.

---

## 14-Phase Lecture Plan

| Phase | Focus |
|-------|-------|
| 1 | Monolith |
| 2 | Functional Style |
| 3 | Parser / JSON |
| 4 | HTTP / REST |
| 5 | Microservices |
| 6 | Persistence |
| 7 | Web UI |
| 8 | Performance |
| 9 | Bot / AI |
| 10 | Reactive |
| 11 | Kafka |
| 12 | Spark |
| 13 | Tournament |
| 14 | Final Presentation |

---

## Tools for This Project

- **Claude Code** — use heavily for AI-assisted development
- **sbt** — build tool
- **ScalaTest** — 100% test coverage enforced
- **Scoverage** — coverage reporting
- **Lichess** — open-source Scala chess; domain model reference

---

## Role: Software Architect

Responsibilities:
1. Understand the given architecture
2. Analyse requirements and constraints for the next phase
3. Select languages, tools, and components
4. Develop target architecture for the next phase
5. Manage the transition
6. Document the architecture

---

## Summary

Software evolves. Architecture must be **planned for change**. The architect drives transitions from one architectural style to the next — not once, but repeatedly throughout the project.
