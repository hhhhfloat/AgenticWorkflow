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
        setStatus(false);
        return;
    }

    if (!res.ok) {
        const text = await res.text();
        appendMessage('error', `HTTP ${res.status}: ${text}`);
        setStatus(false);
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
        setStatus(false);
        if (currentSessionId) {
            await loadSessionList();
        }
    }
}

async function stopAgent() {
    if (!currentSessionId) return;
    runBtn.disabled = true;
    appendLog('[系统] 正在停止…');
    try {
        const res = await fetch(BASE_URL + '/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentSessionId })
        });
        const data = await res.json();
        appendLog('[系统] ' + (data.message || data.status));
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
    } catch (e) {
        heartbeatFailCount++;
        if (heartbeatFailCount >= 3 && !disconnectedLogged) {
            disconnectedLogged = true;
            appendLog('[系统] 🔴 与服务器断联（连续心跳失败），任务可能在 120 秒后被超时停止');
        }
    }
}

function startHeartbeat() {
    setInterval(heartbeat, HEARTBEAT_MS);
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') heartbeat();
    });
}

// ===== 切回本地 =====
async function switchToLocal() {
    if (!confirm('服务将重启并切回本地模式。\n重启后请在电脑端打开 http://127.0.0.1:8080')) return;
    try { await fetch('/switch-to-local', { method: 'POST' }); } catch (e) {}
    document.body.innerHTML =
    '<div style="padding:40px;text-align:center;color:#8b949e">' +
    '<h2 style="color:#e6edf3">🔄 服务正在重启</h2>' +
    '<p>即将切回本地模式</p></div>';
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