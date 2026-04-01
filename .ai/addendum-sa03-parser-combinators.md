# Addendum: SA-03 — Parser Combinators

> Source: `docs/slides/SA-03-Parser.pdf`

---

## Internal vs External DSL

- **Internal DSL**: expressed within the host language (Scala) — uses Scala syntax, no separate parser needed
- **External DSL**: its own grammar and syntax — requires a dedicated parser, but is more expressive and readable to non-programmers

Parser combinators sit in between: the grammar is written in Scala, but it parses an external textual language.

---

## Dependency

```scala
"org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"
```

---

## Parser Base Classes

```scala
class MyParser extends Parsers      // full parser, requires manual tokenization
class MyParser extends RegexParsers // simpler; regex-based terminals, preferred
```

---

## Combinator Operator Reference

| Operator | Meaning |
|----------|---------|
| `p ~ q` | p followed by q; result is `p.result ~ q.result` |
| `p ~> q` | p followed by q; **keep only q's result** |
| `p <~ q` | p followed by q; **keep only p's result** |
| `p \| q` | alternation; first match wins |
| `p \|\|\| q` | alternation; longest match wins |
| `opt(p)` / `p?` | optional; returns `Option[T]` |
| `rep(p)` | zero or more; returns `List[T]` |
| `rep1(p)` | one or more; returns `List[T]` |
| `repsep(p, sep)` | p separated by sep; returns `List[T]` |

---

## Function Application Operators

| Operator | Meaning |
|----------|---------|
| `p ^^ f` | apply function `f` to result of `p` |
| `p ^^^ v` | replace result of `p` with constant `v` |
| `p into f` / `p >> f` | use result of `p` to choose the next parser |

---

## Regex Terminals

```scala
private def text: Parser[String]   = """[^\v]+""".r    ^^ (_.toString)
private def integer: Parser[Int]   = """\d+""".r        ^^ (_.toInt)
private def double: Parser[Double] = """\d+(\.\d+)?""".r ^^ (_.toDouble)
private def count: Parser[Int]     = opt(integer)        ^^ (_.getOrElse(1))
```

---

## Entry Points

```scala
parse(rule, input)    // allows unconsumed input at end
parseAll(rule, input) // FAILS if any input remains — use this for full-document parsing
```

---

## Return Type: Either[String, T]

```scala
def parseDSL(input: String): Either[String, Topic] =
  parseAll(topicParser, input) match {
    case Success(t, _)        => Right(t)
    case NoSuccess(msg, next) =>
      val pos = next.pos
      Left(s"[$pos] failed parsing: $msg\n\n${pos.longString}")
  }
```

---

## Worked Example: Bet Game DSL

```scala
case class Topic(eventName: String, topicName: String,
                 choiceCountPerPrediction: Int, choices: List[Choice])
case class Choice(id: Int, choiceName: String, payoutFactor: Double)
```

```scala
private def topicParser: Parser[Topic] =
  "Tipp-Abgabe:" ~ text ~
  "Frage:" ~ text ~
  "Wähle" ~ count ~ "aus" ~ choices ^^ {
    case _ ~ t ~ _ ~ n ~ _ ~ c ~ _ ~ ch => Topic(t, n, c, ch)
  }

// IMPORTANT: check multiNumberChoice before simpleChoice
// (to allow dots inside choice names)
private def choices: Parser[List[Choice]] =
  rep(multiNumberChoice | simpleChoice) ^^ (c => c.flatten)

private def multiNumberChoice: Parser[List[Choice]] =
  integer ~ ".." ~ integer ~ ">" ~ double ^^ {
    case from ~ _ ~ to ~ _ ~ f => from.to(to).map(n => Choice(n.toString, f)).toList
  }
```

---

## Post-Parse Transformer Pattern

Chain validation steps using `Either` + for-comprehension:

```scala
object TopicTransformer {
  def apply(result: Either[String, Topic]): Either[String, Topic] =
    for {
      parsed  <- result
      numbers <- checkChoiceNumber(parsed)   // returns Either[String, Topic]
      choices <- checkDuplicateChoices(numbers)
    } yield choices
}
```

Each validator returns `Right(topic)` on success or `Left("error message")` on failure.

---

## Task (SA-03)

Implement a **FEN / PGN parser** for chess using Scala Parser Combinators:
- Extend `RegexParsers`
- Return `Either[String, ParsedResult]` from the public API
- Apply the two-track (Either + for-comprehension) pattern for post-parse validation
