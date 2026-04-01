package chess.view

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

  private val css: String =
    """*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
  background: #2d1b14;
  color: #f5e6dc;
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
}
.app {
  display: flex;
  gap: 40px;
  padding: 30px;
  align-items: flex-start;
}
.board-area { display: flex; flex-direction: column; align-items: center; }
.board-wrapper {
  display: grid;
  grid-template-columns: 24px 1fr;
  grid-template-rows: 1fr 24px;
  gap: 0;
}
.rank-labels {
  display: flex;
  flex-direction: column;
  justify-content: space-around;
  align-items: center;
  width: 24px;
  font-size: 13px;
  color: #c4a08a;
  font-weight: 600;
}
.file-labels {
  grid-column: 2;
  display: flex;
  justify-content: space-around;
  align-items: center;
  height: 24px;
  font-size: 13px;
  color: #c4a08a;
  font-weight: 600;
}
.board {
  display: grid;
  grid-template-columns: repeat(8, 72px);
  grid-template-rows: repeat(8, 72px);
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0,0,0,0.5), 0 2px 8px rgba(0,0,0,0.3);
}
.square {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  cursor: default;
  position: relative;
  transition: background-color 0.15s ease;
  user-select: none;
  -webkit-user-select: none;
}
.square.light { background: #fde8d0; }
.square.dark { background: #e8956a; }
.square.drag-over.light { background: #fdd8b5; box-shadow: inset 0 0 0 3px rgba(200,120,80,0.5); }
.square.drag-over.dark { background: #d4804e; box-shadow: inset 0 0 0 3px rgba(200,120,80,0.5); }
.square.drag-source { opacity: 0.4; }
.square.last-move-from.light { background: #fcc89e; }
.square.last-move-from.dark { background: #d4804e; }
.square.last-move-to.light { background: #fcc89e; }
.square.last-move-to.dark { background: #d4804e; }
.piece {
  cursor: grab;
  line-height: 1;
  transition: transform 0.1s ease;
  pointer-events: auto;
}
.piece:active { cursor: grabbing; }
.piece.white-piece { color: #fff; text-shadow: 0 1px 3px rgba(0,0,0,0.6), 0 0 1px rgba(0,0,0,0.4); }
.piece.black-piece { color: #1a1a1a; text-shadow: 0 1px 2px rgba(255,255,255,0.15); }
.piece.dragging { transform: scale(1.15); opacity: 0.9; }
.sidebar {
  width: 280px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-top: 8px;
}
.title {
  font-size: 28px;
  font-weight: 700;
  color: #f7a072;
  letter-spacing: 1px;
}
.turn-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(247,160,114,0.1);
  border: 1px solid rgba(247,160,114,0.15);
}
.turn-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid rgba(247,160,114,0.3);
}
.turn-dot.white { background: #fff; box-shadow: 0 0 8px rgba(255,255,255,0.4); }
.turn-dot.black { background: #1a1a1a; border-color: rgba(255,255,255,0.4); }
.section-title { font-size: 13px; text-transform: uppercase; letter-spacing: 1.5px; color: #c4a08a; margin-bottom: 8px; }
.move-log-container { flex: 1; min-height: 0; }
.move-log {
  max-height: 340px;
  overflow-y: auto;
  font-family: 'SF Mono', 'Cascadia Code', 'Consolas', monospace;
  font-size: 14px;
  line-height: 1.8;
  padding: 10px;
  background: rgba(247,160,114,0.06);
  border-radius: 8px;
  border: 1px solid rgba(247,160,114,0.1);
}
.move-log::-webkit-scrollbar { width: 6px; }
.move-log::-webkit-scrollbar-thumb { background: rgba(247,160,114,0.25); border-radius: 3px; }
.move-row { display: flex; gap: 4px; }
.move-number { color: #c4a08a; min-width: 28px; text-align: right; margin-right: 6px; }
.move-san { padding: 1px 6px; border-radius: 3px; cursor: default; }
.move-san:hover { background: rgba(247,160,114,0.12); }
.controls { display: flex; flex-direction: column; gap: 10px; }
#moveForm { display: flex; gap: 8px; }
#moveInput {
  flex: 1;
  padding: 10px 14px;
  border: 2px solid rgba(247,160,114,0.2);
  border-radius: 8px;
  background: rgba(247,160,114,0.08);
  color: #f5e6dc;
  font-size: 15px;
  font-family: 'SF Mono', 'Cascadia Code', 'Consolas', monospace;
  outline: none;
  transition: border-color 0.2s;
}
#moveInput:focus { border-color: #f7a072; }
#moveInput::placeholder { color: #8a6a5a; }
#moveForm button {
  padding: 10px 18px;
  border: none;
  border-radius: 8px;
  background: #e8956a;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}
#moveForm button:hover { background: #f7a072; }
.btn-row { display: flex; gap: 8px; }
.secondary-btn {
  flex: 1;
  padding: 10px;
  border: 2px solid rgba(247,160,114,0.2);
  border-radius: 8px;
  background: transparent;
  color: #c4a08a;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}
.secondary-btn:hover { border-color: rgba(247,160,114,0.4); color: #f5e6dc; }
.quit-btn {
  flex: 1;
  padding: 10px;
  border: 2px solid rgba(180,80,60,0.3);
  border-radius: 8px;
  background: transparent;
  color: #b4503c;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}
.quit-btn:hover { border-color: rgba(180,80,60,0.6); background: rgba(180,80,60,0.1); color: #d4705c; }
.toast {
  position: fixed;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%) translateY(20px);
  background: #b4503c;
  color: #fff;
  padding: 12px 24px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.3s, transform 0.3s;
  pointer-events: none;
  z-index: 100;
  box-shadow: 0 4px 16px rgba(180,80,60,0.4);
}
.toast.visible { opacity: 1; transform: translateX(-50%) translateY(0); }
.promotion-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(45,27,20,0.8);
  display: none;
  justify-content: center;
  align-items: center;
  z-index: 200;
}
.promotion-overlay.visible { display: flex; }
.promotion-dialog {
  background: #3d2a20;
  border: 1px solid rgba(247,160,114,0.2);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  gap: 12px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.5);
}
.promotion-choice {
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, transform 0.1s;
  background: rgba(247,160,114,0.08);
}
.promotion-choice:hover { background: rgba(247,160,114,0.2); transform: scale(1.08); }
@media (max-width: 900px) {
  .app { flex-direction: column; align-items: center; gap: 24px; padding: 16px; }
  .sidebar { width: 100%; max-width: 600px; }
  .board { grid-template-columns: repeat(8, min(72px, calc((100vw - 80px) / 8))); grid-template-rows: repeat(8, min(72px, calc((100vw - 80px) / 8))); }
  .square { font-size: min(48px, calc((100vw - 80px) / 12)); }
}"""

  private val javascript: String =
    """let gameState = null;
let dragSource = null;
let pendingPromotion = null;

const WHITE_PIECES = { Q: '\u2655', R: '\u2656', B: '\u2657', N: '\u2658' };
const BLACK_PIECES = { Q: '\u265b', R: '\u265c', B: '\u265d', N: '\u265e' };

async function loadState() {
  const res = await fetch('/api/state');
  gameState = await res.json();
  renderBoard();
  renderMoveLog();
  renderTurnIndicator();
}

function renderBoard() {
  const board = document.getElementById('board');
  const rankLabels = document.getElementById('rankLabels');
  const fileLabels = document.getElementById('fileLabels');
  board.innerHTML = '';
  rankLabels.innerHTML = '';
  fileLabels.innerHTML = '';
  for (let r = 8; r >= 1; r--) {
    const label = document.createElement('div');
    label.textContent = r;
    rankLabels.appendChild(label);
  }
  for (let c = 0; c < 8; c++) {
    const label = document.createElement('div');
    label.textContent = String.fromCharCode(97 + c);
    fileLabels.appendChild(label);
  }
  const lastMove = getLastMove();
  gameState.squares.forEach(sq => {
    const div = document.createElement('div');
    div.className = 'square ' + sq.squareColor;
    div.dataset.pos = sq.pos;
    if (lastMove && sq.pos === lastMove.from) div.classList.add('last-move-from');
    if (lastMove && sq.pos === lastMove.to) div.classList.add('last-move-to');
    if (sq.piece) {
      const span = document.createElement('span');
      span.className = 'piece ' + sq.pieceColor + '-piece';
      span.textContent = sq.piece;
      span.draggable = true;
      span.addEventListener('dragstart', onDragStart);
      span.addEventListener('dragend', onDragEnd);
      div.appendChild(span);
    }
    div.addEventListener('dragover', onDragOver);
    div.addEventListener('dragleave', onDragLeave);
    div.addEventListener('drop', onDrop);
    board.appendChild(div);
  });
}

function getLastMove() {
  if (!gameState || gameState.moveLog.length === 0) return null;
  const squares = gameState.squares;
  return null;
}

function renderMoveLog() {
  const log = document.getElementById('moveLog');
  log.innerHTML = '';
  const moves = gameState.moveLog;
  for (let i = 0; i < moves.length; i += 2) {
    const row = document.createElement('div');
    row.className = 'move-row';
    const num = document.createElement('span');
    num.className = 'move-number';
    num.textContent = (Math.floor(i / 2) + 1) + '.';
    row.appendChild(num);
    const w = document.createElement('span');
    w.className = 'move-san';
    w.textContent = moves[i].san;
    row.appendChild(w);
    if (i + 1 < moves.length) {
      const b = document.createElement('span');
      b.className = 'move-san';
      b.textContent = moves[i + 1].san;
      row.appendChild(b);
    }
    log.appendChild(row);
  }
  log.scrollTop = log.scrollHeight;
}

function renderTurnIndicator() {
  const el = document.getElementById('turnIndicator');
  const color = gameState.activeColor;
  el.innerHTML = '<div class="turn-dot ' + color + '"></div><span>' +
    (color === 'white' ? 'White' : 'Black') + ' to move</span>';
}

function onDragStart(e) {
  const square = e.target.closest('.square');
  dragSource = square.dataset.pos;
  square.classList.add('drag-source');
  e.target.classList.add('dragging');
  e.dataTransfer.effectAllowed = 'move';
  e.dataTransfer.setData('text/plain', '');
}

function onDragEnd(e) {
  dragSource = null;
  e.target.classList.remove('dragging');
  document.querySelectorAll('.drag-source, .drag-over').forEach(el => {
    el.classList.remove('drag-source', 'drag-over');
  });
}

function onDragOver(e) {
  e.preventDefault();
  e.dataTransfer.dropEffect = 'move';
  const square = e.target.closest('.square');
  if (square) square.classList.add('drag-over');
}

function onDragLeave(e) {
  const square = e.target.closest('.square');
  if (square) square.classList.remove('drag-over');
}

function onDrop(e) {
  e.preventDefault();
  const square = e.target.closest('.square');
  if (!square || !dragSource) return;
  const target = square.dataset.pos;
  document.querySelectorAll('.drag-source, .drag-over').forEach(el => {
    el.classList.remove('drag-source', 'drag-over');
  });
  if (dragSource === target) { dragSource = null; return; }
  if (isPawnPromotion(dragSource, target)) {
    pendingPromotion = { from: dragSource, to: target };
    showPromotionDialog();
    dragSource = null;
    return;
  }
  sendMove(dragSource + ' ' + target);
  dragSource = null;
}

function isPawnPromotion(from, to) {
  const fromSq = gameState.squares.find(s => s.pos === from);
  if (!fromSq || !fromSq.piece) return false;
  const isPawn = fromSq.piece === '\u2659' || fromSq.piece === '\u265f';
  const toRow = parseInt(to[1]);
  return isPawn && (toRow === 8 || toRow === 1);
}

function showPromotionDialog() {
  const overlay = document.getElementById('promotionOverlay');
  const dialog = document.getElementById('promotionDialog');
  const fromSq = gameState.squares.find(s => s.pos === pendingPromotion.from);
  const pieces = fromSq.pieceColor === 'white' ? WHITE_PIECES : BLACK_PIECES;
  const pieceClass = fromSq.pieceColor + '-piece';
  dialog.innerHTML = '';
  Object.entries(pieces).forEach(([key, symbol]) => {
    const choice = document.createElement('div');
    choice.className = 'promotion-choice ' + pieceClass;
    choice.textContent = symbol;
    choice.onclick = () => {
      overlay.classList.remove('visible');
      sendMove(pendingPromotion.from + ' ' + pendingPromotion.to + '=' + key);
      pendingPromotion = null;
    };
    dialog.appendChild(choice);
  });
  overlay.classList.add('visible');
}

async function sendMove(move) {
  try {
    const res = await fetch('/api/move', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ move: move })
    });
    const data = await res.json();
    if (data.error) {
      showToast(data.error);
    } else {
      gameState = data;
      renderBoard();
      renderMoveLog();
      renderTurnIndicator();
    }
  } catch (err) {
    showToast('Connection error');
  }
}

function submitMove() {
  const input = document.getElementById('moveInput');
  const move = input.value.trim();
  if (move) { sendMove(move); input.value = ''; }
  return false;
}

async function newGame() {
  const res = await fetch('/api/new', { method: 'POST' });
  gameState = await res.json();
  renderBoard();
  renderMoveLog();
  renderTurnIndicator();
}

async function quitGame() {
  try {
    await fetch('/api/quit', { method: 'POST' });
  } catch (e) {}
  document.body.innerHTML = '<div style="display:flex;justify-content:center;align-items:center;height:100vh;font-size:24px;color:#f7a072;">Goodbye!</div>';
}

function showToast(msg) {
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.classList.add('visible');
  setTimeout(() => toast.classList.remove('visible'), 3000);
}

document.addEventListener('DOMContentLoaded', loadState);"""
