package chess.model

opaque type InviteCode = String

object InviteCode:
  /** Length of generated codes. Six characters of [A-Z2-9] gives ~32^6 ≈ 1B
    * permutations — wide enough that a uniform random pick is collision-safe
    * for the lobby cardinality we care about.
    */
  val Length: Int = 6

  private val Alphabet: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

  def apply(raw: String): Option[InviteCode] =
    val normalized = raw.trim.toUpperCase
    Option.when(
      normalized.length == Length && normalized.forall(Alphabet.contains)
    )(normalized)

  def unsafe(raw: String): InviteCode = raw

  extension (code: InviteCode) def value: String = code

  /** Pick `Length` characters uniformly from `Alphabet`. Caller threads in the
    * source of randomness so the function stays pure and testable.
    */
  def fromRandom(nextInt: Int => Int): InviteCode =
    val sb = new StringBuilder(Length)
    var i = 0
    while i < Length do
      sb.append(Alphabet.charAt(nextInt(Alphabet.length)))
      i += 1
    sb.result()

  /** Same shape as [[fromRandom]] but driven by ZIO's `Random` service so it
    * can be controlled from tests via `TestRandom`.
    */
  val random: zio.UIO[InviteCode] =
    val draw = zio.Random.nextIntBounded(Alphabet.length).map(Alphabet.charAt)
    zio.ZIO
      .foreach((0 until Length).toList)(_ => draw)
      .map(_.mkString)
