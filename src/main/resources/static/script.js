// @anchor: script_domRefs
// ===== DOM 引用 =====
const output = document.getElementById('output');
const runBtn = document.getElementById('runBtn');
const stopBtn = document.getElementById('stopBtn');
const clearBtn = document.getElementById('clearBtn');
const promptInput = document.getElementById('prompt');

// ===== 配置 marked 渲染器：所有链接在新标签页打开 =====
const renderer = new marked.Renderer();
renderer.link = function(href, title, text) {
    // 保留原有链接功能，添加 target="_blank"
    return `<a href="${href}" target="_blank" rel="noopener noreferrer"${title ? ` title="${title}"` : ''}>${text}</a>`;
};
marked.use({ renderer });

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

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ============================================================
// @anchor: script_sse
// ===== SSE 流式请求模块 =====
// ============================================================

function runAgent(prompt, maxIterations) {
    output.innerHTML = '连接服务...\n';
    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;
    startHeartbeat();

    fetch(BASE_URL + '/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            prompt: prompt,
            maxIterations: maxIterations  // 新增
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
    // 刷新沙箱目录树（Agent 可能创建了新文件）
    refreshSandbox();
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

// runBtn 点击事件中获取值
runBtn.addEventListener('click', () => {
    const prompt = promptInput.value.trim();
    if (!prompt) {
        alert('请输入需求');
        return;
    }
    const maxIterations = parseInt(document.getElementById('maxIterations').value) || 20;
    runAgent(prompt, maxIterations);
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

    // ---- 如果是 sandbox 根节点，添加操作按钮 ----
    if (isRoot && path === 'sandbox') {
        // 1. 打开文件夹按钮
        const openBtn = document.createElement('button');
        openBtn.className = 'tree-action-btn';
        openBtn.title = '📂 在文件管理器中打开 sandbox 文件夹';
        openBtn.textContent = '📂';
        openBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await openFolder('sandbox');
        });
        label.appendChild(openBtn);

        // 2. 新建项目按钮
        const createBtn = document.createElement('button');
        createBtn.className = 'tree-action-btn';
        createBtn.title = '➕ 在 sandbox 下创建新项目文件夹';
        createBtn.textContent = '➕';
        createBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const projectName = prompt('请输入新项目名称（仅允许字母、数字、- 和 _）：');
            if (projectName && projectName.trim()) {
                await createProject(projectName.trim());
            }
        });
        label.appendChild(createBtn);
    }



    // ---- 如果是 TestProjects 下的项目入口，添加 🚀 标记 ----
    if (!isRoot && isTestProjects && hasIndexHtml) {
        const badge = document.createElement('span');
        badge.textContent = ' 🚀';
        badge.style.color = '#4ec9b0';
        badge.style.fontSize = '12px';
        label.appendChild(badge);
    }

    // ---- 如果是 sandbox 下的一级子目录，添加归档按钮和上传按钮 ----
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    if (isSandboxProject) {
        const projectName = parts[1];

        // 归档按钮（原有）
        const archiveBtn = document.createElement('button');
        archiveBtn.className = 'tree-action-btn';
        archiveBtn.title = '📦 归档此项目到 TestProjects';
        archiveBtn.textContent = '📦';
        archiveBtn.dataset.project = projectName;
        archiveBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await archiveProject(projectName);
        });
        label.appendChild(archiveBtn);

        // 新增：上传文件按钮
        const uploadBtn = document.createElement('button');
        uploadBtn.className = 'tree-action-btn';
        uploadBtn.title = '📤 上传文件到此项目';
        uploadBtn.textContent = '📤';
        uploadBtn.dataset.project = projectName;
        uploadBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            // 创建隐藏的 file input
            const input = document.createElement('input');
            input.type = 'file';
            input.multiple = true;
            input.style.display = 'none';
            document.body.appendChild(input);
            input.addEventListener('change', async () => {
                if (input.files && input.files.length > 0) {
                    await uploadFiles(projectName, input.files);
                }
                document.body.removeChild(input);
            });
            input.click();
        });
        label.appendChild(uploadBtn);
    }
    // ---- 如果是 TestProjects 根节点，添加打开文件夹按钮 ----
    if (isRoot && path === 'TestProjects') {
        const openBtn = document.createElement('button');
        openBtn.className = 'tree-action-btn';
        openBtn.title = '📂 在文件管理器中打开 TestProjects 文件夹';
        openBtn.textContent = '📂';
        openBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await openFolder('TestProjects');
        });
        label.appendChild(openBtn);
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

    if (filePath.endsWith('.html') || filePath.endsWith('.htm')) {
        if (filePath.startsWith('TestProjects/')) {
            const url = '/' + filePath;
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else if (filePath.startsWith('sandbox/')) {
            const url = '/' + filePath;  // 同样构造 /sandbox/xxx/index.html
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else {
            appendLog('[系统] 无法预览此文件');
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

document.addEventListener('DOMContentLoaded', function() {

    loadConfig(); // 新增

    // 历史记录恢复
    const history = getHistory();
    if (history.length > 0) {
        promptInput.value = history[0];
    }
    renderHistory();

    // 树渲染
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;
    sidebarContent.innerHTML = '';
    renderTreeNode('sandbox', sidebarContent, true, false, false);
    renderTreeNode('TestProjects', sidebarContent, true, false, true);

    const iterInput = document.getElementById('maxIterations');
    iterInput.addEventListener('change', function() {
        let val = parseInt(this.value);
        if (isNaN(val)) val = 20;
        if (val < 3) val = 3;
        if (val > 50) val = 50;
        this.value = val;
    });
});

// 从后端加载配置
async function loadConfig() {
    try {
        const res = await fetch('/config');
        const config = await res.json();
        if (config.maxIterations) {
            const input = document.getElementById('maxIterations');
            if (input) {
                input.value = config.maxIterations;
                // 同时更新 change 事件里的兜底值（通过全局变量或直接修改）
            }
        }
    } catch (e) {
        // 配置加载失败，使用硬编码兜底（30）
        console.warn('配置加载失败，使用默认值');
    }
}

document.getElementById('logBtn').addEventListener('click', () => {
    openFolder('HistoryOutput');
});

// ============================================================
// @anchor: script_archive
// ===== 归档功能 =====
// ============================================================

async function archiveProject(projectName) {
    // 防止重复点击
    const btn = document.querySelector(`.archive-btn[data-project="${projectName}"]`);
    if (btn) btn.disabled = true;

    try {
        // 1. 第一次请求：尝试归档
        let res = await fetch('/archive', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectName })
        });

        let data = await res.json();

        // 2. 如果目标已存在，询问用户是否覆盖
        if (data.status === 'exists') {
            const confirmMsg = `项目 "${projectName}" 已存在于 TestProjects/v0_0/，是否覆盖？`;
            if (!confirm(confirmMsg)) {
                appendLog(`[系统] 已取消归档: ${projectName}`);
                return;
            }

            // 用户确认覆盖：强制归档
            res = await fetch('/archive?force=true', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ projectName })
            });
            data = await res.json();
        }

        // 3. 处理结果
        if (data.status === 'success') {
            appendLog(`[系统] ✅ 项目已归档: ${data.path}`);
            // 刷新侧边栏
            refreshSidebar();
        } else {
            appendLog(`[系统] ❌ 归档失败: ${data.message || '未知错误'}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 归档失败: ${err.message}`);
    } finally {
        if (btn) btn.disabled = false;
    }
}

