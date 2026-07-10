// @anchor: script_domRefs
// ===== DOM 引用 =====
const output = document.getElementById('output');
const runBtn = document.getElementById('runBtn');
const stopBtn = document.getElementById('stopBtn');
const clearBtn = document.getElementById('clearBtn');
const promptInput = document.getElementById('prompt');

// @anchor: script_state
// ===== 全局状态 =====
let isRunning = false;
let heartbeatInterval = null;

// @anchor: script_constants
// ===== 常量配置 =====
const MAX_HISTORY = 30;
const STORAGE_KEY = 'promptHistory';
const HEARTBEAT_INTERVAL_MS = 3000;
const BASE_URL = 'http://localhost:8080';

// ============================================================
// @anchor: script_history
// ===== 历史记录模块 =====
// ============================================================

function getHistory() {
    try {
        const data = localStorage.getItem(STORAGE_KEY);
        return data ? JSON.parse(data) : [];
    } catch (e) {
        return [];
    }
}

function saveHistory(history) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(history));
    } catch (e) {
        // 存储空间不足时忽略
    }
}

function addToHistory(prompt) {
    if (!prompt || !prompt.trim()) return;
    const history = getHistory();
    const filtered = history.filter(item => item !== prompt);
    filtered.unshift(prompt);
    if (filtered.length > MAX_HISTORY) {
        filtered.length = MAX_HISTORY;
    }
    saveHistory(filtered);
    return filtered;
}

function renderHistory() {
    const history = getHistory();
    let container = document.getElementById('historyContainer');
    let dropdown = document.getElementById('historyDropdown');

    if (!container) {
        container = document.createElement('div');
        container.id = 'historyContainer';
        const textarea = document.getElementById('prompt');
        textarea.parentNode.insertBefore(container, textarea.nextSibling);
    }

    container.innerHTML = '';

    dropdown = document.createElement('select');
    dropdown.id = 'historyDropdown';
    dropdown.style.cssText =
        'width:100%; margin-top:4px; background:#2d2d2d; color:#fff; border:1px solid #444; padding:6px; font-size:13px;';
    container.appendChild(dropdown);

    dropdown.innerHTML = '<option value="">📜 历史记录</option>';
    if (history.length === 0) {
        dropdown.innerHTML += '<option value="" disabled>（暂无记录）</option>';
    } else {
        history.forEach(item => {
            const option = document.createElement('option');
            option.value = item;
            const display = item.length > 60 ? item.substring(0, 60) + '…' : item;
            option.textContent = display;
            dropdown.appendChild(option);
        });
        const clearOption = document.createElement('option');
        clearOption.value = '__clear__';
        clearOption.textContent = '🗑 清空所有历史';
        clearOption.style.color = '#f44747';
        dropdown.appendChild(clearOption);
    }

    dropdown.addEventListener('change', function () {
        if (this.value === '__clear__') {
            if (confirm('确认清空所有历史记录吗？')) {
                localStorage.removeItem(STORAGE_KEY);
                renderHistory();
                appendLog('[系统] 已清空所有历史记录');
            }
            this.value = '';
            return;
        }
        if (this.value) {
            document.getElementById('prompt').value = this.value;
            this.value = '';
        }
    });
}

// ============================================================
// @anchor: script_projects
// ===== 历史项目模块 =====
// ============================================================

async function loadProjects() {
    const container = document.getElementById('projectsList');
    try {
        const response = await fetch(BASE_URL + '/projects');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const data = await response.json();
        renderProjects(data.versions);
    } catch (e) {
        container.innerHTML = `<span style="color:#f44747;">⚠️ 加载失败: ${e.message}</span>`;
    }
}

