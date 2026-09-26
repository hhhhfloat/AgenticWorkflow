// @anchor: modules_sse
// 以 SSE 流式执行 Agent 请求，逐条渲染输出并处理特殊事件

// ===== SSE 流式请求模块 =====

// ===== 本轮运行日志缓冲（用于结束后折叠展示） =====
let currentRunLog = [];

async function runAgent(prompt, maxIterations) {
    currentRunLog = [];   // ← 新增：清空上一轮缓冲

    if (!isDraftSession()) {
        await loadSessionHistory(getCurrentSessionId());
    } else {
        output.innerHTML = '';
    }
    // 用户 prompt → 绿色气泡
    appendMessage('user', prompt);

    appendLog(`────────── 运行中 ──────────\n`);

    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;

    const settings = getEffectiveSettings ? getEffectiveSettings() : {};
    const config = buildRunConfig(settings);
    const sessionId = getCurrentSessionId(); // 可能为 null，后端会自动创建

    fetch(BASE_URL + '/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            prompt: prompt,
            maxIterations: maxIterations,
            sessionId: sessionId,
            config: config
        })
    })
        .then(response => {
        if (!response.ok) {
            return response.text().then(text => {
                throw new Error(`HTTP ${response.status}: ${text}`);
            });
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        function readChunk() {
            reader.read().then(({ done, value }) => {
                if (done) {
                    finishRun(true);
                    return;
                }
                buffer += decoder.decode(value, { stream: true });
                const events = buffer.split('\n\n');
                buffer = events.pop();
                for (const event of events) {
                    if (event.startsWith('data: ')) {
                        const data = event.substring(6).trim().replace(/\\n/g, '\n');
                        if (handleSpecialEvent(data)) continue;
                        currentRunLog.push(data);   // ← 新增：记录到缓冲
                        appendLog(data);
                    }
                }
                readChunk();
            }).catch(err => {
                if (!window._isPageUnloading) {
                    appendLog('[错误] ' + err.message);
                }
                finishRun();
            });
        }
        readChunk();
    })
        .catch(err => {
        if (!window._isPageUnloading) {
            appendLog('[连接错误] ' + err.message + '\n请确保 HTTP 服务已启动（运行 start.bat）');
        }
        finishRun();
    });
}

/**
 * 处理特殊 SSE 事件。返回 true 表示已处理，不再作为普通日志输出。
 */
function handleSpecialEvent(data) {
    // [结束] 标志
    if (data === '[结束]') {
        finishRun(true);
        return true;
    }

    // 新增：usage 事件
    if (data.startsWith('{') && data.indexOf('"type":"usage"') !== -1) {
        try {
            const obj = JSON.parse(data);
            if (obj.type === 'usage' && obj.sessionId) {
                renderUsagePanel();
                return true;
            }
        } catch (e) {}
    }
    // 首条 session 事件：{"type":"session","sessionId":"xxx","isNew":true/false}
    if (data.startsWith('{') && data.indexOf('"type":"session"') !== -1) {
        try {
            const obj = JSON.parse(data);
            if (obj.type === 'session' && obj.sessionId) {
                const prevId = getCurrentSessionId();
                setCurrentSessionId(obj.sessionId);
                if (obj.isNew || !prevId) {
                    loadSessionList();  // 新会话 → 刷新列表
                } else {
                    highlightCurrentSession();
                }
                return true;
            }
        } catch (e) {}
    }
    return false;
}

// @anchor: modules_finishRun
// 运行结束后复位按钮状态、刷新会话并把本轮日志折叠归档

// ===== 运行结束清理 =====
function finishRun(normal = false) {
    if (!normal && !window._isPageUnloading && isRunning) {
        appendLog('[系统] ⚠️ 任务意外终止，请检查后端服务状态');
    }
    runBtn.disabled = false;
    stopBtn.disabled = true;
    isRunning = false;
    refreshSandbox();

    if (!isDraftSession()) {
        loadSessionList();

        if (normal) {
            // 结束后精炼 + 附加折叠详情
            setTimeout(async () => {
                await loadSessionHistory(getCurrentSessionId());
                appendRunDetail();
            }, 300);
        }
    }
}

/**
 * 把本轮实时日志包装成折叠块，附加到 #output 末尾。
 */
function appendRunDetail() {
    if (currentRunLog.length === 0) return;

    const details = document.createElement('details');
    details.className = 'run-detail';

    const summary = document.createElement('summary');
    summary.textContent = '▸ 查看本轮运行详情';
    details.appendChild(summary);

    const pre = document.createElement('pre');
    pre.textContent = currentRunLog.join('\n');
    details.appendChild(pre);

    output.appendChild(details);
    output.scrollTop = output.scrollHeight;
}

// @anchor: modules_stop
// 请求 /stop 停止当前会话任务并复位界面状态

// ===== 停止 Agent =====
function stopAgent() {
    if (!isRunning) return;
    stopBtn.disabled = true;
    appendLog('[系统] 正在停止任务...');

    const sessionId = getCurrentSessionId();
    fetch(BASE_URL + '/stop', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: sessionId })
    })
        .then(response => response.json())
        .then(data => {
        appendLog('[系统] ' + data.message);
        stopBtn.disabled = true;
        runBtn.disabled = false;
        isRunning = false;
        finishRun(true);
    })
        .catch(err => {
        if (!window._isPageUnloading) {
            appendLog('[错误] 停止请求失败: ' + err.message);
        }
        stopBtn.disabled = false;
    });
}
