// @anchor: modules_sse
// 以 SSE 流式执行 Agent 请求，逐条渲染输出并处理特殊事件

// ===== 运行中状态 =====
let desktopRunAbort = null;

// @anchor: modules_runAgent
async function runAgent(prompt, maxIterations) {
    if (!isDraftSession()) {
        await loadSessionHistory(getCurrentSessionId());
    } else {
        output.innerHTML = '';
    }
    appendMessage('user', prompt);
    appendLog(`────────── 运行中 ──────────\n`);

    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;

    const sessionId = getCurrentSessionId();
    const myAbort = new AbortController();
    desktopRunAbort = myAbort;

    let response;
    try {
        response = await fetch(BASE_URL + '/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                prompt: prompt,
                maxIterations: maxIterations,
                sessionId: sessionId,
                config: buildRunConfig(loadSettings())
            }),
            signal: myAbort.signal
        });
    } catch (err) {
        if (err.name !== 'AbortError' && !window._isPageUnloading) {
            appendLog('[连接错误] ' + err.message + '\n请确保 HTTP 服务已启动');
        }
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
        return;
    }

    if (!response.ok) {
        const text = await response.text();
        appendLog('HTTP ' + response.status + ': ' + text);
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
        return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();
            for (const event of events) {
                if (!event.startsWith('data: ')) continue;
                // abort 后立即停止输出，避免最后一批 buffer 写进别人的视图
                if (desktopRunAbort !== myAbort) return;
                const data = event.substring(6).trim().replace(/\\n/g, '\n');
                if (handleSpecialEvent(data)) continue;
                appendLog(data);
            }
        }
    } catch (err) {
        if (err.name !== 'AbortError' && !window._isPageUnloading) {
            appendLog('[错误] ' + err.message);
        }
    } finally {
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
    }
}

// @anchor: modules_abortRun
// 主动断开 /run 的 SSE（不停止后端任务）；用于切换会话时避免日志串台
function abortRun() {
    if (desktopRunAbort) {
        desktopRunAbort.abort();
        desktopRunAbort = null;
    }
}

// @anchor: modules_handleSpecialEvent
function handleSpecialEvent(data) {
    // [结束] 只是标记，UI 状态交给 pollStatus 判定
    if (data === '[结束]') return true;

    if (data.startsWith('{') && data.indexOf('"type":"usage"') !== -1) {
        try {
            const obj = JSON.parse(data);
            if (obj.type === 'usage' && obj.sessionId) {
                renderUsagePanel();
                return true;
            }
        } catch (e) {}
    }
    if (data.startsWith('{') && data.indexOf('"type":"session"') !== -1) {
        try {
            const obj = JSON.parse(data);
            if (obj.type === 'session' && obj.sessionId) {
                const prevId = getCurrentSessionId();
                setCurrentSessionId(obj.sessionId);
                if (obj.isNew || !prevId) {
                    loadSessionList();
                } else {
                    highlightCurrentSession();
                }
                return true;
            }
        } catch (e) {}
    }
    return false;
}

// @anchor: modules_renderRunLogs
// 从 /session/logs 拉取会话最近一轮日志，追加为折叠块
async function renderRunLogs(sessionId) {
    if (!sessionId) return;
    try {
        const res = await fetch(BASE_URL + '/session/logs?sessionId=' +
        encodeURIComponent(sessionId) + '&tail=500');
        if (!res.ok) return;
        const data = await res.json();
        if (!data.lines || data.lines.length === 0) return;

        const details = document.createElement('details');
        details.className = 'run-detail';

        const summary = document.createElement('summary');
        const total = data.total || 0;
        const shown = data.lines.length;
        summary.textContent = total > shown
            ? `▸ 查看最近一轮运行详情（已截断，共 ${total} 行，显示最后 ${shown} 行）`
            : `▸ 查看最近一轮运行详情（共 ${shown} 行）`;
        details.appendChild(summary);

        const pre = document.createElement('pre');
        pre.textContent = data.lines.join('\n');
        details.appendChild(pre);

        output.appendChild(details);
        if (isScrolledToBottom(output)) output.scrollTop = output.scrollHeight;
        updateScrollBottomBtn();
    } catch (e) {
        // 静默
    }
}

// @anchor: modules_onTaskFinished
// 由 pollStatus 调用：任务结束时刷新 UI
async function onTaskFinished(finishedSessionId) {
    refreshSandbox();
    await loadSessionList();
    // 仅当用户当前就在看结束的会话时，才重载视图（避免打断浏览其他会话）
    if (finishedSessionId && getCurrentSessionId() === finishedSessionId) {
        await loadSessionHistory(finishedSessionId);
    }
}

// @anchor: modules_stop
// 请求 /stop 停止正在运行的任务
async function stopAgent() {
    if (!isRunning) return;
    stopBtn.disabled = true;
    appendLog('[系统] 正在停止任务...');

    const sessionId = globalStatus.sessionId || getCurrentSessionId();
    try {
        const response = await fetch(BASE_URL + '/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: sessionId })
        });
        const data = await response.json();
        appendLog('[系统] ' + data.message);
        // 不主动复位，交给 pollStatus
    } catch (err) {
        if (!window._isPageUnloading) {
            appendLog('[错误] 停止请求失败: ' + err.message);
        }
        stopBtn.disabled = false;
    }
}

// @anchor: modules_desktop_stream
// 桌面端只读日志流：订阅 /stream 观看正在运行任务的实时日志
let desktopStreamAbort = null;

async function openStream(sessionId) {
    closeStream();
    if (!sessionId) return;
    const myAbort = new AbortController();
    desktopStreamAbort = myAbort;
    try {
        const res = await fetch(BASE_URL + '/stream?sessionId=' + encodeURIComponent(sessionId), {
            signal: myAbort.signal
        });
        if (!res.ok) {
            appendLog('[系统] 日志流订阅失败: HTTP ' + res.status);
            return;
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();
            for (const ev of events) {
                if (!ev.startsWith('data: ')) continue;
                if (desktopStreamAbort !== myAbort) return;
                const data = ev.substring(6).trim().replace(/\\n/g, '\n');
                if (data === '[stream-end]' || data === '[stream-timeout]') {
                    appendLog('[系统] 日志流已结束');
                    closeStream();
                    return;
                }
                appendLog(data);
            }
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            appendLog('[系统] 日志流中断: ' + e.message);
        }
    }
}

function closeStream() {
    if (desktopStreamAbort) {
        desktopStreamAbort.abort();
        desktopStreamAbort = null;
    }
}