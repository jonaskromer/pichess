package chess.model.board

import chess.model.piece.Piece

/** Read-only view of a board's piece placement: the twelve piece
  * bitboards, the cached colour/occupancy aggregates, and square
  * queries.
  *
  * Implemented by the immutable [[BoardState]] (the public/codec/view
  * type) AND by the search-internal mutable board used for copy-make, so
  * the hot readers — NNUE accumulator diff, HCE feature extraction,
  * move generation, Zobrist hashing, check detection — can read either
  * representation without re-materialising an immutable board per node.
  *
  * The search only ever passes the mutable implementation here, and the
  * public/from-scratch paths only ever pass [[BoardState]], so each hot
  * call site stays monomorphic (the JIT inlines the accessor). */
trait BoardLike:
  def pawnsW: Bitboard
  def knightsW: Bitboard
  def bishopsW: Bitboard
  def rooksW: Bitboard
  def queensW: Bitboard
  def kingW: Bitboard
  def pawnsB: Bitboard
  def knightsB: Bitboard
  def bishopsB: Bitboard
  def rooksB: Bitboard
  def queensB: Bitboard
  def kingB: Bitboard

  def whitePieces: Bitboard
  def blackPieces: Bitboard
  def occupancy: Bitboard

  def get(pos: Position): Option[Piece]
  def contains(pos: Position): Boolean
