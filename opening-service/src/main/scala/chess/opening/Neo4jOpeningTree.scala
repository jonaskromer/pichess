package chess.opening

import org.neo4j.driver.{Driver, Query}
import zio.*

import scala.jdk.CollectionConverters.*

/** Neo4j-backed [[OpeningTree]] implementation. Each `recordMove` is one
  * idempotent MERGE that creates the position nodes if needed and either
  * sets `count = 1` (new edge) or increments `count` (existing edge).
  */
final class Neo4jOpeningTree(driver: Driver) extends OpeningTree:

  private val UpsertCypher = """
    MERGE (b:Position {fen: $beforeFen})
    MERGE (a:Position {fen: $afterFen})
    MERGE (b)-[m:MOVE {san: $san}]->(a)
      ON CREATE SET m.count = 1
      ON MATCH  SET m.count = m.count + 1
  """

  def recordMove(
      beforeFen: String,
      san: String,
      afterFen: String
  ): Task[Unit] =
    val params = Map[String, AnyRef](
      "beforeFen" -> beforeFen,
      "afterFen"  -> afterFen,
      "san"       -> san
    ).asJava
    val query = Query(UpsertCypher, params)

    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.attempt(driver.session()))(s =>
          ZIO.attempt(s.close()).orDie
        )
        .flatMap(session =>
          ZIO.attempt(session.run(query).consume()).unit
        )
    }

object Neo4jOpeningTree:
  val layer: URLayer[Driver, OpeningTree] =
    ZLayer.fromFunction(Neo4jOpeningTree(_))
