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
// @anchor: script_quote
// ===== 随机文案抽取 =====
// ============================================================
function getRandomQuote() {
    if (!QUOTES || QUOTES.length === 0) return '';
    const idx = Math.floor(Math.random() * QUOTES.length);
    return QUOTES[idx];
}

// ============================================================
// @anchor: script_log
// ===== 日志输出模块（已整合迭代提示） =====
// ============================================================

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

    // --- 新增：如果这条日志是迭代标记，则追加一条提示 ---
    if (msg.includes('--- 第') && msg.includes('次迭代 ---')) {
        const tip = getRandomQuote();
        if (tip) {
            const tipLine = `<div class="log-entry log-tip">> ${escapeHtml(tip)}</div>`;
            output.innerHTML += tipLine;
            output.scrollTop = output.scrollHeight;
        }
    }
}

// ============================================================
// @anchor: script_sidebarToggle
// ===== 侧边栏折叠切换 =====
// ============================================================
const sidebar = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggleSidebarBtn');

if (toggleBtn && sidebar) {
    toggleBtn.addEventListener('click', function() {
        sidebar.classList.toggle('collapsed');
        this.textContent = sidebar.classList.contains('collapsed') ? '▶' : '◀';
    });
}

// ============================================================
// @anchor: script_tree
// ===== 文件树核心逻辑 =====
// ============================================================

// ============================================================
// @anchor: script_tree
// ===== 文件树核心逻辑（第4步：差异化交互） =====
// ============================================================

/**
 * 调用后端 /browse 接口获取目录内容
 */
