(function() {
  // ============ 配置 ============
  const DIFFICULTIES = {
    easy:   { rows: 4, cols: 6,  pairs: 12, label: '简单' },
    medium: { rows: 6, cols: 8,  pairs: 24, label: '中等' },
    hard:   { rows: 8, cols: 10, pairs: 40, label: '困难' },
  };

  const CELL_GAP = 3;

  const ALL_EMOJIS = [
    '🍎','🍊','🍋','🍇','🍓','🍑','🥝','🌽','🍕','🎸',
    '🎯','💎','🍒','🥭','🍍','🫐','🍉','🎻','⚽','🏀',
    '🌸','🌻','🐶','🐱','🐸','🦊','🚀','🌈','🎨','🧩',
    '🎭','🎪','🔮','🪐','🍩','🍪','🧁','☕','🎲','🎵'
  ];

  // ============ 状态 ============
  let difficulty = 'medium';
  let rows, cols, pairs;
  let board = [];
  let cellEmoji = [];
  let selectedRC = null;
  let score = 0;
  let comboCount = 0;
  let lastMatchTime = 0;
  let timerSeconds = 0;
  let timerInterval = null;
  let gameOver = false;
  let locked = false;

  // ============ DOM ============
  const boardEl = document.getElementById('board');
  const canvasEl = document.getElementById('linkCanvas');
  const ctx = canvasEl.getContext('2d');
  const timerEl = document.getElementById('timer');
  const scoreEl = document.getElementById('score');
  const comboEl = document.getElementById('combo');
  const remainingEl = document.getElementById('remaining');
  const btnHint = document.getElementById('btnHint');
  const btnShuffle = document.getElementById('btnShuffle');
  const btnRestart = document.getElementById('btnRestart');
  const diffSelector = document.getElementById('diffSelector');
  const toast = document.getElementById('toast');

  let cellSize = 60;

  // ============ 工具 ============
  function shuffle(arr) {
    const a = [...arr];
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  function showToast(msg, duration = 1800) {
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toast._timeout);
    toast._timeout = setTimeout(() => toast.classList.remove('show'), duration);
  }

  // ============ 棋盘初始化 ============
  function initBoard() {
    const cfg = DIFFICULTIES[difficulty];
    rows = cfg.rows;
    cols = cfg.cols;
    pairs = cfg.pairs;

    board = [];
    for (let r = 0; r <= rows + 1; r++) {
      board[r] = new Array(cols + 2).fill(0);
    }

    const emojiCount = pairs / 2;
    const chosenEmojis = ALL_EMOJIS.slice(0, emojiCount);
    cellEmoji = [''];
    let flat = [];
    for (let i = 0; i < emojiCount; i++) {
      cellEmoji.push(chosenEmojis[i]);
      for (let k = 0; k < 4; k++) {
        flat.push(i + 1);
      }
    }
    flat = shuffle(flat);

    let idx = 0;
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        board[r][c] = flat[idx++];
      }
    }

    if (!hasAnyMatch()) {
      reshuffleBoard();
    }
  }

  function reshuffleBoard() {
    const flat = [];
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        if (board[r][c] !== 0) flat.push(board[r][c]);
      }
    }
    if (flat.length === 0) return;

    const shuffled = shuffle(flat);
    let attempts = 0;
    let current = shuffled;
    while (attempts < 200) {
      let idx = 0;
      for (let r = 1; r <= rows; r++) {
        for (let c = 1; c <= cols; c++) {
          if (board[r][c] !== 0) {
            board[r][c] = current[idx++];
          }
        }
      }
      if (hasAnyMatch()) return;
      current = shuffle(current);
      attempts++;
    }
  }

  // ============ 路径查找核心 ============
  function isLineClear(r1, c1, r2, c2) {
    if (r1 === r2) {
      const minC = Math.min(c1, c2);
      const maxC = Math.max(c1, c2);
      for (let c = minC + 1; c < maxC; c++) {
        if (board[r1][c] !== 0) return false;
      }
      return true;
    }
    if (c1 === c2) {
      const minR = Math.min(r1, r2);
      const maxR = Math.max(r1, r2);
      for (let r = minR + 1; r < maxR; r++) {
        if (board[r][c1] !== 0) return false;
      }
      return true;
    }
    return false;
  }

  function isEmptyOrEndpoint(r, c, r1, c1, r2, c2) {
    if (board[r][c] !== 0) {
      return (r === r1 && c === c1) || (r === r2 && c === c2);
    }
    return true;
  }

  function findPath(r1, c1, r2, c2) {
    if (r1 === r2 && c1 === c2) return null;
    if (board[r1][c1] !== board[r2][c2]) return null;
    if (board[r1][c1] === 0) return null;

    // 0 转弯：直线
    if ((r1 === r2 || c1 === c2) && isLineClear(r1, c1, r2, c2)) {
      return { path: [{r: r1, c: c1}, {r: r2, c: c2}] };
    }

    // 1 转弯
    if (board[r1][c2] === 0 && isLineClear(r1, c1, r1, c2) && isLineClear(r1, c2, r2, c2)) {
      return { path: [{r: r1, c: c1}, {r: r1, c: c2}, {r: r2, c: c2}] };
    }
    if (board[r2][c1] === 0 && isLineClear(r1, c1, r2, c1) && isLineClear(r2, c1, r2, c2)) {
      return { path: [{r: r1, c: c1}, {r: r2, c: c1}, {r: r2, c: c2}] };
    }

    // 2 转弯：水平扫描
    for (let r = 0; r <= rows + 1; r++) {
      if (!isEmptyOrEndpoint(r, c1, r1, c1, r2, c2)) continue;
      if (!isEmptyOrEndpoint(r, c2, r1, c1, r2, c2)) continue;
      if (!isLineClear(r1, c1, r, c1)) continue;
      if (!isLineClear(r, c1, r, c2)) continue;
      if (!isLineClear(r, c2, r2, c2)) continue;
      return { path: [{r: r1, c: c1}, {r: r, c: c1}, {r: r, c: c2}, {r: r2, c: c2}] };
    }

    // 2 转弯：垂直扫描
    for (let c = 0; c <= cols + 1; c++) {
      if (!isEmptyOrEndpoint(r1, c, r1, c1, r2, c2)) continue;
      if (!isEmptyOrEndpoint(r2, c, r1, c1, r2, c2)) continue;
      if (!isLineClear(r1, c1, r1, c)) continue;
      if (!isLineClear(r1, c, r2, c)) continue;
      if (!isLineClear(r2, c, r2, c2)) continue;
      return { path: [{r: r1, c: c1}, {r: r1, c: c}, {r: r2, c: c}, {r: r2, c: c2}] };
    }

    return null;
  }

  // ============ 可用配对检测 ============
  function findAnyMatch() {
    const groups = {};
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        const v = board[r][c];
        if (v === 0) continue;
        if (!groups[v]) groups[v] = [];
        groups[v].push({r, c});
      }
    }
    for (const v in groups) {
      const cells = groups[v];
      for (let i = 0; i < cells.length; i++) {
        for (let j = i + 1; j < cells.length; j++) {
          if (findPath(cells[i].r, cells[i].c, cells[j].r, cells[j].c)) {
            return [cells[i], cells[j]];
          }
        }
      }
    }
    return null;
  }

  function hasAnyMatch() {
    return findAnyMatch() !== null;
  }

  // ============ 渲染 ============
  function calcCellSize() {
    const maxWidth = Math.min(window.innerWidth - 280, 700);
    const maxHeight = Math.min(window.innerHeight - 220, 600);
    const csByWidth = Math.floor((maxWidth - (cols - 1) * CELL_GAP) / cols);
    const csByHeight = Math.floor((maxHeight - (rows - 1) * CELL_GAP) / rows);
    return Math.max(40, Math.min(72, csByWidth, csByHeight));
  }

  function boardTotalWidth() {
    return cols * cellSize + (cols - 1) * CELL_GAP;
  }

  function boardTotalHeight() {
    return rows * cellSize + (rows - 1) * CELL_GAP;
  }

  function renderBoard() {
    cellSize = calcCellSize();
    boardEl.style.setProperty('--cs', cellSize + 'px');
    boardEl.style.setProperty('--gap', CELL_GAP + 'px');
    boardEl.style.gridTemplateColumns = `repeat(${cols}, ${cellSize}px)`;
    boardEl.style.gridTemplateRows = `repeat(${rows}, ${cellSize}px)`;

    boardEl.innerHTML = '';
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        const cell = document.createElement('div');
        cell.className = 'cell';
        cell.dataset.row = r;
        cell.dataset.col = c;
        if (board[r][c] === 0) {
          cell.classList.add('removed');
        } else {
          cell.textContent = cellEmoji[board[r][c]];
        }
        boardEl.appendChild(cell);
      }
    }

    const bw = boardTotalWidth();
    const bh = boardTotalHeight();
    canvasEl.width = bw;
    canvasEl.height = bh;
    canvasEl.style.width = bw + 'px';
    canvasEl.style.height = bh + 'px';

    updateRemaining();
  }

  function updateCell(r, c) {
    const cell = boardEl.querySelector(`[data-row="${r}"][data-col="${c}"]`);
    if (!cell) return;
    cell.classList.remove('selected', 'hint-glow', 'matched');
    if (board[r][c] === 0) {
      cell.classList.add('removed');
      cell.textContent = '';
    } else {
      cell.classList.remove('removed');
      cell.textContent = cellEmoji[board[r][c]];
    }
  }

  function updateRemaining() {
    let count = 0;
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        if (board[r][c] !== 0) count++;
      }
    }
    remainingEl.textContent = count;
    if (count === 0) victory();
  }

  function clearSelection() {
    if (selectedRC) {
      const cell = boardEl.querySelector(`[data-row="${selectedRC.r}"][data-col="${selectedRC.c}"]`);
      if (cell) cell.classList.remove('selected');
      selectedRC = null;
    }
  }

  function refreshAllCells() {
    for (let r = 1; r <= rows; r++) {
      for (let c = 1; c <= cols; c++) {
        updateCell(r, c);
      }
    }
    clearSelection();
    clearCanvas();
    updateRemaining();
  }

  // ============ Canvas 绘制 ============
  function dataToPixel(r, c) {
    return {
      x: (c - 1) * (cellSize + CELL_GAP) + cellSize / 2,
      y: (r - 1) * (cellSize + CELL_GAP) + cellSize / 2
    };
  }

  function drawPath(pathPoints) {
    ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);
    if (!pathPoints || pathPoints.length < 2) return;

    ctx.beginPath();
    const p0 = dataToPixel(pathPoints[0].r, pathPoints[0].c);
    ctx.moveTo(p0.x, p0.y);

    for (let i = 1; i < pathPoints.length; i++) {
      const p = dataToPixel(pathPoints[i].r, pathPoints[i].c);
      ctx.lineTo(p.x, p.y);
    }

    ctx.strokeStyle = '#f0c040';
    ctx.lineWidth = 4;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.shadowColor = '#f0c040';
    ctx.shadowBlur = 14;
    ctx.stroke();

    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.shadowBlur = 0;
    ctx.stroke();

    for (const pt of pathPoints) {
      const p = dataToPixel(pt.r, pt.c);
      ctx.beginPath();
      ctx.arc(p.x, p.y, 5, 0, Math.PI * 2);
      ctx.fillStyle = '#ffffff';
      ctx.fill();
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3.5, 0, Math.PI * 2);
      ctx.fillStyle = '#f0c040';
      ctx.fill();
    }
  }

  function clearCanvas() {
    ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);
  }

  // ============ 交互逻辑 ============
  function handleCellClick(r, c) {
    if (gameOver || locked) return;
    if (board[r][c] === 0) return;

    if (selectedRC && selectedRC.r === r && selectedRC.c === c) {
      clearSelection();
      clearCanvas();
      return;
    }

    if (selectedRC === null) {
      selectedRC = {r, c};
      const cell = boardEl.querySelector(`[data-row="${r}"][data-col="${c}"]`);
      if (cell) cell.classList.add('selected');
    } else {
      const r1 = selectedRC.r, c1 = selectedRC.c;
      const r2 = r, c2 = c;

      const result = findPath(r1, c1, r2, c2);

      if (result) {
        locked = true;
        const cell1 = boardEl.querySelector(`[data-row="${r1}"][data-col="${c1}"]`);
        const cell2 = boardEl.querySelector(`[data-row="${r2}"][data-col="${c2}"]`);

        if (cell1) cell1.classList.add('selected');
        if (cell2) cell2.classList.add('selected');

        drawPath(result.path);

        const now = Date.now();
        if (now - lastMatchTime < 3000 && lastMatchTime > 0) {
          comboCount++;
          comboEl.textContent = 'x' + comboCount + ' 🔥';
        } else {
          comboCount = 0;
          comboEl.textContent = '-';
        }
        lastMatchTime = now;

        score += 10 + comboCount * 5;
        scoreEl.textContent = score;

        setTimeout(() => {
          clearCanvas();
          board[r1][c1] = 0;
          board[r2][c2] = 0;

          if (cell1) cell1.classList.add('matched');
          if (cell2) cell2.classList.add('matched');

          setTimeout(() => {
            updateCell(r1, c1);
            updateCell(r2, c2);
            selectedRC = null;
            locked = false;

            const rem = countRemaining();
            if (rem > 0 && !hasAnyMatch()) {
              showToast('🔄 无可用配对，自动重排中...', 1800);
              setTimeout(() => {
                reshuffleBoard();
                refreshAllCells();
                showToast('✅ 棋盘已重排！', 1500);
              }, 500);
            }
          }, 350);
        }, 420);
      } else {
        if (board[r1][c1] === board[r2][c2]) {
          showToast('⚠️ 相同图案但路径被阻挡', 1200);
        }
        clearSelection();
        selectedRC = {r, c};
        const cell = boardEl.querySelector(`[data-row="${r}"][data-col="${c}"]`);
        if (cell) cell.classList.add('selected');
        clearCanvas();
      }
    }
  }

  function countRemaining() {
    let c = 0;
    for (let r = 1; r <= rows; r++)
      for (let cc = 1; cc <= cols; cc++)
        if (board[r][cc] !== 0) c++;
    return c;
  }

  // ============ 游戏流程 ============
  function startGame() {
    stopTimer();
    initBoard();
    score = 0;
    comboCount = 0;
    lastMatchTime = 0;
    timerSeconds = 0;
    gameOver = false;
    locked = false;
    selectedRC = null;

    scoreEl.textContent = '0';
    comboEl.textContent = '-';
    timerEl.textContent = '00:00';

    renderBoard();
    clearCanvas();
    startTimer();

    document.querySelectorAll('.victory-overlay').forEach(el => el.remove());
    btnHint.disabled = false;
  }

  function victory() {
    gameOver = true;
    stopTimer();
    clearCanvas();
    clearSelection();
    btnHint.disabled = true;

    const mins = Math.floor(timerSeconds / 60);
    const secs = timerSeconds % 60;
    const timeStr = String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');

    const overlay = document.createElement('div');
    overlay.className = 'victory-overlay';
    overlay.innerHTML = `
      <div class="victory-card">
        <div class="victory-icon">🎉</div>
        <div class="victory-title">恭喜通关！</div>
        <div class="victory-stats">
          难度：<span>${DIFFICULTIES[difficulty].label}</span><br>
          最终得分：<span>${score}</span> 分<br>
          用时：<span>${timeStr}</span>
        </div>
        <button class="btn btn-primary" id="btnVictoryRestart">🔄 再来一局</button>
      </div>
    `;
    document.body.appendChild(overlay);

    overlay.querySelector('#btnVictoryRestart').addEventListener('click', () => {
      overlay.remove();
      startGame();
    });
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) { overlay.remove(); startGame(); }
    });
  }

  // ============ 计时器 ============
  function startTimer() {
    stopTimer();
    timerSeconds = 0;
    timerEl.textContent = '00:00';
    timerInterval = setInterval(() => {
      timerSeconds++;
      const m = Math.floor(timerSeconds / 60);
      const s = timerSeconds % 60;
      timerEl.textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
    }, 1000);
  }

  function stopTimer() {
    if (timerInterval) { clearInterval(timerInterval); timerInterval = null; }
  }

  // ============ 事件绑定 ============
  boardEl.addEventListener('click', (e) => {
    const cell = e.target.closest('.cell');
    if (!cell) return;
    const r = parseInt(cell.dataset.row);
    const c = parseInt(cell.dataset.col);
    if (isNaN(r) || isNaN(c)) return;
    handleCellClick(r, c);
  });

  btnRestart.addEventListener('click', startGame);

  btnHint.addEventListener('click', () => {
    if (gameOver || locked) return;
    const pair = findAnyMatch();
    if (!pair) {
      showToast('🔄 无可用配对，请点重排按钮', 2000);
      return;
    }

    score = Math.max(0, score - 5);
    scoreEl.textContent = score;
    comboCount = 0;
    comboEl.textContent = '-';

    clearSelection();
    clearCanvas();

    const cell1 = boardEl.querySelector(`[data-row="${pair[0].r}"][data-col="${pair[0].c}"]`);
    const cell2 = boardEl.querySelector(`[data-row="${pair[1].r}"][data-col="${pair[1].c}"]`);
    if (cell1) cell1.classList.add('hint-glow');
    if (cell2) cell2.classList.add('hint-glow');

    const result = findPath(pair[0].r, pair[0].c, pair[1].r, pair[1].c);
    if (result) drawPath(result.path);

    setTimeout(() => {
      if (cell1) cell1.classList.remove('hint-glow');
      if (cell2) cell2.classList.remove('hint-glow');
      clearCanvas();
    }, 1800);
  });

  btnShuffle.addEventListener('click', () => {
    if (gameOver || locked) return;
    if (countRemaining() === 0) return;
    reshuffleBoard();
    refreshAllCells();
    comboCount = 0;
    comboEl.textContent = '-';
    showToast('🔀 棋盘已重排', 1500);
  });

  diffSelector.addEventListener('click', (e) => {
    const btn = e.target.closest('.diff-btn');
    if (!btn) return;
    const level = btn.dataset.level;
    if (level === difficulty) return;
    difficulty = level;
    diffSelector.querySelectorAll('.diff-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    startGame();
  });

  let resizeDebounce;
  window.addEventListener('resize', () => {
    clearTimeout(resizeDebounce);
    resizeDebounce = setTimeout(() => {
      if (!gameOver && !locked) {
        renderBoard();
        clearCanvas();
        if (selectedRC) {
          const cell = boardEl.querySelector(`[data-row="${selectedRC.r}"][data-col="${selectedRC.c}"]`);
          if (cell) cell.classList.add('selected');
        }
      }
    }, 250);
  });

  window.addEventListener('keydown', (e) => {
    if (e.key === 'h' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      btnHint.click();
    } else if (e.key === 'r' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      if (!gameOver && !locked) btnShuffle.click();
    } else if (e.key === 'Escape') {
      clearSelection();
      clearCanvas();
    }
  });

  // ============ 启动 ============
  startGame();
})();
