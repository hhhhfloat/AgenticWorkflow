// @anchor: modules_events
// ===== 事件绑定 =====

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
    const maxIterations = parseInt(document.getElementById('maxIterations').value) || 20;
    runAgent(prompt, maxIterations);
    addToHistory(prompt);
    renderHistory();
});

// 停止按钮
stopBtn.addEventListener('click', stopAgent);

//window.addEventListener('beforeunload', () => {
//    if (isRunning) {
//        stopHeartbeat();
//        // 使用 keepalive: true 确保请求在页面关闭后依然发出
//        fetch(BASE_URL + '/stop', {
//            method: 'POST',
//            keepalive: true
//        }).catch(() => {});
//    }
//});

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
document.addEventListener('DOMContentLoaded', function() {
    loadConfig();
    // ⭐ 新增：查询后端状态，恢复 UI
    if (typeof checkBackendStatus === 'function') {
        checkBackendStatus();
    }

    // 初始化配置弹窗
    if (typeof initSettingsModal === 'function') {
        initSettingsModal();
    }

    const history = getHistory();
    if (history.length > 0) {
        promptInput.value = history[0];
    }
    renderHistory();

    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;
    sidebarContent.innerHTML = '';
    renderTreeNode('sandbox', sidebarContent, true, false, false);
    renderTreeNode('TestProjects', sidebarContent, true, false, true);

    const iterInput = document.getElementById('maxIterations');
    iterInput.addEventListener('change', function() {
        let val = parseInt(this.value);
        if (isNaN(val)) val = 30;
        if (val < 3) val = 3;
        if (val > 100) val = 100;
        this.value = val;
    });
    startHeartbeat();
});
