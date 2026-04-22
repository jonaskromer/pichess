package chess.view

import scala.io.Source

object HtmlPage:

  def render: String =
    s"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>piChess</title>
<style>
$css
</style>
</head>
<body>
<div id="app"></div>
<script src="/web/main.js"></script>
</body>
</html>"""

  private val css: String = loadResource("web/style.css")

  private def loadResource(path: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    val source = Source.fromInputStream(stream)
    try source.mkString
    finally source.close()
