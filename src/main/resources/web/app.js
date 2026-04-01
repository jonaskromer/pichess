let gameState = null;
let dragSource = null;
let pendingPromotion = null;

const WHITE_PIECES = { Q: '\u2655', R: '\u2656', B: '\u2657', N: '\u2658' };
const BLACK_PIECES = { Q: '\u265b', R: '\u265c', B: '\u265d', N: '\u265e' };

async function loadState() {
  try {
    const res = await fetch('/api/state');
    gameState = await res.json();
    renderBoard();
    renderMoveLog();
    renderTurnIndicator();
  } catch (e) {}
}

function connectEvents() {
  const source = new EventSource('/api/events');
  source.addEventListener('state', (e) => {
    gameState = JSON.parse(e.data);
    renderBoard();
    renderMoveLog();
    renderTurnIndicator();
  });
  source.addEventListener('quit', () => {
    source.close();
    showGoodbye();
  });
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
  gameState.squares.forEach(sq => {
    const div = document.createElement('div');
    div.className = 'square ' + sq.squareColor;
    if (gameState.checkedKingPos && sq.pos === gameState.checkedKingPos) {
      div.classList.add('in-check');
    }
    div.dataset.pos = sq.pos;
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
}

async function quitGame() {
  try {
    await fetch('/api/quit', { method: 'POST' });
  } catch (e) {}
}

function showGoodbye() {
  document.body.innerHTML = '<div style="display:flex;justify-content:center;align-items:center;height:100vh;font-size:24px;color:#f7a072;">Goodbye!</div>';
}

function showToast(msg) {
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.classList.add('visible');
  setTimeout(() => toast.classList.remove('visible'), 3000);
}

document.addEventListener('DOMContentLoaded', () => {
  loadState();
  connectEvents();
});
