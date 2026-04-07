# Addendum: SA-03 — Internal and External Languages

> Source: `SA-03-InternalExternalLang.pdf`

---

## Internal vs External DSLs

- **Internal DSL**: Expressed within a host language (e.g., Scala). Leverages host language syntax and tooling. Supported by IDEs directly.
- **External DSL**: Independent of any language environment. Has its own grammar and requires a dedicated parser and custom editor support.

### Internal DSLs in Scala
Scala provides excellent tools to build internal DSLs (Generic Types, Operator Overloading, and Implicit Conversions).
Examples given in lecture:
- **Scala Spec**: Natural language testing assertions (`cell.value must be_==(0)`).
- **Scala Units**: Type-safe unit tracking (`val length: Length[Int] = (5 m) * 3`).
- **Scala.Scalar**: Type-directed equations (`300 * nmi / (1.5 * hr) == 200 * kn`).
- **Baysick**: BASIC programs acting as valid executable Scala code.
- **Music DSL**: Encoding music melodies via strings and variables.

---

## Implicit Conversions

Implicit conversions are heavily used to build intuitive syntax without polluting standard library definitions.

**Problem:** Standard objects don't know your domain rules (e.g., `4,25 $ + 3,75 €`).
**Solution:** `implicit def convertToEuro(value: Dollar): Euro {…}`

### Rules for Implicits
- Conversion methods must be explicitly marked as `implicit`.
- They must be in scope.
- They are applied *only* if the code cannot be compiled otherwise.
- Only exactly one valid conversion can be applied.
- The compiler will only apply one conversion step (no chaining).

### Examples

**Adding an `.acronym` method to `String`:**
```scala
class ExtendedString(str: String) {
  def acronym = str.toCharArray.foldLeft("") { (t, c) =>
    t + (if (c.isUpperCase) c.toString else "")
  }
}
implicit def string2ExtendedString(str: String) = new ExtendedString(str)
println("HyperText Transfer Protocol".acronym) // HTTP
```

**Adding a `!` (Factorial) method to `Int`:**
```scala
def fact(n: Int): BigInt = if (n <= 0) 1 else fact(n-1) * n

class Factorizer(n: Int) {
  def ! = fact(n)
}
implicit def int2fact(n: Int) = new Factorizer(n)
println("10! = " + (10!)) // 3628800
```

---

## Extendable Language Philosophy

Guy Steele ("Growing a Language") argues that languages must grow via:
- Generic Types
- Operator Overloading
- Community-built libraries
Scala is highly extendable, allowing DSLs and custom logic to look indistinguishable from core language constructs.

---

## Parser Combinators for External DSLs

Scala Parser Combinators acts as an *internal DSL* used for creating parsers that read *external DSLs*.

### Dependency

```scala
"org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"
```

### Parser Base Classes

```scala
class MyParser extends Parsers      // full parser, requires manual tokenization
class MyParser extends RegexParsers // regex-based terminals, preferred
```

### Combinator Operator Reference

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

## Task 3: Parser

Build 3 Parsers using different libraries/techniques:
1. **ParserCombinators**
2. **FastParse**
3. **regex** (no library)

Use AI tools to generate the code. Guide the generation carefully and read the code and the tests. Bring test coverage to (or as close as possible to) 100%.
