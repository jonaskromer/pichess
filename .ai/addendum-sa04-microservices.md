# Addendum: SA-04 — Microservices and REST

> Source: `SA-04-Microservices.pdf`

---

## Part 1: Microservices

### Monolith vs Microservices

We previously developed a layered, component-based architecture with Dependency Injection.
- Components can be replaced at compile time.
- However, we had one deployment unit (a Monolith).
- **Goal**: Deploy each component individually.

### 9 Characteristics of Microservices

| # | Characteristic | Description |
|---|---------------|-------------|
| 1 | **Componentization via services** | Independent processes communicating via IPC instead of in-memory libraries. |
| 2 | **Organized around business capabilities** | Vertical teams owning a domain area (UI -> DB) rather than horizontal tech layers. |
| 3 | **Products not projects** | "You build it, you run it" — teams own the product over its full lifetime. |
| 4 | **Smart endpoints and dumb pipes** | Logic lives in services, REST/messaging is just transport. No complex ESB. |
| 5 | **Decentralized governance** | Teams choose the best technology (language/stack) for their specific service. |
| 6 | **Decentralized data management** | Polyglot persistence. Each service manages its own database. |
| 7 | **Infrastructure automation** | Heavy reliance on automated testing, CI/CD, and monitoring. |
| 8 | **Design for failure** | Real-world failure is assumed. Emphasizes real-time monitoring and resilience. |
| 9 | **Evolutionary design** | Expect services to be routinely scrapped and replaced. |

### Library vs Service

| | Library | Service |
|---|---------|---------|
| **Deployment** | Multiple libraries part of one process | Runs in its own process |
| **Communication** | In-Memory calls | Inter-Process Communication (e.g., HTTP Web-Services) |
| **Lifecycle** | Replaced & Upgradable at compile time | Independently replaceable & upgradable |

### Advantages

| Monolith | Microservices |
|----------|---------------|
| Simplicity | Partial deployment |
| Consistency | Availability |
| Inter-module refactoring | Preserve modularity |
| | Multiple platforms (polyglot) |
| | Versioned independently |

### Case Studies

#### Spotify
- High scale: 90 Teams, 600 developers, 1 Product.
- **810 Services actively running**.
- Highly decentralized micro-ownership (1.7 services per developer).

#### Netflix
- Employs **The Simian Army** (e.g., Chaos Monkey) for "Design for Failure".
- Deliberately induces delays, errors, and terminates production instances randomly to ensure fault tolerance.
- Handles billions of requests per day and a petabyte of data.

### SBT Multi-Project Structure

SBT supports multiple projects to divide a monolithic repository into separate deployable modules.

```scala
lazy val root = (project in file("."))
  .aggregate(util, core)

lazy val util = (project in file("util"))
lazy val core = (project in file("core"))
```

---

## Part 2: REST

### Distributed Communication

#### Within one language/system
- **Sockets**: Bidirectional IPC flow using TCP (reliable) or UDP (datagrams). Defined by IP address and port.
- **RMI (Remote Method Invocation)**: Built-in Java mechanism. Uses normal method call semantics plus setup and exception handling. Limited to Java contexts.
- **EJB (Enterprise JavaBeans)**: Server-side components deployed in Application Servers (WebLogic, Glassfish) mapping to a Three Tier Architecture.

#### Between different languages/systems
RMI falls short for loose coupling between separate corporations or different languages. For this, we use:
- **CORBA**
- **SOAP**
- **REST**

### Web Services & SOAP (Legacy)

Web services provide access to machine readable data (usually XML or JSON). Early implementations used:
- **UDDI**: XML-based standard for discovering services.
- **WSDL**: XML-based interface description language.
- **SOAP**: Messaging protocol specification for exchanging structured information (heavy XML envelope syntax).

### REST — REpresentational State Transfer

Defined by Roy Fielding in 2000, REST is the dominant modern architecture style for web application APIs.
- Built organically around the **HTTP protocol**.

#### Client-Server
- **Clients**: Initiate requests directly using HTTP. Address objects using URIs.
- **Servers**: Respond to requests with XML, JSON, HTML, etc.
- **Stateless**: Servers do *not* know the state of the client.

#### REST → CRUD Mapping
REST maps elegantly to CRUD operations using HTTP verbs:
| HTTP Verb | CRUD |
|-----------|------|
| `POST` | Create |
| `GET` | Read |
| `PUT` | Update |
| `DELETE` | Delete |

### URL Design Guidelines

1. **Two URLs per resource type**: One for the collection, one for instances.
2. **Nouns, not verbs**: Name resources conceptually.
3. **Plural vs Singular**: Use plural for the collection, singular for the instance.
4. **Parameters**: Put everything else behind the `?` query string.

### Web Frameworks

#### Play Framework
Play makes it very easy to develop REST APIs via a specific `routes` file mechanism:
```
GET     /user/{id}    Application.user
POST    /user/        Application.createUser
```

#### Akka HTTP
A library to handle HTTP directly on the client and server side.
- Provides a powerful high-level **Routing DSL** to express routing tables in Scala code based on URL paths and HTTP verbs:
```scala
val route =
  path("hello") {
    get {
      complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello</h1>"))
    }
  }
```

---

## Reference Reading
- "Web API Design: Crafting Interfaces that Developers Love" — Brian Mulloy (Apigee eBook)

---

## Task 4

1. Split up the Project into Modules.
2. Develop a REST Service for your game using **Http4S**.
3. Create a Web UI using this REST-Service.
