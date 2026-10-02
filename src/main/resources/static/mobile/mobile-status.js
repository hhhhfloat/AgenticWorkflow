// 手机端状态轮询：驱动按钮、检测任务结束、切回工作会话

async function fetchStatus() {
    try {
        const res = await fetch(BASE_URL + '/status');
        return await res.json();
    } catch (e) {
        return null;
    }
}

// 会话列表顶部按钮：无任务时"新对话"，有任务时"切回工作会话"
function updateNewSessionBtn(running) {
    if (!newSessionBtn) return;
    if (running) {
        newSessionBtn.textContent = '↩ 切回工作会话';
        newSessionBtn.dataset.mode = 'switch';
    } else {
        newSessionBtn.textContent = '＋ 新对话';
        newSessionBtn.dataset.mode = 'new';
    }
}

// 切到正在跑任务的会话，订阅其日志流
async function switchToWorkSession() {
    const sid = globalStatus.sessionId;
    closeDrawer();
    if (!sid) return;
    if (sid === currentSessionId) {
        outputEl.scrollTop = 0;
        return;
    }
    if (typeof closeStream === 'function') closeStream();
    currentSessionId = sid;
    setStoredSessionId(sid);
    await loadSessionHistory(sid);
    await loadSessionList();
    if (typeof openStream === 'function') openStream(sid);
}

async function pollStatus() {
    const data = await fetchStatus();
    if (!data) return;

    const wasRunning = lastKnownRunning;
    const nowRunning = !!data.running;
    lastKnownRunning = nowRunning;
    globalStatus = data;

    setStatus(nowRunning);
    updateNewSessionBtn(nowRunning);

    // 任务刚结束：刷新会话列表 + 重载历史拿最新摘要
    if (wasRunning && !nowRunning) {
        if (currentSessionId) {
            await loadSessionList();
            await loadSessionHistory(currentSessionId);
        }
    }
}

function startStatusPolling() {
    pollStatus();
    setInterval(pollStatus, 5000);
}

// 初始化：根据 /status 决定恢复工作会话还是本地会话
async function initMobileStatus() {
    const data = await fetchStatus();
    if (!data) {
        // 状态查询失败，兜底加载本地会话
        if (currentSessionId) await loadSessionHistory(currentSessionId);
        else outputEl.innerHTML = '<div class="hint">等待输入…</div>';
        return;
    }
    globalStatus = data;
    lastKnownRunning = !!data.running;
    setStatus(!!data.running);
    updateNewSessionBtn(!!data.running);

    if (data.running && data.sessionId) {
        if (data.sessionId !== currentSessionId) {
            currentSessionId = data.sessionId;
            setStoredSessionId(data.sessionId);
            await loadSessionHistory(data.sessionId);
        }
        if (typeof openStream === 'function') openStream(data.sessionId);
    } else if (currentSessionId) {
        await loadSessionHistory(currentSessionId);
    } else {
        outputEl.innerHTML = '<div class="hint">等待输入…</div>';
    }
}