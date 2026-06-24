package chess.analytics

/** Pure classification of a SAN move into the feature counters the dashboard
  * tracks. Standard SAN notation: `x` capture, `+` check, `#` checkmate, `=`
  * promotion, `O-O`/`O-O-O` castling. */
object MoveFeatures:

  def isCapture(san: String): Boolean   = san.contains('x')
  def isCheck(san: String): Boolean     = san.contains('+')
  def isCheckmate(san: String): Boolean = san.contains('#')
  def isPromotion(san: String): Boolean = san.contains('=')

  def isKingsideCastle(san: String): Boolean =
    san == "O-O" || san == "0-0"
  def isQueensideCastle(san: String): Boolean =
    san == "O-O-O" || san == "0-0-0"
