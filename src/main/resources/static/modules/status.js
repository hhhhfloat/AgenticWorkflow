// @anchor: modules_status
// 后端状态轮询：驱动按钮状态、任务结束检测与首次重连

// ===== 全局状态 =====
let globalStatus = { running: false, sessionId: null, title: null, heartbeatStale: false };
let lastAppliedRunning = null;

// @anchor: modules_status_fetch
// 拉取 /status，失败返回 null（不阻塞轮询）
async function fetchStatus() {
    try {
        const res = await fetch(BASE_URL + '/status');
        return await res.json();
    } catch (e) {
        return null;
    }
}

// @anchor: modules_applyStatusToUI
// 根据 /status 结果刷新按钮；仅在 running 状态变化时更新，避免覆盖用户乐观状态
function applyStatusToUI(s) {
    const running = !!s.running;
    if (running === lastAppliedRunning) return;
    lastAppliedRunning = running;
    isRunning = running;

    if (runBtn) runBtn.disabled = running;
    if (stopBtn) stopBtn.disabled = !running;

    const newBtn = document.getElementById('newSessionBtn');
    if (newBtn) {
        if (running) {
            newBtn.textContent = '↩ 切回工作会话';
            newBtn.dataset.mode = 'switch';
        } else {
            newBtn.textContent = '＋ 新对话';
            newBtn.dataset.mode = 'new';
        }
    }
}

// @anchor: modules_switchToWorkSession
// 切换到正在运行的工作会话，并订阅其日志流
async function switchToWorkSession() {
    const sid = globalStatus.sessionId;
    if (!sid) return;
    if (sid === getCurrentSessionId()) {
        output.scrollTop = 0;
        return;
    }
    if (typeof abortRun === 'function') abortRun();
    if (typeof closeStream === 'function') closeStream();
    setCurrentSessionId(sid);
    await loadSessionHistory(sid);
    highlightCurrentSession();
    renderUsagePanel();
    if (typeof openStream === 'function') openStream(sid);
}

// @anchor: modules_pollStatus
// 周期性查询 /status，检测任务结束并驱动 UI
async function pollStatus() {
    const data = await fetchStatus();
    if (!data) return;

    const wasRunning = globalStatus.running;
    const prevSessionId = globalStatus.sessionId;
    globalStatus = data;

    applyStatusToUI(data);

    if (wasRunning && !data.running) {
        // 任务结束：刷新 UI（仅在查看该会话时重载视图）
        if (typeof onTaskFinished === 'function') {
            await onTaskFinished(prevSessionId);
        }
    }
}

// @anchor: modules_startStatusPolling
// 启动 3 秒一次的状态轮询
function startStatusPolling() {
    pollStatus();
    setInterval(pollStatus, 3000);
}

// @anchor: modules_checkBackendStatus
// 页面加载时首次检查：若有任务在跑，跳到工作会话并订阅日志
async function checkBackendStatus() {
    const data = await fetchStatus();
    if (!data) return;
    globalStatus = data;

    if (data.running && data.sessionId) {
        appendLog('[系统] 🟢 检测到正在运行的任务，正在重连…');
        if (data.sessionId !== getCurrentSessionId()) {
            if (typeof abortRun === 'function') abortRun();
            setCurrentSessionId(data.sessionId);
            await loadSessionHistory(data.sessionId);
            highlightCurrentSession();
            renderUsagePanel();
        }
        if (typeof openStream === 'function') openStream(data.sessionId);
    }
    applyStatusToUI(data);
}