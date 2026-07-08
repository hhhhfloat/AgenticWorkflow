// ===== 数据层 =====
const STORAGE_KEY = 'basketball-checkin-data';
const DEFAULT_DATA = {
  activity: {
    title: '周末篮球局',
    date: '2025-01-18',
    time: '15:00',
    location: '社区篮球场'
  },
  members: [
    { id: 1, name: '张三', status: 'going' },
    { id: 2, name: '李四', status: 'going' },
    { id: 3, name: '王五', status: 'going' },
    { id: 4, name: '赵六', status: 'pending' },
    { id: 5, name: '陈七', status: 'pending' },
    { id: 6, name: '周八', status: 'not_going' },
    { id: 7, name: '刘九', status: 'not_going' },
  ],
  nextId: 8
};

let appData;

function loadData() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      appData = JSON.parse(raw);
      if (!appData.nextId) appData.nextId = Math.max(0, ...appData.members.map(m => m.id)) + 1;
    } else {
      appData = JSON.parse(JSON.stringify(DEFAULT_DATA));
    }
  } catch (e) {
    appData = JSON.parse(JSON.stringify(DEFAULT_DATA));
  }
}

function saveData() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(appData));
}

// ===== 渲染层 =====
function renderAll() {
  renderHeader();
  renderStats();
  renderColumns();
  renderTeamHint();
}

function renderHeader() {
  const a = appData.activity;
  document.getElementById('activityTitle').textContent = a.title || '篮球局';
  document.getElementById('activityDate').textContent = a.date || '--';
  document.getElementById('activityTime').textContent = a.time || '--';
  document.getElementById('activityLocation').textContent = a.location || '--';
}

function renderStats() {
  const going = appData.members.filter(m => m.status === 'going').length;
  const pending = appData.members.filter(m => m.status === 'pending').length;
  const notGoing = appData.members.filter(m => m.status === 'not_going').length;
  const total = appData.members.length;

  document.getElementById('statTotal').textContent = total;
  document.getElementById('statGoing').textContent = going;
  document.getElementById('statPending').textContent = pending;
  document.getElementById('statNotGoing').textContent = notGoing;
}

function renderColumns() {
  renderColumn('going', 'colGoing', 'colCountGoing');
  renderColumn('pending', 'colPending', 'colCountPending');
  renderColumn('not_going', 'colNot', 'colCountNot');
}

function renderColumn(status, bodyId, countId) {
  const members = appData.members.filter(m => m.status === status);
  const body = document.getElementById(bodyId);
  document.getElementById(countId).textContent = members.length;

  if (members.length === 0) {
    body.innerHTML = '';
    return;
  }

  body.innerHTML = members.map(m => {
    const initial = m.name.charAt(0);
    const statusLabels = { going: '参加', pending: '待定', not_going: '不参加' };
    const nextStatus = cycleStatus(m.status);
    const nextLabel = statusLabels[nextStatus];
    return `
      <div class="member-card" title="点击切换状态 → ${nextLabel}" onclick="cycleMemberStatus(${m.id})">
        <div class="member-avatar">${initial}</div>
        <div class="member-info">
          <div class="member-name">${escapeHtml(m.name)}</div>
          <div class="member-status-hint">点击切换 · 当前：${statusLabels[m.status]}</div>
        </div>
        <button class="btn-delete-member" onclick="deleteMember(event, ${m.id})" title="删除">×</button>
      </div>
    `;
  }).join('');
}

function renderTeamHint() {
  const going = appData.members.filter(m => m.status === 'going').length;
  const hintEl = document.getElementById('teamHint');
  const textEl = document.getElementById('hintText');
  const btnShuffle = document.getElementById('btnShuffle');

  hintEl.classList.remove('ready');

  if (going >= 10) {
    textEl.textContent = `🔥 ${going} 人参加！可以打全场 5v5 了！`;
    hintEl.classList.add('ready');
    btnShuffle.disabled = false;
  } else if (going >= 6) {
    textEl.textContent = `💪 ${going} 人参加，可以打半场 3v3！`;
    hintEl.classList.add('ready');
    btnShuffle.disabled = false;
  } else if (going >= 4) {
    textEl.textContent = `👍 ${going} 人参加，可以 2v2！还差 ${6 - going} 人能 3v3`;
    hintEl.classList.add('ready');
    btnShuffle.disabled = false;
  } else if (going > 0) {
    textEl.textContent = `还差 ${4 - going} 人才能 2v2，继续号召吧！`;
    btnShuffle.disabled = true;
  } else {
    textEl.textContent = '等待大家报名中…';
    btnShuffle.disabled = true;
  }
}