/**
 * 刷新侧边栏：重新加载 TestProjects 目录
 */
function refreshSidebar() {
    // 找到 TestProjects 根节点
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    // 找到 TestProjects 对应的树节点
    const testRoot = sidebarContent.querySelector('[data-path="TestProjects"]');
    if (!testRoot) return;

    // 找到它的子节点容器（即 tree-node 内部的 tree-children）
    const treeNode = testRoot.closest('.tree-node');
    if (!treeNode) return;

    const childrenContainer = treeNode.querySelector('.tree-children');
    if (!childrenContainer) return;

    // 清空并重置状态
    childrenContainer.innerHTML = '';
    childrenContainer.dataset.loaded = '';
    childrenContainer.style.display = 'none';

    // 触发点击事件重新加载
    const label = treeNode.querySelector('.tree-item');
    if (label) {
        label.click();
    }
}

async function createProject(projectName) {
    // 前端二次校验：只允许字母、数字、- 和 _
    if (!/^[a-zA-Z0-9\-_]+$/.test(projectName)) {
        alert('项目名仅允许字母、数字、- 和 _');
        return;
    }
    try {
        const res = await fetch('/createProject', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectName })
        });
        const data = await res.json();
        if (data.status === 'success') {
            appendLog(`[系统] ✅ 项目已创建: ${data.path}`);
            refreshSandbox();  // 刷新侧边栏
        } else if (data.status === 'exists') {
            alert(`项目 "${projectName}" 已存在，请换一个名称`);
        } else {
            appendLog(`[系统] ❌ 创建失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 创建失败: ${err.message}`);
    }
}