function renderProjects(versions) {
    const container = document.getElementById('projectsList');
    if (!versions || versions.length === 0) {
        container.innerHTML = '<span style="color:#888;">暂无归档项目</span>';
        return;
    }
    let html = '';
    for (const v of versions) {
        const version = v.version;
        const projects = v.projects || [];
        if (projects.length === 0) continue;
        html += `<div style="margin-bottom:6px;"><strong style="color:#4ec9b0;">📁 ${version}</strong>`;
        html += `<div style="display:flex; flex-wrap:wrap; gap:6px; margin-top:4px; padding-left:12px;">`;
        for (const p of projects) {
            const name = p.name;
            const path = p.path;
            html += `<a href="${path}" target="_blank" style="background:#2d2d2d; padding:3px 10px; border-radius:12px; text-decoration:none; color:#9cdcfe; font-size:13px; border:1px solid #444;">${name}</a>`;
        }
        html += `</div></div>`;
    }
    container.innerHTML = html;
}

// ============================================================
// @anchor: script_heartbeat
// ===== 心跳机制 =====
// ============================================================

function startHeartbeat() {
    if (heartbeatInterval) return;
    heartbeatInterval = setInterval(() => {
        fetch(BASE_URL + '/heartbeat', { method: 'POST' }).catch(() => { /* 静默失败 */ });
    }, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
}

// ============================================================
// @anchor: script_log
// ===== 日志输出模块 =====
// ============================================================

function isMarkdown(text) {
    return /(\|\s*[-:]+[\s|]+\||^#{1,6}\s|\n```|\n\s*[-*]\s|\n>\s|^---\s*$|\n---\s*$)/m.test(text);
}

function renderMarkdown(text) {
    try {
        return marked.parse(text, { gfm: true, breaks: true });
    } catch (e) {
        return escapeHtml(text);
    }
}

function appendLog(msg) {
    let color = 'log-info';
    if (msg.startsWith('[错误]')) color = 'log-error';
    else if (msg.startsWith('[完成]') || msg.includes('✅')) color = 'log-success';
    else if (msg.startsWith('[系统]')) color = 'log-system';

    let renderedContent;

    if (msg.startsWith('[系统]')) {
        renderedContent = escapeHtml(msg);
    } else if (msg.startsWith('📁 ') && (msg.includes('项') || msg.includes('内容'))) {
        renderedContent = `<pre style="margin:0; font-family:inherit; white-space:pre-wrap;">${escapeHtml(msg)}</pre>`;
    } else {
        try {
            let cleanMsg = msg;
            if (msg.startsWith('[完成] ')) {
                cleanMsg = msg.substring(4);
            }
            renderedContent = marked.parse(cleanMsg, { gfm: true, breaks: true });
        } catch (e) {
            renderedContent = escapeHtml(msg);
        }
    }

    const line = `<div class="log-entry ${color}">${renderedContent}</div>`;
    output.innerHTML += line;
    output.scrollTop = output.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ============================================================
// @anchor: script_sse
// ===== SSE 流式请求模块 =====
// ============================================================

function runAgent(prompt) {
    output.innerHTML = '连接服务...\n';
    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;
    startHeartbeat();

    fetch(BASE_URL + '/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt })
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
                        finishRun();
                        return;
                    }
                    buffer += decoder.decode(value, { stream: true });
                    const events = buffer.split('\n\n');
                    buffer = events.pop();
                    for (const event of events) {
                        if (event.startsWith('data: ')) {
                            const data = event.substring(6).trim().replace(/\\n/g, '\n');
                            if (data === '[结束]') {
                                finishRun();
                                return;
                            }
                            appendLog(data);
                        }
                    }
                    readChunk();
                }).catch(err => {
                    appendLog('[错误] ' + err.message);
                    finishRun();
                });
            }
            readChunk();
        })
        .catch(err => {
            appendLog('[连接错误] ' + err.message + '\n请确保 HTTP 服务已启动（运行 HttpServerMain）');
            finishRun();
        });
}

function finishRun() {
    runBtn.disabled = false;
    stopBtn.disabled = true;
    isRunning = false;
    stopHeartbeat();
}

