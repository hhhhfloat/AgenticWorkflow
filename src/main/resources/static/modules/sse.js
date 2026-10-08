// @anchor: sse_intro
// 以 SSE 流式执行 Agent 请求，逐条渲染输出并处理特殊事件

// ===== 运行中状态 =====
let desktopRunAbort = null;

// @anchor: sse_runAgent
// 运行 Agent：POST /run 建立 SSE 流并逐条渲染输出
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
                // abort 后立即停止输出，避免最后一批 buffer 写进别人的视图
                if (desktopRunAbort !== myAbort) return;
                const data = parseSseFrame(event);
                if (data === null) continue;
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

// @anchor: sse_abortRun
// 主动断开 /run 的 SSE（不停止后端任务）；用于切换会话时避免日志串台
function abortRun() {
    if (desktopRunAbort) {
        desktopRunAbort.abort();
        desktopRunAbort = null;
    }
}
// @anchor: sse_isLocalRunActive
// 本端是否正在通过 /run 跑任务（用于避免与 /stream 重复订阅）
function isLocalRunActive() {
    return desktopRunAbort !== null;
}

// @anchor: sse_parseControlEvent
// 尝试把一行 data 解析为控制事件；解析失败返回 null
function parseControlEvent(data) {
    if (!data.startsWith('{')) return null;
    try {
        const obj = JSON.parse(data);
        if (obj && typeof obj.type === 'string') return obj;
    } catch (e) {}
    return null;
}

// @anchor: sse_parseFrame
// 解析一段 SSE 原始帧，返回其中的 data 内容（原生多行 data 用 \n 拼接）
// 无 data 行时返回 null
function parseSseFrame(rawFrame) {
    const dataLines = [];
    for (const line of rawFrame.split('\n')) {
        if (line.startsWith('data: ')) {
            dataLines.push(line.substring(6));
        } else if (line.startsWith('data:')) {
            dataLines.push(line.substring(5));
        }
    }
    if (dataLines.length === 0) return null;
    return dataLines.join('\n');
}

// @anchor: sse_handleSpecialEvent
// 识别控制事件，返回是否已被消费
function handleSpecialEvent(data) {
    const obj = parseControlEvent(data);
    if (!obj) return false;

    switch (obj.type) {
        case 'session':
            if (obj.sessionId) {
                const prevId = getCurrentSessionId();
                setCurrentSessionId(obj.sessionId);
                if (obj.isNew || !prevId) loadSessionList();
                else highlightCurrentSession();
            }
            return true;
        case 'usage':
            if (obj.sessionId) renderUsagePanel();
            return true;
        case 'done':
            appendLog('[完成] ' + (obj.summary || ''));
            return true;
        case 'error':
            appendLog('[错误] ' + (obj.message || ''));
            return true;
        case 'end':
            if (typeof onTaskFinished === 'function') {
                onTaskFinished(getCurrentSessionId())
                    .catch(e => console.error('onTaskFinished 失败:', e));
            }
            return true;
        default:
            return false;   // 未知 type 交给普通日志路径
    }
}

// @anchor: sse_renderRunLogs
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

async function onTaskFinished(finishedSessionId) {
    await Promise.all([
        refreshSandbox(),
        refreshSidebar()
    ]);
    await loadSessionList();
    if (finishedSessionId && getCurrentSessionId() === finishedSessionId) {
        await loadSessionHistory(finishedSessionId);
    }
}

// @anchor: sse_stop
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

// @anchor: sse_desktopStream
// 桌面端只读日志流：订阅 /stream 观看正在运行任务的实时日志
let desktopStreamAbort = null;
let _currentStreamSessionId = null;
let _streamRetryTimer = null;
let _streamRetryCount = 0;

async function openStream(sessionId) {
    // 已在订阅同一会话 → 直接返回（幂等）
    if (_currentStreamSessionId === sessionId && desktopStreamAbort) return;
    // 切换会话时重置重试计数
    if (_currentStreamSessionId !== sessionId) _streamRetryCount = 0;

    // abort 旧流但不重置计数
    if (desktopStreamAbort) {
        desktopStreamAbort.abort();
        desktopStreamAbort = null;
    }
    if (_streamRetryTimer) {
        clearTimeout(_streamRetryTimer);
        _streamRetryTimer = null;
    }

    if (!sessionId) {
        _currentStreamSessionId = null;
        return;
    }
    _currentStreamSessionId = sessionId;

    const myAbort = new AbortController();
    desktopStreamAbort = myAbort;
    let streamEnded = false;

    try {
        const res = await fetch(BASE_URL + '/stream?sessionId=' + encodeURIComponent(sessionId), {
            signal: myAbort.signal
        });
        if (!res.ok) {
            appendLog('[系统] 日志流订阅失败: HTTP ' + res.status);
            scheduleStreamRetry(sessionId);
            return;
        }
        _streamRetryCount = 0;   // 连接成功 → 重置

        const reader = res.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (!streamEnded) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();
            for (const ev of events) {
                if (desktopStreamAbort !== myAbort) return;   // 主动 abort
                const data = parseSseFrame(ev);
                if (data === null) continue;
                const obj = parseControlEvent(data);
                if (obj && (obj.type === 'stream-end' || obj.type === 'stream-timeout')) {
                    if (obj.type === 'stream-timeout') {
                        appendLog('[系统] ⏱️ 日志流超时（任务可能仍在后台运行）');
                    } else {
                        appendLog('[系统] 日志流已结束');
                    }
                    streamEnded = true;
                    break;
                }
                if (handleSpecialEvent(data)) continue;
                appendLog(data);
            }
        }
    } catch (e) {
        if (e.name === 'AbortError') return;   // 主动 abort，不重连
        appendLog('[系统] 日志流中断: ' + e.message);
    } finally {
        if (desktopStreamAbort === myAbort) desktopStreamAbort = null;
    }

    // 流结束（正常或异常）→ 决策是否重连
    scheduleStreamRetry(sessionId);
}

// @anchor: sse_scheduleStreamRetry
// 流断开后按需重连：任务仍在跑 + 是当前会话 + 本端未跑 /run，则递增退避重试，最多 3 次
function scheduleStreamRetry(sessionId) {
    const needRetry = globalStatus
    && globalStatus.running
    && globalStatus.sessionId === sessionId
    && !isLocalRunActive();

    if (!needRetry) {
        closeStream();
        return;
    }
    if (_streamRetryCount >= 3) {
        appendLog('[系统] 日志流多次中断，停止重连（任务仍在后台运行）');
        closeStream();
        return;
    }
    _streamRetryCount++;
    const delay = 2000 * _streamRetryCount;
    appendLog(`[系统] ${delay / 1000} 秒后重连日志流...`);
    _streamRetryTimer = setTimeout(() => {
        _streamRetryTimer = null;
        if (globalStatus && globalStatus.running
        && globalStatus.sessionId === sessionId
        && !isLocalRunActive()) {
            openStream(sessionId);
        }
    }, delay);
}

function closeStream() {
    if (desktopStreamAbort) {
        desktopStreamAbort.abort();
        desktopStreamAbort = null;
    }
    if (_streamRetryTimer) {
        clearTimeout(_streamRetryTimer);
        _streamRetryTimer = null;
    }
    _currentStreamSessionId = null;
    _streamRetryCount = 0;
}