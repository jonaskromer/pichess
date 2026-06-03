package chess.gameservice

import chess.api.{AnnotationsDto, BoardStateDto, WebBoardView}
import chess.model.board.{GameState, Position}
import chess.model.piece.Color
import chess.model.rules.MoveValidator
import chess.model.{GameError, GameId, SessionState}
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusException}
import pichess.game_service.StateReply
import zio.UIO
import zio.ZIO

/** Pure mapping helpers for the gRPC service.
  *
  * Extracted out of [[GrpcServer]] so the GameError → Status mapping and
  * SessionState → StateReply projection are unit-testable independent of a
  * running gRPC channel. GrpcServer itself stays behind a coverage
  * exclusion (the rpc methods need an actual stub to drive).
  */
object GrpcMappers:

  /** Project a SessionState into the wire reply. The DTO is built here
    * (server-side) using [[WebBoardView.toDto]] — which used to live in
    * the gateway — and serialised via [[BoardStateDto.protobufCodec]].
    * The gateway just decodes the bytes back into the same DTO and
    * forwards it as JSON; no more FEN-parse round-trip on the reply
    * path.
    *
    * The SAN log is read from the pre-computed
    * [[chess.model.GameSnapshot.moveLog]] field — incrementally
    * maintained by `recordMove` / `undoOnce` / `redoOnce` and seeded by
    * `fromHistory` on load — so this projection avoids re-walking the
    * history through `SanSerializer.deriveMoveLog` on every reply.
    */
  def toStateReply(
      gameId: GameId,
      session: SessionState
  ): UIO[StateReply] =
    buildAnnotations(session.state).map { annotations =>
      val dto = WebBoardView.toDto(
        state   = session.state,
        moveLog = session.game.moveLog,
        error   = session.error,
      )
      StateReply(
        gameId      = gameId,
        boardState  = encodeBoardState(dto),
        error       = session.error.getOrElse(""),
        fen         = chess.codec.FenSerializer.serialize(session.state),
        annotations = encodeAnnotations(annotations),
      )
    }

  /** Encode a [[BoardStateDto]] to the bytes carried by
    * [[StateReply.boardState]]. Delegates to [[BoardStateDto.encodeBytes]]
    * (boopickle binary codec — picked over zio-schema-protobuf after
    * the latter benched 33× slower on this DTO shape).
    */
  def encodeBoardState(dto: BoardStateDto): ByteString =
    ByteString.copyFrom(BoardStateDto.encodeBytes(dto))

  /** Same boopickle round-trip for the Phase 4 annotation sidecar. */
  def encodeAnnotations(dto: AnnotationsDto): ByteString =
    ByteString.copyFrom(AnnotationsDto.encodeBytes(dto))

  /** Build the full annotation bundle from a [[GameState]]: the
    * per-piece legal-destinations index plus the threats list and
    * attackers map. Used to populate [[StateReply.annotations]] so the
    * gateway can skip its FEN-parse + recompute path on cache miss.
    *
    * Cost is the [[MoveValidator.legalDestinationsIndex]] sweep
    * (~50-70 µs for a mid-game position per Phase 3 bench) plus a few
    * micros for the bitboard `isSquareAttacked` / `attackersOf` calls
    * — Phase 2 made those essentially free. Same arithmetic the
    * gateway used to do, just moved one hop upstream so the work
    * happens once per state change rather than once per cache miss.
    */
  def buildAnnotations(state: GameState): UIO[AnnotationsDto] =
    MoveValidator.legalDestinationsIndex(state).orDie.map { rawLegal =>
      val ownColor = state.activeColor
      val opponent =
        if ownColor == Color.White then Color.Black else Color.White
      val legalMap = rawLegal.map { case (src, dests) =>
        src.toString -> dests.map(_.toString)
      }
      // Mirror the gateway's old logic: a "threat" is an own-color
      // square currently attacked by the opponent.
      val ownSquares = ownPieceSquares(state, ownColor)
      val threats    = ownSquares.filter(sq =>
        MoveValidator.isSquareAttacked(state.board, sq, ownColor)
      )
      val attackerEntries = threats.map { sq =>
        sq.toString ->
          MoveValidator.attackersOf(state.board, sq, opponent).map(_.toString)
      }.toMap
      AnnotationsDto(
        legalMovesFrom = legalMap,
        threats        = threats.map(_.toString),
        attackersOf    = attackerEntries,
      )
    }

  /** Bitboard-driven iteration of every square holding an own-color
    * piece. Mirrors `MoveValidator.legalDestinationsIndex`'s active-
    * piece walk so threats / attackers reuse the same active-piece set. */
  private def ownPieceSquares(state: GameState, color: Color): List[Position] =
    val bb =
      if color == Color.White then state.board.whitePieces.raw
      else state.board.blackPieces.raw
    val buf = scala.collection.mutable.ListBuffer.empty[Position]
    var rem = bb
    while rem != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(rem)
      rem &= rem - 1L
      buf += Position(('a' + (idx % 8)).toChar, (idx / 8) + 1)
    buf.toList

  /** Lift a domain GameError into the gRPC status it should surface as.
    * The mapping is exhaustive across `GameError`'s variants — a new
    * variant becomes a `match`-not-exhaustive compile error here.
    */
  def toStatusException(err: GameError): StatusException =
    val status = err match
      case _: GameError.GameNotFound        => Status.NOT_FOUND
      case _: GameError.InvalidMove         => Status.INVALID_ARGUMENT
      case _: GameError.ParseError          => Status.INVALID_ARGUMENT
      case _: GameError.InfrastructureError => Status.INTERNAL
    new StatusException(status.withDescription(err.message))
