// @anchor: modules_events
// ===== 事件绑定 =====
// ===== 侧边栏视图切换 =====
function switchSidebarView(view) {
    const filesView = document.getElementById('filesView');
    const sessionsView = document.getElementById('sessionsView');
    const filesBtn = document.getElementById('viewFilesBtn');
    const sessionsBtn = document.getElementById('viewSessionsBtn');
    if (!filesView || !sessionsView) return;

    if (view === 'files') {
        filesView.style.display = '';
        sessionsView.style.display = 'none';
        filesBtn.classList.add('active');
        sessionsBtn.classList.remove('active');
    } else {
        filesView.style.display = 'none';
        sessionsView.style.display = '';
        filesBtn.classList.remove('active');
        sessionsBtn.classList.add('active');
        loadSessionList();
    }
}

document.getElementById('viewFilesBtn').addEventListener('click', () => switchSidebarView('files'));
document.getElementById('viewSessionsBtn').addEventListener('click', () => switchSidebarView('sessions'));
document.getElementById('newSessionBtn').addEventListener('click', createNewSession);
// ⭐ 新增：页面卸载标志，防止误报断联日志
window._isPageUnloading = false;

// 清空按钮
clearBtn.addEventListener('click', () => {
    output.innerHTML = '';
});

// 运行按钮
runBtn.addEventListener('click', () => {
    const prompt = promptInput.value.trim();
    if (!prompt) {
        alert('请输入需求');
        return;
    }
    const maxIterations = parseInt(document.getElementById('maxIterations').value) || 100;
    runAgent(prompt, maxIterations);
    addToHistory(prompt);

    // 清空输入框
    promptInput.value = '';
    // 触发 auto-resize，让高度回落到初始
    promptInput.dispatchEvent(new Event('input'));
});

// 停止按钮
stopBtn.addEventListener('click', stopAgent);


// ⭐ 关键修改：页面卸载时只标记，不杀 Agent，也不触发断联日志
window.addEventListener('beforeunload', () => {
    window._isPageUnloading = true;  // 标记正在卸载
    if (isRunning) {
        stopHeartbeat();  // 停止发送心跳
        // ⭐ 不发送 /stop，让后端自己超时清理
    }
});

// @anchor: modules_visibility
// ===== 页面可见性变化：切回前台时立即心跳，防止后端超时断开 =====
document.addEventListener('visibilitychange', function() {
    if (!document.hidden) {
        console.log('🔄 页面回到前台，立即发送心跳续命...');
        sendHeartbeat();
    }
});

// 日志按钮
document.getElementById('logBtn').addEventListener('click', () => {
    openFolder('HistoryOutput');
});

// @anchor: modules_init
// ===== DOMContentLoaded 初始化 =====
document.addEventListener('DOMContentLoaded', async function() {
    loadConfig();
    if (typeof checkBackendStatus === 'function') {
        checkBackendStatus();
    }
    if (typeof initSettingsModal === 'function') {
        initSettingsModal();
    }

    // 恢复上次会话
    await restoreCurrentSession();
    // 加载会话列表
    await loadSessionList();

    const history = getHistory();
    if (history.length > 0) {
        promptInput.value = history[0];
    }
    // 不再调用 renderHistory()

    const promptEl = document.getElementById('prompt');
    if (promptEl) {
        function autoResizePrompt() {
            promptEl.style.height = 'auto';
            promptEl.style.height = Math.min(promptEl.scrollHeight, 200) + 'px';
        }
        promptEl.addEventListener('input', autoResizePrompt);
        autoResizePrompt();
    }

    const sidebarContent = document.getElementById('sidebarContent');
    if (sidebarContent) {
        sidebarContent.innerHTML = '';
        renderTreeNode('sandbox', sidebarContent, true, false, false);
        renderTreeNode('TestProjects', sidebarContent, true, false, true);
    }

    const iterInput = document.getElementById('maxIterations');
    if (iterInput) {
        iterInput.addEventListener('change', function() {
            let val = parseInt(this.value);
            if (isNaN(val)) val = 30;
            if (val < 3) val = 3;
            if (val > 100) val = 100;
            this.value = val;
        });
    }
    startHeartbeat();
});
