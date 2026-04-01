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

## Task 7

1. Use MongoDB to build a **second DB implementation** for the persistence layer.
2. Integrate it via the identically configured DAO Pattern you established for Slick, rendering the interface completely independent of whether MongoDB or Slick powers the backing mechanism.
