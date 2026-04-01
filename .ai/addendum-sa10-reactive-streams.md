# Addendum: SA-10 — Reactive Streams

> Source: `docs/slides/SA-10-Reactive Streams.pdf`

---

## Lazy Evaluation and Sequences

Scala variable modifiers denote differing evaluation semantics:
- `var`: mutable.
- `val`: immutable, evaluated eagerly at instantiation.
- `def`: evaluated on every discrete call.
- `lazy val`: evaluated *only once*, specifically upon the first time the value is requested.

**Infinite Streams**
By combining `lazy` evaluation with recursion, Scala can represent algorithmically infinite sequences (e.g., Fibonacci numbers, primes). Memory is only consumed exactly up to the finite elements requested.

---

## Reactive Streams & Back Pressure

When communicating across asynchronous boundaries, rapid Producers can overwhelm slow Consumers, causing buffer overflows or massive memory spikes.

**Back Pressure**: The Subscriber signals upstream to the Publisher exactly how much volume it can currently process. The Publisher dynamically adapts its send rate, ensuring zero overflow.

**Key 4 Specification Concepts** (`reactive-streams-jvm`):
1. `Publisher`
2. `Subscriber`
3. `Subscription`
4. `Processor`

---

## Akka Streams

Akka Streams is a pristine implementation of the Reactive Streams specification built atop typed Actors. It elevates the low-level specification nomenclature to:
- **`Source[Out, Mat]`** (Publisher)
- **`Sink[In, Mat]`** (Subscriber)
- **`Flow[In, Out, Mat]`** (Processor)

### Stream Composition Matrix

Sources, Flows, and Sinks connect iteratively using `.via` and `.to`. 

```scala
val source = Source(1 to 1000)
val flow = Flow[Int].map(x => x * 2)
val sink = Sink.foreach[Int](println)

val graph = source.via(flow).to(sink)
graph.run() // Materializes and executes the graph
```

### Graph DSL

For non-linear routing, Akka Streams provides a Graph DSL allowing data processing paths to *fan-out* (via `Broadcast`, `Balance`) and *fan-in* (via `Merge`, `Zip`).

```scala
val graph = GraphDSL.create() { implicit builder =>
  import GraphDSL.Implicits._
  // Connections are drawn using the ~> operator
  A ~> B ~> C
       B ~> D ~> C
  ClosedShape
}
```

---

## Task 10

1. Create an Akka Stream inside the application structure.
2. Form a `Source` (keyboard, file, website, random generator, external DSL).
3. Draft one or more computational `Flow` components.
4. Output the result to a `Sink`.
