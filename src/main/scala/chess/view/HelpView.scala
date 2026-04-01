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
       |  Squares are written as column (a–h) followed by row (1–8).
       |  Enter the source square, a space, then the destination square.
       |  Example:  e2 e4  moves the piece on e2 to e4
       |            g1 f3  moves the piece on g1 to f3
       |
       |IMPLEMENTED RULES
       |  Pawn    — one square forward; two squares from starting rank;
       |            diagonal capture; en passant
       |  Rook    — any distance horizontally or vertically; blocked by pieces
       |  Bishop  — any distance diagonally; blocked by pieces
       |  Queen   — any distance in any direction; blocked by pieces
       |  Knight  — L-shape (2+1 squares); jumps over pieces
       |  King    — one square in any direction
       |  Turn order: White moves first, then alternates
       |
       |NOT YET IMPLEMENTED
       |  Castling         (kingside and queenside)
       |  Pawn promotion   (reaching the back rank)
       |  Check detection  (moves leaving own king in check are not rejected)
       |  Checkmate        (game does not end on checkmate)
       |  Stalemate        (game does not end on stalemate)
       |  Draw conditions  (50-move rule, threefold repetition, insufficient material)
       |""".stripMargin
