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
<div class="app">
  <div class="board-area">
    <div class="board-wrapper">
      <div class="rank-labels" id="rankLabels"></div>
      <div class="board" id="board"></div>
      <div class="file-labels" id="fileLabels"></div>
    </div>
  </div>
  <div class="sidebar">
    <h1 class="title">piChess</h1>
    <div class="turn-indicator" id="turnIndicator"></div>
    <div class="move-log-container">
      <h2 class="section-title">Moves</h2>
      <div class="move-log" id="moveLog"></div>
    </div>
    <div class="controls">
      <form id="moveForm" onsubmit="return submitMove()">
        <input type="text" id="moveInput" placeholder="e.g. e2e4 or Nf3" autocomplete="off" spellcheck="false">
        <button type="submit">Move</button>
      </form>
      <div class="btn-row">
        <button class="secondary-btn" onclick="undoMove()">Undo</button>
        <button class="secondary-btn" onclick="redoMove()">Redo</button>
      </div>
      <div class="btn-row">
        <button class="secondary-btn" onclick="newGame()">New Game</button>
        <button class="quit-btn" onclick="quitGame()">Quit</button>
      </div>
    </div>
    <div class="toast" id="toast"></div>
  </div>
</div>
<div class="promotion-overlay" id="promotionOverlay">
  <div class="promotion-dialog" id="promotionDialog"></div>
</div>
<script>
$javascript
</script>
</body>
</html>"""

  private val css: String = loadResource("web/style.css")

  private val javascript: String = loadResource("web/app.js")

  private def loadResource(path: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    val source = Source.fromInputStream(stream)
    try source.mkString
    finally source.close()