async function uploadFiles(projectName, fileList) {
    if (!fileList || fileList.length === 0) return;

    const formData = new FormData();
    formData.append('projectName', projectName);
    for (const file of fileList) {
        formData.append('files', file);
    }

    try {
        // 第一次请求：检查是否存在
        let res = await fetch('/upload', {
            method: 'POST',
            body: formData
        });
        let data = await res.json();

        // 如果有文件已存在，询问用户是否覆盖
        if (data.status === 'check' && data.existing && data.existing.length > 0) {
            const confirmMsg = `以下 ${data.existing.length} 个文件已存在：\n${data.existing.join('\n')}\n\n是否覆盖？`;
            if (!confirm(confirmMsg)) {
                appendLog(`[系统] 已取消上传: ${projectName}`);
                return;
            }
            // 强制覆盖
            res = await fetch('/upload?force=true', {
                method: 'POST',
                body: formData
            });
            data = await res.json();
        }

        // 处理结果
        if (data.status === 'success' || data.status === 'partial') {
            const uploaded = data.uploaded || [];
            const failed = data.failed || [];
            let msg = `✅ 上传完成: ${uploaded.length} 个文件`;
            if (failed.length > 0) {
                msg += `，${failed.length} 个失败 (${failed.join(', ')})`;
            }
            appendLog(`[系统] ${msg}`);
            refreshSandbox();  // 刷新侧边栏
        } else {
            appendLog(`[系统] ❌ 上传失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 上传失败: ${err.message}`);
    }
}

function refreshSandbox() {
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    // 找到 sandbox 根节点
    const sandboxRoot = sidebarContent.querySelector('[data-path="sandbox"]');
    if (!sandboxRoot) return;

    const treeNode = sandboxRoot.closest('.tree-node');
    if (!treeNode) return;

    const childrenContainer = treeNode.querySelector('.tree-children');
    if (!childrenContainer) return;

    // ✅ 递归清除所有子节点的缓存
    clearCacheRecursively(childrenContainer);

    // 清空并重置
    childrenContainer.innerHTML = '';
    childrenContainer.dataset.loaded = '';
    childrenContainer.style.display = 'none';

    // 触发重新加载
    const label = treeNode.querySelector('.tree-item');
    if (label) {
        label.click();
    }
}

function clearCacheRecursively(container) {
    // 清除所有 tree-node 的 dataset.loaded
    const nodes = container.querySelectorAll('.tree-node');
    nodes.forEach(node => {
        const childContainer = node.querySelector('.tree-children');
        if (childContainer) {
            childContainer.dataset.loaded = '';
            // 递归清除更深层的缓存
            clearCacheRecursively(childContainer);
        }
    });
}

// ============================================================
// @anchor: script_openFolder
// ===== 打开文件夹 =====
// ============================================================

async function openFolder(path) {
    try {
        const res = await fetch(`/openFolder?path=${encodeURIComponent(path)}`);
        const data = await res.json();
        if (data.status === 'success') {
            appendLog(`[系统] 📂 已打开 ${path} 文件夹`);
        } else {
            appendLog(`[系统] ❌ 打开失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 打开失败: ${err.message}`);
    }
}
