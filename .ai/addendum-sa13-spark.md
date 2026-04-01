# Addendum: SA-13 — Spark

> Source: `docs/slides/SA-13-Spark.pdf`

---

## Cluster-Computing Frameworks

Cluster frameworks (like Hadoop, Storm, Flink, and **Spark**) combine independent computers into unified systems for High Availability and High-Performance Computing.
- Originally modeled around Google's BigTable and **Map/Reduce** paradigm.
- Distributes "Shared-Nothing" data processing over worker nodes.
- **Spark** is a cluster-computing framework written natively in Scala (introduced in 2009). Its functional concepts (immutability, closures, higher-order functions) make Scala its most natural ecosystem.

---

## Spark Architecture

- **Driver Program**: The central controller coordinating the cluster.
- **Cluster Manager**: Distributes tasks (e.g., running native Spark, YARN, or Mesos).
- **Worker Nodes**: Physical or virtual nodes running Executors.
- **Executors**: Run the individual Tasks.
- **Tasks**: Parallelized chunks of the original Driver program.

---

## Core Data Structures: RDDs / Datasets

The core abstraction of Spark is the **Dataset** (historically: **RDD** - Resilient Distributed Dataset). 

To the Driver program, a Dataset looks identical to a standard Scala collection. However, Spark transparently distributes the dataset across the cluster's memory, ensuring fault tolerance and parallelized computation.

### Operations on Datasets

**1. Transformations (Lazy)**
These compute relationships but are NOT executed immediately:
- `map`, `flatMap`, `filter`, `distinct`, `sample`, `union`

**2. Actions (Eager)**
These enforce the actual computation (causing a "shuffle" across the cluster):
- `take`, `count`, `reduce`, `top`

---

## Example Spark Job (Pi Approximation)

```scala
import org.apache.spark.sql.SparkSession
import scala.math.random

object SparkPi {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("Spark Pi").config("spark.master", "local").getOrCreate()
    val slices = 2
    val n = 100000L * slices
    
    // Distribute computation over the cluster
    val count = spark.sparkContext.parallelize(1 until n.toInt, slices).map { i =>
      val x = random * 2 - 1
      val y = random * 2 - 1
      if (x*x + y*y <= 1) 1 else 0
    }.reduce(_ + _) // Action triggers computation
    
    println(s"Pi is roughly ${4.0 * count / (n - 1)}")
    spark.stop()
  }
}
```

---

## Spark Streaming

Spark also features native real-time streaming libraries that can directly connect as consumers to technologies like **Kafka**.
```scala
libraryDependencies += "org.apache.spark" % "spark-streaming-kafka-0-10_2.12" % "3.0.1"
```

---

## Task: Spark

1. Work with Spark and aggregate data from your application (e.g., count victories or analyze player behavior).
2. As a first step, read your source data from a simple file.
3. Then deploy **Spark Streaming** to connect directly to the Kafka bus and ingest your analytics data as a live stream.
