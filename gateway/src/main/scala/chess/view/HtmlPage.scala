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
<link rel="icon" type="image/png" sizes="32x32" href="/web/peach-32.png">
<link rel="icon" type="image/png" sizes="192x192" href="/web/peach.png">
<link rel="apple-touch-icon" sizes="180x180" href="/web/peach-180.png">
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
