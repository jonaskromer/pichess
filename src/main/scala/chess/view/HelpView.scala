package chess.view

object HelpView:
  def render: String =
    """|=== πChess Help ===
       |
       |COMMANDS
       |  <from> <to>   Move a piece  (e.g. e2 e4)
       |  flip          Flip the board (toggle White/Black perspective)
       |  help          Show this help screen
       |  quit          Exit the game
       |
       |MOVE NOTATION
       |  Both coordinate and Standard Algebraic Notation (SAN) are accepted.
       |  See docs/notation.md for the full guide.
       |
       |  Coordinate:      e2 e4   e2e4   e2-e4   e7 e8=Q  (source then destination)
       |  Pawn push:       e4      d5
       |  Pawn capture:    exd5    cxb4
       |  Piece move:      Nf3     Bc4    Rd1    Qd8    Ke2
       |  Piece capture:   Nxf3    Bxc6
       |  Disambiguation:  Nbd2    N1f3   Raxd5  (file, rank, or both)
       |  Promotion:       e8=Q    exd8=R  (=Q, =R, =B, or =N)
       |  Castling:        O-O (kingside)   O-O-O (queenside)
       |  Check/mate:      Nf3+    Qxf7#  (accepted and ignored)
       |  Piece letters:   N=Knight  B=Bishop  R=Rook  Q=Queen  K=King
       |
       |IMPLEMENTED RULES
       |  Pawn    — one square forward; two squares from starting rank;
       |            diagonal capture; en passant; promotion on back rank
       |  Rook    — any distance horizontally or vertically; blocked by pieces
       |  Bishop  — any distance diagonally; blocked by pieces
       |  Queen   — any distance in any direction; blocked by pieces
       |  Knight  — L-shape (2+1 squares); jumps over pieces
       |  King    — one square in any direction; castling (O-O / O-O-O)
       |  Check   — moves leaving own king in check are rejected;
       |            checked king is highlighted in both TUI and GUI
       |  Castling — king moves two squares toward rook; rook jumps over;
       |             requires: neither piece moved, path clear, no check
       |  Turn order: White moves first, then alternates
       |
       |NOT YET IMPLEMENTED
       |  Checkmate        (game does not end on checkmate)
       |  Stalemate        (game does not end on stalemate)
       |  Draw conditions  (50-move rule, threefold repetition, insufficient material)
       |""".stripMargin