// ===== 业务逻辑 =====
function cycleStatus(current) {
  const order = ['pending', 'going', 'not_going'];
  const idx = order.indexOf(current);
  return order[(idx + 1) % 3];
}

function cycleMemberStatus(id) {
  const member = appData.members.find(m => m.id === id);
  if (!member) return;
  member.status = cycleStatus(member.status);
  saveData();
  renderAll();
}

function addMember() {
  const input = document.getElementById('inputNewMember');
  const name = input.value.trim();
  if (!name) {
    input.focus();
    return;
  }
  // 检查重名
  if (appData.members.some(m => m.name === name)) {
    alert('该成员已存在，请使用不同的姓名。');
    return;
  }
  appData.members.push({
    id: appData.nextId++,
    name: name,
    status: 'pending'
  });
  input.value = '';
  input.focus();
  saveData();
  renderAll();
}

function deleteMember(event, id) {
  event.stopPropagation();
  const member = appData.members.find(m => m.id === id);
  if (!member) return;
  if (!confirm(`确定要删除「${member.name}」吗？此操作不可恢复。`)) return;
  appData.members = appData.members.filter(m => m.id !== id);
  saveData();
  renderAll();
}

function resetAllPending() {
  if (appData.members.length === 0) return;
  if (!confirm('将所有成员的状态重置为「待定」？')) return;
  appData.members.forEach(m => { m.status = 'pending'; });
  saveData();
  renderAll();
}

function clearAllMembers() {
  if (appData.members.length === 0) return;
  if (!confirm(`确定要删除全部 ${appData.members.length} 名成员吗？此操作不可恢复。`)) return;
  appData.members = [];
  appData.nextId = 1;
  saveData();
  renderAll();
}

// ===== 活动编辑弹窗 =====
function openActivityModal() {
  const a = appData.activity;
  document.getElementById('inputTitle').value = a.title || '';
  document.getElementById('inputDate').value = a.date || '';
  document.getElementById('inputTime').value = a.time || '';
  document.getElementById('inputLocation').value = a.location || '';
  document.getElementById('modalActivity').style.display = 'flex';
}

function closeActivityModal(event) {
  if (event && event.target !== document.getElementById('modalActivity')) return;
  document.getElementById('modalActivity').style.display = 'none';
}

function saveActivity() {
  appData.activity.title = document.getElementById('inputTitle').value.trim() || '篮球局';
  appData.activity.date = document.getElementById('inputDate').value;
  appData.activity.time = document.getElementById('inputTime').value;
  appData.activity.location = document.getElementById('inputLocation').value.trim();
  saveData();
  renderHeader();
  document.getElementById('modalActivity').style.display = 'none';
}

// ===== 分队弹窗 =====
function openShuffleModal() {
  doShuffle();
  document.getElementById('modalShuffle').style.display = 'flex';
}

function closeShuffleModal(event) {
  if (event && event.target !== document.getElementById('modalShuffle')) return;
  document.getElementById('modalShuffle').style.display = 'none';
}

function doShuffle() {
  const goingMembers = appData.members.filter(m => m.status === 'going').map(m => m.name);
  // Fisher-Yates shuffle
  const shuffled = [...goingMembers];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  const half = Math.ceil(shuffled.length / 2);
  const teamA = shuffled.slice(0, half);
  const teamB = shuffled.slice(half);

  const container = document.getElementById('shuffleTeams');
  container.innerHTML = `
    <div class="shuffle-team team-a">
      <div class="team-name">🟠 A 队（${teamA.length}人）</div>
      <div class="team-members">${teamA.length ? teamA.map(n => '🏀 ' + escapeHtml(n)).join('<br>') : '—'}</div>
    </div>
    <div class="shuffle-team team-b">
      <div class="team-name">🔵 B 队（${teamB.length}人）</div>
      <div class="team-members">${teamB.length ? teamB.map(n => '🏀 ' + escapeHtml(n)).join('<br>') : '—'}</div>
    </div>
  `;
}

// ===== 工具函数 =====
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// ===== 键盘事件 =====
document.addEventListener('keydown', function(e) {
  // Enter 添加成员
  if (e.key === 'Enter' && document.activeElement === document.getElementById('inputNewMember')) {
    e.preventDefault();
    addMember();
  }
  // Escape 关闭弹窗
  if (e.key === 'Escape') {
    document.getElementById('modalActivity').style.display = 'none';
    document.getElementById('modalShuffle').style.display = 'none';
  }
});

// ===== 初始化 =====
loadData();
renderAll();
