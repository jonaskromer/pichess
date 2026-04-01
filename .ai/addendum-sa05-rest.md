# Addendum: SA-05 — REST

> Source: `docs/slides/SA-05-REST.pdf`

---

## Distributed Communication Options

| Technology | Scope | Notes |
|------------|-------|-------|
| Sockets (TCP/UDP) | Any language | Raw bytes; low-level |
| RMI | Java only | Java object graph over network |
| EJB | Java only | Heavy enterprise Java |
| CORBA | Cross-language | IDL-based; complex |
| SOAP | Cross-language | XML; WSDL contracts; heavyweight |
| **REST** | Cross-language | HTTP-native; lightweight; **modern standard** |

---

## SOAP (Legacy Awareness)

- XML envelope (header + body)
- **WSDL**: machine-readable service contract
- **UDDI**: service registry/discovery
- Tightly coupled, verbose, hard to evolve — replaced by REST in most new systems

---

## REST — REpresentational State Transfer

Defined by Roy Fielding in his 2000 doctoral dissertation. REST uses HTTP as its native protocol.

**Key properties**:
- **Stateless**: every request is self-contained; no server-side session state
- **Resource-based**: every "thing" is a resource identified by URL
- **HTTP verbs express operations** — no action verbs in URLs

---

## REST → CRUD Mapping

| HTTP Method | CRUD | Semantics |
|-------------|------|-----------|
| `POST` | Create | Create new resource in collection |
| `GET` | Read | Retrieve resource or collection |
| `PUT` | Update | Replace resource (full update) |
| `DELETE` | Delete | Remove resource |

---

## URL Design Rules

1. **Nouns, not verbs** — `/games` not `/getGames`, not `/startGame`
2. **Plural for collections**: `/games`
3. **Singular instance via ID**: `/games/42`
4. **Two URLs per resource type**: `GET /games` (list) and `GET /games/{id}` (item)
5. **Hierarchy for sub-resources**: `/games/42/moves`

---

## Three-Tier Architecture

```
Client (browser / TUI)
        ↕  HTTP/REST
    Service Layer (REST API)
        ↕  internal
    Domain / Persistence
```

The REST API is a **view layer** — it calls domain services, it does not contain business logic.

---

## Akka HTTP — Routing DSL

```scala
import akka.http.scaladsl.server.Directives._

val route =
  path("games") {
    get {
      complete(/* ... */)
    } ~
    post {
      entity(as[String]) { body =>
        complete(/* ... */)
      }
    }
  } ~
  path("games" / IntNumber) { id =>
    get {
      complete(/* ... */)
    } ~
    delete {
      complete(/* ... */)
    }
  }
```

Match by `path(...)` and/or HTTP verb (`get`, `post`, `put`, `delete`).

---

## Task 4

1. Implement a **REST service** for πChess using **Akka HTTP**
2. Write it as a **view layer** (alongside the TUI — same domain, different interface)
3. This API will later be used for **Gatling performance testing**
4. Also add a **module-level REST API** to enable communication between **Docker instances** (microservices IPC)

---

## Reference

"Web API Design: Crafting Interfaces that Developers Love" — Brian Mulloy (Apigee eBook)
