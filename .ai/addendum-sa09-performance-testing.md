# Addendum: SA-09 — Performance Testing with Gatling

> Source: `docs/slides/SA-09-Performance-Testing.pdf`

---

## Types of Testing Overview

1. **Unit Testing**: Tests individual methods/functions in isolation (code level).
2. **Integration Testing**: Tests interaction between unified units. Can be Black Box or White Box.
3. **System Testing**: The entire integrated system is tested. Focus shifts from purely functional to non-functional attributes like performance.
4. **Acceptance Testing**: The client verifies that all business requirements map to their domain usage effectively.

---

## Performance Testing

Determines system responsiveness and stability under a particular workload. Evaluates scalability, reliability, and resource consumption to identify system bottlenecks.

**Subtypes**:
- **Load Testing**: Behavior under *expected* concurrent user load acting within a set duration.
- **Stress Testing**: Finding the absolute capacity *limit* by escalating load until the system degrades or fails.
- **Volume Testing**: Subjecting software to gigantic volumes of underlying data (Flood testing).
- **Endurance Testing**: Protracting the load longitudinally over long continuous timelines (e.g., hours or days).
- **Spike Testing**: Suddenly increasing load dynamically to observe adaptive behavior and failure contingencies.

---

## Tooling: Gatling

An open-source load and performance testing framework based on Scala, Akka, and Netty.

1. Install Gatling (unzip).
2. Set browser proxy to `localhost:8000`.
3. Start your microservice.
4. Run the **Gatling Recorder** (`recorder.sh`).
5. Use your application. The actions establish a Recorded Simulation (`RecordedSimulation.scala`).
6. Run the simulation to view comprehensive reports of HTTP success/failure rates, latencies, and transaction metrics.

*(Note: JMeter is a highly popular legacy alternative to Gatling).*

---

## Performance Optimization Principles

Performance encompasses: Throughput, Response Time, Scalability, and Memory consumption.

> **"Premature optimization is the root of all evil."** — Donald Knuth

**Rules of Optimization:**
- **Measure First**: 90% of execution time is spent in 10% of the code. Only optimize after profiling the bottlenecks.
- **Alternatives**: Rely on Moore's law, hardware upgrades, compiler improvements (JIT), or superior libraries before mangling clean architectural code for manual speed.
- **Design Patterns for Tuning**:
  - **Flyweight**: Decreases memory consumption by sharing state.
  - **Prototype**: Reduces overhead by cloning instead of full instantiation.
  - **Proxy**: Defers resource-heavy operations until necessary.
  - **Caching**: Drastically limits DB load via main memory caching.

---

## Task 9

1. Generate a Performance-Test-Script using the Gatling Recorder.
2. Optimize the generated script manually.
3. Analyse the report and optimize your application code.
4. Repeat the performance test and document the improvement.