// @anchor: script_stop
function stopAgent() {
    if (!isRunning) return;
    stopBtn.disabled = true;
    appendLog('[系统] 正在停止任务...');

    fetch(BASE_URL + '/stop', { method: 'POST' })
        .then(response => response.json())
        .then(data => {
            appendLog('[系统] ' + data.message);
            stopBtn.disabled = true;
            runBtn.disabled = false;
            isRunning = false;
            stopHeartbeat();
        })
        .catch(err => {
            appendLog('[错误] 停止请求失败: ' + err.message);
            stopBtn.disabled = false;
            stopHeartbeat();
        });
}

// ============================================================
// @anchor: script_events
// ===== 事件绑定 =====
// ============================================================

clearBtn.addEventListener('click', () => {
    output.innerHTML = '';
});

runBtn.addEventListener('click', () => {
    const prompt = promptInput.value.trim();
    if (!prompt) {
        alert('请输入需求');
        return;
    }
    runAgent(prompt);
    addToHistory(prompt);
    renderHistory();
});

stopBtn.addEventListener('click', stopAgent);

// 页面关闭时尝试停止
window.addEventListener('beforeunload', () => {
    if (isRunning) {
        stopHeartbeat();
        fetch(BASE_URL + '/stop', { method: 'POST' }).catch(() => {});
    }
});

// 展开/折叠项目列表
document.getElementById('toggleProjectsBtn').addEventListener('click', function () {
    const container = document.getElementById('projectsContainer');
    const isHidden = container.style.display === 'none';
    container.style.display = isHidden ? 'block' : 'none';
    this.textContent = isHidden ? '📂 历史成型项目 (点击收起)' : '📂 历史成型项目 (点击展开)';
    if (isHidden && !container.dataset.loaded) {
        container.dataset.loaded = 'true';
        loadProjects();
    }
});

// 页面加载时恢复上次输入
window.addEventListener('DOMContentLoaded', function () {
    const history = getHistory();
    if (history.length > 0) {
        promptInput.value = history[0];
    }
    renderHistory();
});

// @anchor: script_visibility
// ===== 页面可见性变化：切回前台时立即心跳，防止后端超时断开 =====
document.addEventListener('visibilitychange', function() {
    if (!document.hidden) {
        // 页面重新变为可见（用户切回来了）
        console.log('🔄 页面回到前台，立即发送心跳续命...');
        fetch(BASE_URL + '/heartbeat', { method: 'POST' }).catch(() => {
            // 静默失败，避免干扰
        });
    }
});

// ============================================================
// @anchor: script_iterationTip
// ===== 迭代提示框（随机文案） =====
// ============================================================

const tipElement = document.getElementById('iterationTip');
let lastTip = '';

function showRandomTip() {
    if (!QUOTES || QUOTES.length === 0) return;
    let newTip;
    do {
        newTip = QUOTES[Math.floor(Math.random() * QUOTES.length)];
    } while (newTip === lastTip && QUOTES.length > 1);
    lastTip = newTip;
    tipElement.textContent = newTip;
    tipElement.style.opacity = '1';
    // 5秒后淡出（如果新消息没来）
    clearTimeout(tipElement._timeout);
    tipElement._timeout = setTimeout(() => {
        tipElement.style.opacity = '0';
    }, 5000);
}

// 在 appendLog 中检测迭代标记
const originalAppendLog = appendLog;
appendLog = function(msg) {
    // 先执行原逻辑
    originalAppendLog(msg);
    // 检测是否包含迭代标记
    if (msg.includes('--- 第') && msg.includes('次迭代 ---')) {
        showRandomTip();
    }
};

// 如果页面刚加载，也可以先显示一句（但此时无迭代，可不显示）
// 或者当运行开始时显示一个初始句
// 在 runAgent 开始时也可以调用一次
const originalRunAgent = runAgent;
runAgent = function(prompt) {
    // 显示第一句
    if (QUOTES && QUOTES.length) {
        tipElement.textContent = QUOTES[0]; // 或随机
        tipElement.style.opacity = '1';
        clearTimeout(tipElement._timeout);
        tipElement._timeout = setTimeout(() => {
            tipElement.style.opacity = '0';
        }, 5000);
    }
    originalRunAgent(prompt);
};
