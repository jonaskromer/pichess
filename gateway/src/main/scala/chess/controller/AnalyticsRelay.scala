package chess.controller

import zio.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

/** Bridges the Spark speed layer to the browser: consumes the `chess.analytics`
  * topic (per-game `GameSummary` JSON published by `spark-analytics`) and
  * fans each message out to all connected SSE clients via a [[Hub]].
  *
  * The gateway holds no analytics state — it is a pass-through relay. Mirrors
  * the zio-kafka consumer pattern in analytics-service's `KafkaAnalyticsConsumer`.
  * Started only when `KAFKA_BOOTSTRAP_SERVERS` is configured, so the gateway
  * still runs in Kafka-less setups (the SSE endpoint just stays silent).
  */
object AnalyticsRelay:

  val Topic: String = "chess.analytics"
  private val Group  = "pichess-gateway-analytics"

  /** A fresh scoped Kafka consumer. `latest` offset reset: the live panel only
    * cares about summaries produced while a client is watching.
    */
  def consumerLayer(bootstrapServers: String): ZLayer[Any, Throwable, Consumer] =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(bootstrapServers.split(',').toList.map(_.trim))
          .withGroupId(Group)
          .withProperty("auto.offset.reset", "latest")
      )
    )

  /** Drain the topic into `hub` forever; raw JSON values are relayed verbatim. */
  def run(hub: Hub[String]): ZIO[Consumer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics(Topic), Serde.string, Serde.string)
      .tap(record => hub.publish(record.value))
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .runDrain
