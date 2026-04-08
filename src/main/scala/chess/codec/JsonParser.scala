package chess.codec

import chess.model.board.{Board, CastlingRights, GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.rules.MoveValidator

object JsonParser:

  def parse(input: String): Either[String, GameState] =
    JReader(input).readValue().flatMap(fromJson)

  private def fromJson(json: JValue): Either[String, GameState] = json match
    case JValue.JObject(fields) =>
      for
        board   <- fields.get("board").toRight("Missing 'board'").flatMap(parseBoard)
        color   <- fields.get("activeColor").toRight("Missing 'activeColor'").flatMap(asString("activeColor")).flatMap(parseColorString)
        rights  <- fields.get("castlingRights").toRight("Missing 'castlingRights'").flatMap(parseCastling)
        ep      <- parseEnPassant(fields.get("enPassantTarget"))
        status  <- fields.get("status").toRight("Missing 'status'").flatMap(parseStatus)
      yield GameState(
        board = board,
        activeColor = color,
        enPassantTarget = ep,
        inCheck = MoveValidator.isInCheck(board, color),
        castlingRights = rights,
        status = status
      )
    case _ => Left("Expected JSON object at top level")

  private def parseBoard(json: JValue): Either[String, Board] = json match
    case JValue.JObject(fields) =>
      fields.foldLeft[Either[String, Board]](Right(Map.empty)) { case (acc, (key, value)) =>
        for
          board <- acc
          pos   <- parsePosition(key)
          piece <- parsePiece(value)
        yield board + (pos -> piece)
      }
    case _ => Left("Expected object for 'board'")

  private def parsePosition(s: String): Either[String, Position] =
    if s.length == 2 && s(0) >= 'a' && s(0) <= 'h' && s(1) >= '1' && s(1) <= '8'
    then Right(Position(s(0), s(1).asDigit))
    else Left(s"Invalid position '$s'")

  private def parsePiece(json: JValue): Either[String, Piece] = json match
    case JValue.JString(s) =>
      s.split(' ') match
        case Array(colorStr, typeStr) =>
          for
            color <- parseColorString(colorStr)
            pt    <- parsePieceType(typeStr)
          yield Piece(color, pt)
        case _ => Left(s"Invalid piece '$s', expected 'color type'")
    case _ => Left("Expected string for piece")

  private def asString(field: String)(json: JValue): Either[String, String] = json match
    case JValue.JString(s) => Right(s)
    case _                 => Left(s"Expected string for '$field'")

  private def parseColorString(s: String): Either[String, Color] = s match
    case "white" => Right(Color.White)
    case "black" => Right(Color.Black)
    case _       => Left(s"Invalid color '$s'")

  private def parsePieceType(s: String): Either[String, PieceType] = s match
    case "king"   => Right(PieceType.King)
    case "queen"  => Right(PieceType.Queen)
    case "rook"   => Right(PieceType.Rook)
    case "bishop" => Right(PieceType.Bishop)
    case "knight" => Right(PieceType.Knight)
    case "pawn"   => Right(PieceType.Pawn)
    case _        => Left(s"Invalid piece type '$s'")

  private def parseCastling(json: JValue): Either[String, CastlingRights] = json match
    case JValue.JObject(fields) =>
      for
        wk <- getBool(fields, "whiteKingSide")
        wq <- getBool(fields, "whiteQueenSide")
        bk <- getBool(fields, "blackKingSide")
        bq <- getBool(fields, "blackQueenSide")
      yield CastlingRights(wk, wq, bk, bq)
    case _ => Left("Expected object for 'castlingRights'")

  private def getBool(fields: Map[String, JValue], key: String): Either[String, Boolean] =
    fields.get(key) match
      case Some(JValue.JBool(b)) => Right(b)
      case Some(_)               => Left(s"Expected boolean for '$key'")
      case None                  => Left(s"Missing '$key'")

  private def parseEnPassant(json: Option[JValue]): Either[String, Option[Position]] = json match
    case Some(JValue.JNull) | None   => Right(None)
    case Some(JValue.JString(s))     => parsePosition(s).map(Some(_))
    case _                           => Left("Invalid en passant target")

  private def parseStatus(json: JValue): Either[String, GameStatus] = json match
    case JValue.JString("playing") => Right(GameStatus.Playing)
    case JValue.JObject(fields) =>
      fields.get("checkmate") match
        case Some(JValue.JString(c)) => parseColorString(c).map(GameStatus.Checkmate(_))
        case _                       => Left("Invalid status object")
    case _ => Left("Invalid status")

  // -- Minimal JSON AST and recursive-descent reader --

  private[codec] enum JValue:
    case JObject(fields: Map[String, JValue])
    case JString(value: String)
    case JBool(value: Boolean)
    case JNull

  private class JReader(input: String):
    private var pos = 0

    def readValue(): Either[String, JValue] =
      skipWhitespace()
      if pos >= input.length then Left("Unexpected end of input")
      else
        input(pos) match
          case '{' => readObject()
          case '"' => readString().map(JValue.JString(_))
          case 't' => readLiteral("true", JValue.JBool(true))
          case 'f' => readLiteral("false", JValue.JBool(false))
          case 'n' => readLiteral("null", JValue.JNull)
          case c   => Left(s"Unexpected character '$c' at position $pos")

    private def readObject(): Either[String, JValue.JObject] =
      pos += 1 // skip '{'
      skipWhitespace()
      if pos < input.length && input(pos) == '}' then
        pos += 1
        Right(JValue.JObject(Map.empty))
      else
        readPairs(Map.empty)

    private def readPairs(acc: Map[String, JValue]): Either[String, JValue.JObject] =
      for
        key   <- { skipWhitespace(); readString() }
        _     <- { skipWhitespace(); expect(':') }
        value <- readValue()
        result <- {
          val updated = acc + (key -> value)
          skipWhitespace()
          if pos < input.length && input(pos) == ',' then
            pos += 1
            readPairs(updated)
          else if pos < input.length && input(pos) == '}' then
            pos += 1
            Right(JValue.JObject(updated))
          else Left(s"Expected ',' or '}' at position $pos")
        }: Either[String, JValue.JObject]
      yield result

    private def readString(): Either[String, String] =
      if pos >= input.length || input(pos) != '"' then Left(s"Expected '\"' at position $pos")
      else
        pos += 1
        val sb = StringBuilder()
        while pos < input.length && input(pos) != '"' do
          if input(pos) == '\\' then
            pos += 1
            if pos >= input.length then return Left("Unexpected end in string escape")
            input(pos) match
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case 'n'  => sb.append('\n')
              case 't'  => sb.append('\t')
              case c    => sb.append('\\').append(c)
            pos += 1
          else
            sb.append(input(pos))
            pos += 1
        if pos >= input.length then Left("Unterminated string")
        else
          pos += 1 // skip closing '"'
          Right(sb.toString)

    private def readLiteral(expected: String, value: JValue): Either[String, JValue] =
      if input.startsWith(expected, pos) then
        pos += expected.length
        Right(value)
      else Left(s"Expected '$expected' at position $pos")

    private def expect(c: Char): Either[String, Unit] =
      if pos < input.length && input(pos) == c then
        pos += 1
        Right(())
      else Left(s"Expected '$c' at position $pos")

    private def skipWhitespace(): Unit =
      while pos < input.length && " \t\r\n".contains(input(pos)) do pos += 1
