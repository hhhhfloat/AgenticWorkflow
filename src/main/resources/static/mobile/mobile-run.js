// 手机端运行：SSE 流、停止、心跳、usage、切回本地

function handleEvent(data) {
    if (data === '[结束]') return;

    if (data.startsWith('{')) {
        try {
            const obj = JSON.parse(data);
            if (obj.type === 'session') {
                currentSessionId = obj.sessionId;
                setStoredSessionId(obj.sessionId);
                return;
            }
            if (obj.type === 'usage') { renderUsage(obj); return; }
        } catch (e) { /* JSON 片段，忽略 */ }
    }

    if (data.startsWith('[完成]')) { appendMessage('done', data); return; }
    if (data.startsWith('[错误]')) { appendMessage('error', data); return; }
    appendLog(data);
}

async function runAgent() {
    const prompt = promptEl.value.trim();
    if (!prompt || isRunning) return;

    clearOutput();
    appendMessage('user', prompt);
    promptEl.value = '';
    promptEl.style.height = '44px';
    setStatus(true);

    const maxIterations = Number(maxIterEl.value) || 100;

    let res;
    try {
        res = await fetch(BASE_URL + '/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt, sessionId: currentSessionId, maxIterations })
        });
    } catch (e) {
        appendMessage('error', '[连接错误] ' + e.message);
        if (typeof pollStatus === 'function') pollStatus();
        return;
    }

    if (!res.ok) {
        const text = await res.text();
        appendMessage('error', `HTTP ${res.status}: ${text}`);
        if (typeof pollStatus === 'function') pollStatus();
        return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();
            for (const ev of events) {
                if (!ev.startsWith('data: ')) continue;
                const data = ev.substring(6).trim().replace(/\\n/g, '\n');
                handleEvent(data);
            }
        }
    } catch (e) {
        appendMessage('error', '[流中断] ' + e.message);
    } finally {
        // 不直接复位按钮，交给 pollStatus 判定任务是否真的结束
        if (currentSessionId) await loadSessionList();
        if (typeof pollStatus === 'function') pollStatus();
    }
}

async function stopAgent() {
    const sid = (globalStatus && globalStatus.sessionId) || currentSessionId;
    if (!sid) return;
    runBtn.disabled = true;
    appendLog('[系统] 正在停止…');
    try {
        const res = await fetch(BASE_URL + '/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: sid })
        });
        const data = await res.json();
        appendLog('[系统] ' + (data.message || data.status));
        // 不主动复位按钮，交给 pollStatus 判定任务是否真结束
    } catch (e) {
        appendMessage('error', '[停止失败] ' + e.message);
        runBtn.disabled = false;
    }
}

// ===== 心跳 =====
let heartbeatFailCount = 0;
let disconnectedLogged = false;

async function heartbeat() {
    try {
        const res = await fetch(BASE_URL + '/heartbeat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentSessionId })
        });
        const data = await res.json();
        if (heartbeatFailCount > 0) {
            appendLog('[系统] 🟢 连接恢复');
            heartbeatFailCount = 0;
            disconnectedLogged = false;
        }
        if (data.refresh) appendLog('[系统] 检测到沙箱目录变化');
        if (typeof refreshController === 'function') refreshController();
    } catch (e) {
        heartbeatFailCount++;
        if (heartbeatFailCount >= 3 && !disconnectedLogged) {
            disconnectedLogged = true;
            appendLog('[系统] 🔴 与服务器断联（连续心跳失败），任务仍在后台运行');
        }
    }
}

function startHeartbeat() {
    setInterval(heartbeat, HEARTBEAT_MS);
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') heartbeat();
    });
}



// 退出主服务（不关 launcher）
async function shutdownMainService() {
    if (isRunning) {
        alert('任务正在运行，请先停止再退出');
        return;
    }
    if (!confirm('退出主服务？\nlauncher 会继续运行，手机可随时再启动。')) return;
    try { await fetch('/shutdown', { method: 'POST' }); } catch (e) {}
    document.body.innerHTML =
    '<div style="padding:40px;text-align:center;color:#8b949e">' +
    '<h2 style="color:#e6edf3">⏻ 主服务已退出</h2>' +
    '<p>launcher 仍在 8081 运行</p>' +
    '<p style="margin-top:20px"><a href="http://' + location.hostname + ':8081/" ' +
    'style="color:#58a6ff">重新启动</a></p>' +
    '</div>';
}

// ===== 只读日志流 =====

let streamAbort = null;

async function openStream(sessionId) {
    closeStream();
    if (!sessionId) return;
    streamAbort = new AbortController();
    try {
        const res = await fetch(BASE_URL + '/stream?sessionId=' + encodeURIComponent(sessionId), {
            signal: streamAbort.signal
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
                const data = ev.substring(6).trim().replace(/\\n/g, '\n');
                if (data === '[stream-end]' || data === '[stream-timeout]') {
                    appendLog('[系统] 日志流已结束');
                    closeStream();
                    return;
                }
                handleEvent(data);
            }
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            appendLog('[系统] 日志流中断: ' + e.message);
        }
    }
}

function closeStream() {
    if (streamAbort) {
        streamAbort.abort();
        streamAbort = null;
    }
}