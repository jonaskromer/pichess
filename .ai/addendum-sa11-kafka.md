# Addendum: SA-11 — Kafka

> Source: `docs/slides/SA-11-Kafka.pdf`

---

## Event-Driven Architecture

In sprawling Deep Microservice arrays, point-to-point connections create an unmanageable mesh of HTTP or REST webcalls. The solution is an **Event-Driven Architecture**: rather than services connecting to each other, they connect to a central, high-throughput Event Bus.

**Popular Technologies:**
- RabbitMQ
- ActiveMQ
- Redis
- **Kafka**

---

## Apache Kafka

Originally developed by LinkedIn in Scala/Java (Open Sourced in 2011).

**Core Hierarchy**:
1. **Events**: The fundamental abstract record inside Kafka, consisting of a key, a value, a timestamp, and metadata.
2. **Topics**: The named channels where Events are durably stored. Unlike RabbitMQ, Kafka **never deletes** events immediately upon read. They persist for a configured retention period and can feed multiple parallel subscribers independently.
3. **Partitions & Brokers**: Topics partition across multiple physical Brokers. Events sharing a single Key map to the same partition, guaranteeing chronological sequential order.

---

## Core Kafka APIs

- **Producer**: Writes events to Kafka.
- **Consumer**: Subscribes to read events from Kafka.
- **Streams**: Real-time stream processing native to Kafka.
- **Connect**: Bridge API connecting legacy external DBs.

### Kafka Development Stack
Normally, Kafka orchestrates heavily alongside **ZooKeeper** (serving configuration, cluster tracking, and topic membership). In local development, the full system is spun up via Docker-Compose images bridging Zookeeper and Kafka.

---

## Scala Examples

### Producer
```scala
val props = new Properties()
props.put("bootstrap.servers", "localhost:9092")
// Specify string serializers...

val producer = new KafkaProducer[String, String](props)
val record = new ProducerRecord("mytopic", "mykey", "Hello!")
producer.send(record)
producer.close()
```

### Consumer
```scala
val props = new Properties()
props.put("bootstrap.servers", "localhost:9092")
props.put("group.id", "mygroup")
// Specify string deserializers...

val consumer = new KafkaConsumer[String, String](props)
consumer.subscribe(util.Collections.singletonList("mytopic"))

while(true) {
  val records = consumer.poll(100)
  for (record <- records.asScala) println(record)
}
```

---

## Task 11

1. Write a Producer and Consumer in Kafka.
2. Utilize the provided Docker stack to host Kafka locally.
3. Connect Kafka to the existing Micro Services via the Akka Data Stream built previously.
