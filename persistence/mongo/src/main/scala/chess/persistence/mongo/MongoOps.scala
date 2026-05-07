package chess.persistence.mongo

import org.reactivestreams.Publisher
import zio.*
import zio.interop.reactivestreams.*
import zio.stream.ZStream

/** Tiny adapters from the MongoDB Java driver's `Publisher[T]` results into
  * ZIO. The driver returns a `Publisher` for every operation — including
  * empty/single-result ones — so we centralise the conversion.
  */
object MongoOps:

  /** Drain a `Publisher` to a list. Use only when you know the result is
    * bounded (e.g. a single `find()` projected to a small set).
    */
  def toList[A](publisher: Publisher[A]): Task[List[A]] =
    publisher.toZIOStream().runCollect.map(_.toList)

  /** Run a `Publisher` whose result is at most one element. Returns `None` if
    * the publisher completed without emitting anything.
    */
  def headOption[A](publisher: Publisher[A]): Task[Option[A]] =
    publisher.toZIOStream().runHead

  /** Run a `Publisher` purely for its side effect, ignoring any emitted
    * values. Useful for `insertOne`, `replaceOne`, `deleteOne`, etc., whose
    * result envelopes (`InsertOneResult`, …) we don't currently inspect.
    */
  def runDiscard(publisher: Publisher[?]): Task[Unit] =
    publisher.toZIOStream().runDrain
