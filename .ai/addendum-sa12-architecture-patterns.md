# Addendum: SA-12 — Architecture Patterns

> Source: `docs/slides/SA-12-Architecture Patterns.pdf`

---

## Defining Architecture

> *"Architecture is about the important stuff. Whatever that is." — Martin Fowler*

Architecture represents the foundational decisions that are hard to change later. It is a constant balancing act between benefits and tradeoffs.

**Modern Changes in Architecture Landscape**:
- Agile Development
- Continuous Delivery & Deployment
- The DevOps Revolution (VMs, Docker, Configuration Management)
- Evolutionary Architectures

---

## Levels of Architecture

Architecture is evaluated across varying zoom levels:
- System Context -> System -> Module -> Component -> Class
- Enterprise Architecture
- Integration Architecture
- Application Architecture
- Component Design
- Implementation Design

---

## Core Architecture Patterns

1. **Layered Architecture**: Organizes applications into strict horizontal layers (e.g., Presentation, Business, Persistence). "Closed rules" dictate that a layer can only communicate directly with the layer immediately below it.
2. **Microkernel / Plug-in Architecture**: A core system (Microkernel) containing minimal logic, augmented by standalone Plug-in modules (e.g., Eclipse IDE).
3. **Pipes and Filters (Pipeline Architecture)**:
   - *Pipes*: Transport mechanisms for data (message bus, files, streams).
   - *Filters*: Transformations that map input datasets to an output format. 
   - Useful for processing datasets through multiple chained actions.
4. **Space-based Architecture**: Focuses strictly on extreme scalability. Caches and parallel processing grids dissolve the database bottleneck, handling applications with highly variable loads (e.g., flash sales).
5. **Microservices Architecture**: Decentralized, highly granular, independently deployable services organized around business domains. 
6. **Service-Oriented Architecture (SOA)**: Enterprise-scale service integration, often utilizing heavy middleware (ESBs) to coordinate disparate monolithic structures.
7. **Service-Based Architecture**: A middle-ground approach featuring a centralized database but separate, moderately sized domain services.

---

*(No explicit implementation Task provided for this lecture phase)*
