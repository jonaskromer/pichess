# Addendum: SA-07 — Slick

> Source: `docs/slides/SA-07-Slick.pdf`

---

## Data Access Architecture

### MVC and Architecture Styles
Classic MVC strictly separates the View, Controller, and Model. Modern applications cut architectures by features, technical layers, or physical distribution nodes (e.g., 3-tier client/server).

### DAO: Data Access Object Pattern
A DAO provides an abstract interface to a persistence mechanism, hiding database technology details and making the underlying DB interchangeable.
- The client code uses a DAO to fetch/store data without knowing SQL/NoSQL semantics.
- DAO implementations open, manage, and close connections.

---

## Object-Relational Impedance Mismatch

Relational databases are set-oriented (relations, tuples, predicate logic). Object-oriented languages are based on identity, encapsulation, and inheritance. 

Mapping one to the other is extremely problematic (once called "the Vietnam of Computer Science"). ORM frameworks (like Hibernate, JPA standard) attempt to close the gap but often introduce complex state management, mutability requirements (violating functional paradigms), and verbose QBE (Query by Example) or QBL (Query by Language/SQL strings) APIs.

---

## Slick

**Slick** stands for Scala Language-Integrated Connection Kit.
It is **NOT an ORM**. It is a **Functional-Relational Mapper** (FRM).

Instead of hiding the relational nature of the database behind mutable objects, Slick embraces the relational model. Using Scala collections logic, it maps table columns securely and provides a typesafe Scala collection-like API to generate predictable SQL.

### Key Features
- Write database queries in Scala instead of SQL strings.
- Out-of-the-box support for Postgres, MySQL, H2, SQLite, etc.
- No heavy XML or annotation configuration; configured entirely in Scala code.
- Composable queries.

---

## Defining a Slick Schema

Schemas are mapped identically to table relations using Scala classes extending `Table[T]`.

```scala
class Categories extends Table[(Int, String, Option[String])]("categories") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def description = column[Option[String]]("description")
  
  def * = (id, name, description)
}
```

Slick can automatically generate the DDL (`CREATE TABLE...`) statements directly from these definitions.

---

## Executing Slick Queries

Slick enables two types of functional query execution modes:
**Higher-order functions**:
```scala
val query = articles
  .filter(_.upvoteCount > 50)
  .filter(_.title like "%Scala%")
  .sortBy(_.date)
```

**For-comprehensions**:
```scala
val q2 = for {
    a <- articles if a.name like "%Scala%"
} yield (a.id)
```

To execute, map, update, or join relations, Slick executes queries inside a database session natively yielding Scala primitives and Option types over JDBC.

---

## Task 6

1. Develop a database layer.
2. Use the DAO Pattern to make the interface independent of the used DB.
3. Use **Slick** as the first DB implementation.
