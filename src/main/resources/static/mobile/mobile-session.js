// 手机端会话：本地 ID 存取、列表、历史、切换、新建、抽屉与 tab

// ===== 会话 ID 本地存取 =====
function getStoredSessionId() {
    try { return localStorage.getItem(SESSION_KEY) || null; } catch (e) { return null; }
}
function setStoredSessionId(id) {
    try {
        if (id) localStorage.setItem(SESSION_KEY, id);
        else localStorage.removeItem(SESSION_KEY);
    } catch (e) {}
}

// ===== 历史渲染 =====
function renderHistoryMessages(messages) {
    clearOutput();
    let skippedSystem = false;
    messages.forEach((msg, idx) => {
        const role = msg.role;
        const content = msg.content || '';
        if (idx === 0 && role === 'system' && !skippedSystem) {
            skippedSystem = true;
            return;
        }
        if (role === 'user') appendMessage('user', content);
        else if (role === 'assistant') appendMessage('assistant', content);
        else if (role === 'system') appendMessage('system', content);
    });
    if (messages.length === 0) {
        outputEl.innerHTML = '<div class="hint">等待输入…</div>';
    }
}

async function loadSessionHistory(sessionId) {
    try {
        const res = await fetch(BASE_URL + '/session/history?sessionId=' + encodeURIComponent(sessionId));
        if (!res.ok) {
            setStoredSessionId(null);
            currentSessionId = null;
            setSessionTitle(null);
            setProject(null);
            usageBar.textContent = '';
            clearOutput();
            outputEl.innerHTML = '<div class="hint">等待输入…</div>';
            await loadSessionList();
            return;
        }
        const data = await res.json();
        setSessionTitle(data.title);
        setProject(data.workProject);
        renderHistoryMessages(data.messages || []);
        loadSessionUsage(sessionId);
        await renderRunLogs(sessionId);
    } catch (e) {
        appendMessage('error', '加载历史失败: ' + e.message);
    }
}

// 拉取会话累计用量并渲染
async function loadSessionUsage(sessionId) {
    try {
        const res = await fetch(BASE_URL + '/session/usage?sessionId=' + encodeURIComponent(sessionId));
        if (!res.ok) { usageBar.textContent = ''; return; }
        const u = await res.json();
        if (!u || (u.apiCalls === 0 && u.promptTokens === 0)) {
            usageBar.textContent = '';
            return;
        }
        renderUsage(u);
    } catch (e) {
        usageBar.textContent = '';
    }
}

// ===== 会话列表 =====
async function loadSessionList() {
    try {
        const res = await fetch(BASE_URL + '/session/list');
        const data = await res.json();
        renderSessionList(data.sessions || []);
    } catch (e) {
        console.error('加载会话列表失败', e);
    }
}

function renderSessionList(sessions) {
    sessionListView.innerHTML = '';
    if (!sessions.length) {
        sessionListView.innerHTML = '<div class="session-empty">暂无会话</div>';
        return;
    }
    sessions.forEach(s => {
        const item = document.createElement('div');
        item.className = 'session-item';
        if (s.sessionId === currentSessionId) item.classList.add('active');

        const title = document.createElement('div');
        title.className = 'title';
        title.textContent = s.title || '新会话';
        item.appendChild(title);

        if (s.workProject) {
            const proj = document.createElement('div');
            proj.className = 'project';
            proj.textContent = '📌 ' + s.workProject;
            item.appendChild(proj);
        }

        const meta = document.createElement('div');
        meta.className = 'meta';
        const left = document.createElement('span');
        left.textContent = s.state === 'RUNNING' ? '运行中' : '空闲';
        const right = document.createElement('span');
        right.textContent = formatRelativeTime(s.lastActiveAt);
        meta.appendChild(left);
        meta.appendChild(right);
        item.appendChild(meta);

        item.addEventListener('click', () => switchToSession(s.sessionId));
        sessionListView.appendChild(item);
    });
}

function formatRelativeTime(t) {
    if (!t) return '';
    try {
        const time = new Date(t.replace(' ', 'T')).getTime();
        const diff = Date.now() - time;
        const min = 60000, hour = 60 * min, day = 24 * hour;
        if (diff < min) return '刚刚';
        if (diff < hour) return Math.floor(diff / min) + ' 分钟前';
        if (diff < day) return Math.floor(diff / hour) + ' 小时前';
        if (diff < 7 * day) return Math.floor(diff / day) + ' 天前';
        return t.substring(0, 10);
    } catch (e) { return t; }
}

async function switchToSession(sessionId) {
    if (sessionId === currentSessionId) { closeDrawer(); return; }
    if (typeof abortRun === 'function') abortRun();
    if (typeof closeStream === 'function') closeStream();
    currentSessionId = sessionId;
    setStoredSessionId(sessionId);
    await loadSessionHistory(sessionId);
    await loadSessionList();
    closeDrawer();
}

// ===== 新建会话 =====
function openCreateModal() {
    newProjectName.value = '';
    createMask.classList.add('show');
    loadExistingProjects();
    setTimeout(() => newProjectName.focus(), 50);
}
function closeCreateModal() { createMask.classList.remove('show'); }


// 加载 sandbox 下的已有项目
async function loadExistingProjects() {
    const list = document.getElementById('projectPickerList');
    if (!list) return;
    list.innerHTML = '<div class="picker-empty">加载中…</div>';
    try {
        const res = await fetch('/browse?path=sandbox');
        const data = await res.json();
        const dirs = (data.entries || []).filter(e => e.type === 'dir' && !e.name.startsWith('.'));
        if (!dirs.length) {
            list.innerHTML = '<div class="picker-empty">sandbox 下暂无项目</div>';
            return;
        }
        list.innerHTML = '';
        dirs.forEach(d => {
            const item = document.createElement('div');
            item.className = 'picker-item';
            item.textContent = '📁 ' + d.name;
            item.addEventListener('click', () => createSessionWithProject(d.name));
            list.appendChild(item);
        });
    } catch (e) {
        list.innerHTML = '<div class="picker-empty">加载失败：' + e.message + '</div>';
    }
}

    // 核心：按项目名创建会话
async function createSessionWithProject(project) {
    project = (project || '').trim();
    if (!project) { alert('请输入项目名'); return; }
    try {
        const res = await fetch(BASE_URL + '/session/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ project })
        });
        const data = await res.json();
        if (!res.ok || data.status !== 'ok') {
            alert('创建失败：' + (data.message || res.status));
            return;
        }
        closeCreateModal();
        currentSessionId = data.sessionId;
        setStoredSessionId(data.sessionId);
        setSessionTitle(data.title);
        setProject(data.workProject);
        clearOutput();
        outputEl.innerHTML = '<div class="hint">新会话已创建，输入需求开始运行</div>';
        await loadSessionList();
        closeDrawer();
    } catch (e) {
        alert('创建失败：' + e.message);
    }
}

// 输入框路径的封装
async function createSession() {
    await createSessionWithProject(newProjectName.value);
}

// ===== 抽屉与 tab =====
function openDrawer() {
    drawer.classList.add('show');
    loadSessionList();
}
function closeDrawer() {
    drawer.classList.remove('show');
}

function switchDrawerTab(tab) {
    const isSessions = tab === 'sessions';
    tabSessionsBtn.classList.toggle('active', isSessions);
    tabFilesBtn.classList.toggle('active', !isSessions);
    drawerSessions.hidden = !isSessions;
    drawerFiles.hidden = isSessions;
    if (!isSessions && typeof renderMobileTree === 'function') {
        renderMobileTree();
    }
}