async function fetchDir(path) {
    const url = `/browse?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    }
    return await res.json();
}

/**
 * 渲染一个树节点
 * @param {string} path           - 相对路径
 * @param {HTMLElement} container - 父容器
 * @param {boolean} isRoot        - 是否为根节点
 * @param {boolean} hasIndexHtml  - 该目录是否包含 index.html（TestProjects 专用）
 * @param {boolean} isTestProjects - 是否在 TestProjects 下
 */
function renderTreeNode(path, container, isRoot = false, hasIndexHtml = false, isTestProjects = false) {
    const wrapper = document.createElement('div');
    wrapper.className = 'tree-node';
    wrapper.dataset.path = path;

    const displayName = path.split('/').pop() || path;

    // ---- 创建节点标签 ----
    const label = document.createElement('div');
    label.className = 'tree-item';
    if (isRoot) {
        label.style.fontWeight = 'bold';
        label.style.color = '#9cdcfe';
    }

    // 图标
    const iconSpan = document.createElement('span');
    iconSpan.textContent = '📁 ';
    label.appendChild(iconSpan);

    // 名称
    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = displayName;
    label.appendChild(nameSpan);

    // ---- 如果是 TestProjects 下的项目入口，添加 🚀 标记 ----
    if (!isRoot && isTestProjects && hasIndexHtml) {
        const badge = document.createElement('span');
        badge.textContent = ' 🚀';
        badge.style.color = '#4ec9b0';
        badge.style.fontSize = '12px';
        label.appendChild(badge);
    }

    wrapper.appendChild(label);

    // ---- 子节点容器（初始隐藏） ----
    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'tree-children';
    childrenContainer.style.display = 'none';
    wrapper.appendChild(childrenContainer);

    // ---- 判断是否为项目入口（TestProjects + 含 index.html） ----
    const isProjectEntry = !isRoot && isTestProjects && hasIndexHtml;

    // ---- 点击事件：项目入口 → 打开预览，否则 → 展开/折叠 ----
    label.addEventListener('click', async (e) => {
        e.stopPropagation();

        if (isProjectEntry) {
            // 项目入口：直接打开预览
            openProjectPreview(path);
            return;
        }

        // 非项目入口：展开/折叠
        const isHidden = childrenContainer.style.display === 'none';

        if (isHidden) {
            // 懒加载
            if (!childrenContainer.dataset.loaded) {
                try {
                    const data = await fetchDir(path);
                    childrenContainer.dataset.loaded = 'true';

                    if (data.entries && data.entries.length > 0) {
                        for (const entry of data.entries) {
                            const childPath = path + '/' + entry.name;
                            if (entry.type === 'dir') {
                                // 传递 hasIndexHtml 和是否为 TestProjects 路径
                                const childIsTestProjects = childPath.startsWith('TestProjects/');
                                renderTreeNode(
                                    childPath,
                                    childrenContainer,
                                    false,
                                    entry.hasIndexHtml || false,
                                    childIsTestProjects
                                );
                            } else {
                                // 文件节点
                                renderFileNode(childPath, entry.name, childrenContainer);
                            }
                        }
                    } else {
                        showEmptyMessage(childrenContainer);
                    }
                } catch (err) {
                    console.error('加载目录失败:', err);
                    showErrorMessage(childrenContainer, err.message);
                }
            }

            childrenContainer.style.display = 'block';
        } else {
            childrenContainer.style.display = 'none';
        }
    });

    container.appendChild(wrapper);
}

/**
 * 渲染文件节点
 */
function renderFileNode(filePath, fileName, container) {
    const fileDiv = document.createElement('div');
    fileDiv.className = 'tree-item';
    fileDiv.dataset.path = filePath;

    const fileIcon = document.createElement('span');
    const ext = fileName.split('.').pop().toLowerCase();
    if (ext === 'html' || ext === 'htm') fileIcon.textContent = '🌐 ';
    else if (ext === 'css') fileIcon.textContent = '🎨 ';
    else if (ext === 'js') fileIcon.textContent = '⚡ ';
    else if (ext === 'java') fileIcon.textContent = '☕ ';
    else if (ext === 'json') fileIcon.textContent = '📋 ';
    else if (ext === 'md') fileIcon.textContent = '📝 ';
    else if (['png', 'jpg', 'jpeg', 'svg', 'gif', 'ico'].includes(ext)) fileIcon.textContent = '🖼️ ';
    else fileIcon.textContent = '📄 ';
    fileDiv.appendChild(fileIcon);

    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = fileName;
    fileDiv.appendChild(nameSpan);

    fileDiv.addEventListener('click', (e) => {
        e.stopPropagation();
        handleFileClick(filePath);
    });

    container.appendChild(fileDiv);
}

/**
 * 打开项目预览（TestProjects 下含 index.html 的目录）
 * 复用 ExternalFileHandler 的路径映射
 */
function openProjectPreview(projectPath) {
    // 确保路径以 TestProjects/ 开头
    if (!projectPath.startsWith('TestProjects/')) {
        appendLog('[系统] 非 TestProjects 项目，无法预览');
        return;
    }

    // 构造访问路径：/TestProjects/.../index.html
    const url = '/' + projectPath + '/index.html';
    appendLog(`[系统] 打开项目预览: ${url}`);
    window.open(url, '_blank');
}

/**
 * 处理文件点击
 */
function handleFileClick(filePath) {
    appendLog(`[系统] 点击文件: ${filePath}`);

    // 如果是以 .html 结尾，尝试在新窗口打开
    if (filePath.endsWith('.html') || filePath.endsWith('.htm')) {
        if (filePath.startsWith('TestProjects/')) {
            const url = '/' + filePath;
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else {
            appendLog('[系统] sandbox 下的 HTML 文件暂不支持直接预览');
        }
    }
}

/**
 * 辅助：显示空目录
 */
function showEmptyMessage(container) {
    const emptyMsg = document.createElement('div');
    emptyMsg.className = 'tree-item';
    emptyMsg.style.color = '#666';
    emptyMsg.style.fontStyle = 'italic';
    emptyMsg.textContent = '📭 空目录';
    container.appendChild(emptyMsg);
}

/**
 * 辅助：显示错误信息
 */
function showErrorMessage(container, msg) {
    const errMsg = document.createElement('div');
    errMsg.className = 'tree-item';
    errMsg.style.color = '#f44747';
    errMsg.textContent = '⚠️ 加载失败: ' + msg;
    container.appendChild(errMsg);
}

// ===== 初始化：挂载两个根节点 =====
document.addEventListener('DOMContentLoaded', function() {
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    sidebarContent.innerHTML = '';

    // sandbox 根节点：纯目录浏览
    renderTreeNode('sandbox', sidebarContent, true, false, false);

    // TestProjects 根节点：开启项目入口检测
    renderTreeNode('TestProjects', sidebarContent, true, false, true);
});