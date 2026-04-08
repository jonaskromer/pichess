package chess.view

object HelpView:
  def render: String =
    """|=== πChess Help ===
       |
       |COMMANDS
       |  <from> <to>          Move a piece  (e.g. e2 e4)
       |  load <FEN|PGN|JSON>  Load a game (format is auto-detected)
       |  export fen|pgn|json  Export the current game in the given format
       |  undo                 Undo the last move
       |  redo                 Redo the last undone move
       |  draw                 Claim a draw (50-move rule)
       |  flip                 Flip the board (toggle White/Black perspective)
       |  help                 Show this help screen
       |  quit                 Exit the game
       |
       |IMPORT / EXPORT
       |  The 'load' command accepts FEN, PGN, or JSON — the format is detected
       |  automatically. The 'export' command requires a format argument.
       |
       |  load rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1
       |  load 1. e4 e5 2. Nf3 Nc6 *
       |  load {"board": {...}, "activeColor": "white", ...}
       |  export fen
       |  export pgn
       |  export json
       |
       |FEN (FORSYTH-EDWARDS NOTATION)
       |  FEN encodes a complete board position as a single line of text.
       |
       |  Format:  <placement> <active> <castling> <en-passant> <halfmove> <fullmove>
       |
       |  Example: rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1
       |           (position after 1. e4)
       |
       |  Placement  — 8 ranks separated by '/', from rank 8 (top) to rank 1.
       |               Letters = pieces (KQRBNPkqrbnp), digits = empty squares.
       |               Uppercase = White, lowercase = Black.
       |  Active     — 'w' or 'b' (whose turn it is)
       |  Castling   — combination of K Q k q, or '-' for none
       |  En passant — target square (e.g. e3) or '-'
       |  Halfmove   — moves since last pawn push or capture (for 50-move rule)
       |  Fullmove   — incremented after Black's move (starts at 1)
       |
       |PGN (PORTABLE GAME NOTATION)
       |  PGN records a full game as a sequence of SAN moves.
       |
       |  Example: 1. e4 e5 2. Nf3 Nc6 *
       |
       |  Move numbers (1. 2. ...) and result tokens (1-0, 0-1, *) are handled.
       |  Comments in {braces} and NAG annotations ($1, $2) are ignored.
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
       |  Check/mate:      Nf3+    Qxf7#  (+ for check, # for checkmate)
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
       |  Checkmate — detected automatically; game ends with winner announced;
       |              # suffix appended to the mating move in SAN
       |  Stalemate — detected automatically; game drawn when the player to
       |              move has no legal move but is not in check
       |  50-move rule — claim a draw with 'draw' after 50 moves with no
       |                 pawn move or capture
       |  Insufficient material — game drawn automatically when neither side
       |                          can checkmate (K vs K, K+B vs K, K+N vs K,
       |                          K+B vs K+B same-colored bishops)
       |  Turn order: White moves first, then alternates
       |
       |NOT YET IMPLEMENTED
       |  Threefold repetition  (draw when same position occurs 3 times)
       |""".stripMargin
