package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.PieceType
import chess.model.rules.MoveValidator
import zio.*

object SanResolver extends NotationResolver:

  // Piece move: [NBRQK][disambig-file?][disambig-rank?][x?][dest][=promo?][+#?]
  private val piecePattern =
    """^([NBRQK])([a-h]?)([1-8]?)(x?)([a-h][1-8])(?:=[NBRQK])?[+#]?$""".r

  // Pawn capture: exd5 | exd8=Q | exd5+
  private val pawnCapPattern =
    """^([a-h])x([a-h][1-8])(?:=([NBRQ]))?[+#]?$""".r

  // Pawn push: e4 | e8=Q | e4+ | e4#
  private val pawnPushPattern =
    """^([a-h][1-8])(?:=([NBRQ]))?[+#]?$""".r

  private val pieceLetters: Map[String, PieceType] = Map(
    "N" -> PieceType.Knight,
    "B" -> PieceType.Bishop,
    "R" -> PieceType.Rook,
    "Q" -> PieceType.Queen,
    "K" -> PieceType.King
  )

  def parse(input: String, state: GameState): IO[GameError, Option[Move]] =
    input match
      case piecePattern(p, file, rank, _, dest) =>
        resolveSan(
          pieceLetters(p),
          pos(dest),
          Option(file).filter(_.nonEmpty).map(_.head),
          Option(rank).filter(_.nonEmpty).map(_.head - '0'),
          state
        ).map(Some(_))
      case pawnCapPattern(fromFile, dest, promo) =>
        resolveSan(
          PieceType.Pawn,
          pos(dest),
          Some(fromFile.head),
          None,
          state,
          promoType(promo)
        ).map(Some(_))
      case pawnPushPattern(dest, promo) =>
        resolveSan(
          PieceType.Pawn,
          pos(dest),
          None,
          None,
          state,
          promoType(promo)
        ).map(Some(_))
      case _ => ZIO.succeed(None)

  private def resolveSan(
      piece: PieceType,
      dest: Position,
      disambigFile: Option[Char],
      disambigRank: Option[Int],
      state: GameState,
      promotion: Option[PieceType] = None
  ): IO[GameError, Move] =
    val rawCandidates = state.board.toList.collect {
      case (from, p)
          if p.color == state.activeColor && p.pieceType == piece =>
        from
    }.filter { from =>
      disambigFile.forall(_ == from.col) &&
      disambigRank.forall(_ == from.row)
    }
    ZIO
      .filter(rawCandidates)(from =>
        MoveValidator
          .validate(state, Move(from, dest))
          .as(true)
          .catchAll(_ => ZIO.succeed(false))
      )
      .flatMap {
        case List(from) => ZIO.succeed(Move(from, dest, promotion))
        case Nil =>
          ZIO.fail(GameError.InvalidMove(s"No $piece can move to $dest"))
        case _ =>
          ZIO.fail(
            GameError.InvalidMove(
              s"Multiple ${piece}s can move to $dest, add a disambiguation character"
            )
          )
      }

  private def pos(s: String): Position = Position(s.head, s(1) - '0')

  private def promoType(s: String): Option[PieceType] =
    Option(s).flatMap(pieceLetters.get)
