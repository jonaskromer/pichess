package chess.spark.scale

import chess.spark.schema.MoveEventRow

/** Deterministic synthetic-game generator for the scale demo — turns a game id
  * into a realistic-shaped run of [[MoveEventRow]]s (GameStarted, N MoveMade,
  * one terminal event with an outcome). No randomness, so a run is reproducible
  * and a given `numGames` always yields the same row count.
  */
object ScaleGen:

  private val Sans = Array(
    "e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7",
    "d4", "d5", "c4", "c5", "Nc3", "g6", "Bg7", "exd5", "Qxd5", "d6"
  )
  private val StartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val Outcomes  = Array("White", "Black", "Draw")
  private val Terminals = Array("GameEnded", "Forfeited")

  /** ~ 10–34 events per game (avg ~22): one start, 8..31 moves, one terminal. */
  def gameRows(gid: Long): List[MoveEventRow] =
    val id       = s"g$gid"
    val numMoves = 8 + (gid % 24).toInt
    val base     = 1700000000000L + gid * 100000L
    val started  = MoveEventRow(id, "GameStarted", "", StartFen, base, "")
    val moves = (0 until numMoves).toList.map { i =>
      val san = Sans(((gid + i) % Sans.length).toInt)
      MoveEventRow(id, "MoveMade", san, StartFen, base + (i + 1) * 1000L, "")
    }
    val terminal = MoveEventRow(
      id,
      Terminals((gid % 2).toInt),
      "",
      StartFen,
      base + (numMoves + 1) * 1000L,
      Outcomes((gid % 3).toInt)
    )
    started :: moves ::: List(terminal)
