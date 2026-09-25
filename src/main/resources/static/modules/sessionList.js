// @anchor: modules_sessionList
// 会话列表的加载 / 渲染 / 切换 / 高亮与会话历史回放

// ===== 会话列表：渲染、切换、历史加载 =====

/**
 * 拉取并渲染会话列表。
 */
async function loadSessionList() {
    try {
        const res = await fetch(BASE_URL + '/session/list');
        const data = await res.json();
        renderSessionList(data.sessions || []);
    } catch (err) {
        console.error('加载会话列表失败', err);
    }
}

/**
 * 渲染会话列表到 #sessionListView。
 */
function renderSessionList(sessions) {
    const container = document.getElementById('sessionListView');
    if (!container) return;
    container.innerHTML = '';

    if (!sessions || sessions.length === 0) {
        container.innerHTML = '<div class="session-empty">暂无会话</div>';
        return;
    }

    const currentId = getCurrentSessionId();

    sessions.forEach(session => {
        const item = document.createElement('div');
        item.className = 'session-item';
        if (session.sessionId === currentId) item.classList.add('active');
        item.dataset.sessionId = session.sessionId;

        const title = document.createElement('div');
        title.className = 'session-title';
        title.textContent = session.title || '新会话';

        const time = document.createElement('div');
        time.className = 'session-time';
        time.textContent = formatRelativeTime(session.lastActiveAt);

        const renameBtn = document.createElement('button');
        renameBtn.className = 'session-rename-btn';
        renameBtn.textContent = '✏️';
        renameBtn.title = '重命名';
        renameBtn.addEventListener('click', (e) => {
            e.stopPropagation();   // 阻止触发切换会话
            startRenameSession(item, session);
        });

        item.appendChild(title);
        item.appendChild(time);
        item.appendChild(renameBtn);
        item.addEventListener('click', () => switchToSession(session.sessionId));
        container.appendChild(item);
    });
}

/**
 * 让会话条目的标题进入编辑态。
 * 回车提交、ESC 取消、失焦提交。
 */
function startRenameSession(item, session) {
    const titleEl = item.querySelector('.session-title');
    if (!titleEl) return;

    const oldTitle = session.title || '新会话';

    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'session-title-input';
    input.value = oldTitle;
    input.maxLength = 100;

    let committed = false;

    const finish = async (save) => {
        if (committed) return;
        committed = true;

        const newTitle = input.value.trim();
        if (!save || !newTitle || newTitle === oldTitle) {
            titleEl.textContent = oldTitle;
            return;
        }

        // 乐观更新
        titleEl.textContent = newTitle;

        try {
            const res = await fetch(BASE_URL + '/session/rename', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: session.sessionId, title: newTitle })
            });
            const data = await res.json();
            if (!res.ok || data.status !== 'ok') {
                titleEl.textContent = oldTitle;
                alert('重命名失败：' + (data.message || res.status));
                return;
            }
            // 更新本地 session 对象，避免下次渲染回退
            session.title = newTitle;
        } catch (err) {
            titleEl.textContent = oldTitle;
            alert('重命名失败：' + err.message);
        }
    };

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            finish(true);
            input.replaceWith(titleEl);
        } else if (e.key === 'Escape') {
            e.preventDefault();
            finish(false);
            input.replaceWith(titleEl);
        }
    });

    input.addEventListener('blur', () => {
        // 输入框被移出 DOM 时 blur 也会触发，用 committed 去重
        if (!committed) {
            finish(true);
            input.replaceWith(titleEl);
        }
    });

    titleEl.replaceWith(input);
    input.focus();
    input.select();
}

/**
 * 相对时间格式化。
 */
function formatRelativeTime(timeStr) {
    if (!timeStr) return '';
    try {
        const t = new Date(timeStr.replace(' ', 'T')).getTime();
        const diff = Date.now() - t;
        const min = 60 * 1000;
        const hour = 60 * min;
        const day = 24 * hour;

        if (diff < min) return '刚刚';
        if (diff < hour) return Math.floor(diff / min) + ' 分钟前';
        if (diff < day) return Math.floor(diff / hour) + ' 小时前';
        if (diff < 7 * day) return Math.floor(diff / day) + ' 天前';
        return timeStr.substring(0, 10);
    } catch (e) {
        return timeStr;
    }
}

/**
 * 切换到某个会话。
 */
async function switchToSession(sessionId) {
    if (sessionId === getCurrentSessionId()) return;

    if (isRunning) {
        if (!confirm('当前任务正在运行，是否停止并切换？')) return;
        stopAgent();
        await new Promise(r => setTimeout(r, 400));
    }

    currentRunLog = [];   // ← 新增：切会话清空缓冲

    setCurrentSessionId(sessionId);
    await loadSessionHistory(sessionId);
    highlightCurrentSession();
    renderUsagePanel();   // ← 新增
}

/**
 * 更新侧边栏高亮。
 */
function highlightCurrentSession() {
    const currentId = getCurrentSessionId();
    document.querySelectorAll('.session-item').forEach(item => {
        item.classList.toggle('active', item.dataset.sessionId === currentId);
    });
}

/**
 * "新对话"按钮 —— 进入草稿状态。
 */
async function createNewSession() {
    // 已经是空白草稿 → 无效果
    if (isDraftSession() && output.innerHTML.trim() === '等待输入...') {
        return;
    }
    if (isRunning) {
        if (!confirm('当前任务正在运行，是否停止并开启新对话？')) return;
        stopAgent();
        await new Promise(r => setTimeout(r, 400));
    }

    clearCurrentSessionId();
    output.innerHTML = '等待输入...';
    highlightCurrentSession();
    renderUsagePanel();
}

/**
 * 加载并渲染会话历史到 #output。
 */
async function loadSessionHistory(sessionId) {
    try {
        const res = await fetch(BASE_URL + '/session/history?sessionId=' + encodeURIComponent(sessionId));
        if (!res.ok) {
            // 会话不存在（可能已被清理）
            clearCurrentSessionId();
            output.innerHTML = '等待输入...';
            await loadSessionList();
            return;
        }
        const data = await res.json();
        renderHistoryMessages(data.messages || []);
    } catch (err) {
        console.error('加载会话历史失败', err);
    }
}

/**
 * 把 messages 渲染到 #output。
 * 跳过索引 0 的 SystemPrompt（那是系统指令，不是对话内容）。
 */
function renderHistoryMessages(messages) {
    output.innerHTML = '';
    messages.forEach((msg, idx) => {
        const role = msg.role;
        const content = msg.content || '';
        if (idx === 0 && role === 'system') return;  // 跳过 SystemPrompt

        if (role === 'user') {
            appendMessage('user', content);
        } else if (role === 'assistant') {
            appendMessage('assistant', content);
        } else if (role === 'system') {
            // 【任务摘要】走特殊的居中样式
            appendMessage('system', content);
        }
    });
}

/**
 * 页面加载时尝试恢复上次会话。
 */
async function restoreCurrentSession() {
    const sid = getCurrentSessionId();
    if (!sid) return;

    try {
        const res = await fetch(BASE_URL + '/session/history?sessionId=' + encodeURIComponent(sid));
        if (!res.ok) {
            clearCurrentSessionId();
            return;
        }
        const data = await res.json();
        renderHistoryMessages(data.messages || []);
    } catch (e) {
        clearCurrentSessionId();
    }
}
