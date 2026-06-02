# Addendum: SA-08 — MongoDB

> Source: `docs/slides/SA-08-MongoDB.pdf`

---

## NoSQL Concept

**NoSQL** (Not Only SQL) encompasses non-relational database technologies tailored to solve problems that traditional RDBMS struggle with:
- Immense datasets requiring high scalability.
- **Horizontal Scaling**: Expanding by adding more inexpensive hardware nodes rather than "bigger" hardware (vertical scaling).
- Flexible, dynamic, or non-existent schemas.
- Distribution models natively accommodating Cloud architectures.

---

## Document Databases

In a Document Store (like MongoDB), data is represented not by rigid table rows, but by collections of JSON-like documents.

**Characteristics**:
- Key-Value pairs inside nested graphs of data.
- Hierarchically related data can be embedded into one document, removing the need for costly SQL JOIN operations across multiple tables.
- Very high read scaling properties.

---

## Map/Reduce for Big Data

To process data horizontally across distributed workers, Document DBs leverage **Map/Reduce** (originally a Google methodology).
1. **Map**: Maps dependency relationships on data and distributes records to worker nodes.
2. **Reduce**: Aggregates the mapped outputs (e.g. summing total blog entries per author universally across distributed nodes).

---

## MongoDB Operations

The hierarchy within MongoDB is: `Database` -> `Collections` -> `Documents` (JSON/BSON objects).

**Core CLI Methods**:
- `use <somedb>` — switch to/create a database.
- `db.<collection>.insert(<document>)` — create a collection or add documents.
- `db.<collection>.find(<query>)` — retrieve documents.

---

## Mongo Scala Driver

Interacting with MongoDB functionally in Scala:

```scala
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "2.6.0"
```

```scala
val client: MongoClient = MongoClient()
val database: MongoDatabase = client.getDatabase("mydb")
val collection: MongoCollection[Document] = database.getCollection("mycoll")

// Insert is asynchronous, returning an Observable
val document: Document = Document("_id" -> 1, "x" -> 1)
val insertObservable: Observable[Completed] = collection.insertOne(document)
```

Because MongoDB's Scala driver natively utilizes `Observable` types, it functions exceptionally well inside Reactive architectures.

---

## Task 8 — Performance Testing + Benchmarking Loop

### k6 Test
1. Write a k6 script targeting your REST endpoints (e.g., game creation, move submission, game state retrieval).
2. Define reproducible thresholds:
   - `http_req_duration['p(95)'] < 500` — p95 latency under 500 ms
   - `http_req_failed < 0.01` — error rate below 1%
3. Fix VU count, duration, and seed data so runs are comparable across machines.

### Gatling Test
1. Write a Gatling simulation covering the same request scenarios.
2. Apply the same p95 latency and error-rate thresholds in the `assertions` block.
3. Produce a reproducible HTML report (fixed injection profile, no randomized think-times without a fixed seed).

### JMH Benchmark
1. Add a JMH benchmark for a hot function in the codebase — good candidates: FEN string parsing, move serialization/deserialization, game-state validation.
2. Annotate with `@Benchmark`, `@BenchmarkMode(Mode.AverageTime)`, `@OutputTimeUnit(TimeUnit.MICROSECONDS)`.

### Baseline → Optimize → Rerun
1. Run all three tools and record baseline numbers.
2. Identify the primary bottleneck from the reports (e.g., N+1 DB queries, unnecessary allocations, synchronous blocking in an async path).
3. Apply a targeted optimization.
4. Rerun and compare against baseline.

### Deliverables
- **k6 summary** + a short note: what the bottleneck was and what fix was applied.
- **Gatling summary** + a short note: what the bottleneck was and what fix was applied.
- **JMH before/after numbers** showing the measured improvement.
