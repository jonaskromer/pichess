package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.PieceType
import chess.model.rules.MoveValidator

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

  def parse(input: String, state: GameState): Option[Either[GameError, Move]] =
    input match
      case piecePattern(p, file, rank, _, dest) =>
        Some(
          resolveSan(
            pieceLetters(p),
            pos(dest),
            Option(file).filter(_.nonEmpty).map(_.head),
            Option(rank).filter(_.nonEmpty).map(_.head - '0'),
            state
          )
        )
      case pawnCapPattern(fromFile, dest, promo) =>
        Some(
          resolveSan(
            PieceType.Pawn,
            pos(dest),
            Some(fromFile.head),
            None,
            state,
            promoType(promo)
          )
        )
      case pawnPushPattern(dest, promo) =>
        Some(
          resolveSan(
            PieceType.Pawn,
            pos(dest),
            None,
            None,
            state,
            promoType(promo)
          )
        )
      case _ => None

  private def resolveSan(
      piece: PieceType,
      dest: Position,
      disambigFile: Option[Char],
      disambigRank: Option[Int],
      state: GameState,
      promotion: Option[PieceType] = None
  ): Either[GameError, Move] =
    val candidates = state.board.toList
      .collect {
        case (from, p)
            if p.color == state.activeColor && p.pieceType == piece =>
          from
      }
      .filter { from =>
        disambigFile.forall(_ == from.col) &&
        disambigRank.forall(_ == from.row) &&
        MoveValidator.validate(state, Move(from, dest)).isRight
      }
    candidates match
      case List(from) => Right(Move(from, dest, promotion))
      case Nil =>
        Left(GameError.InvalidMove(s"No ${piece} can move to $dest"))
      case _ =>
        Left(
          GameError.InvalidMove(
            s"Ambiguous: multiple ${piece}s can reach $dest — add a disambiguation character"
          )
        )

  private def pos(s: String): Position = Position(s.head, s(1) - '0')

  private def promoType(s: String): Option[PieceType] =
    Option(s).flatMap(pieceLetters.get)
