// @anchor: mobile_intro
// 手机端入口：事件绑定与初始化

// ===== 事件绑定 =====
runBtn.addEventListener('click', () => {
    if (isRunning) stopAgent();
    else runAgent();
});
const switchControllerBtn = document.getElementById('switchControllerBtn');
if (switchControllerBtn) {
    switchControllerBtn.addEventListener('click', switchController);
}
menuBtn.addEventListener('click', openDrawer);
closeDrawerBtn.addEventListener('click', closeDrawer);
newSessionBtn.addEventListener('click', () => {
    if (newSessionBtn.dataset.mode === 'switch') {
            switchToWorkSession();
        } else {
        openCreateModal();
    }
});
// ===== 回到底部按钮 =====
const scrollBottomBtnEl = document.getElementById('scrollBottomBtn');
if (scrollBottomBtnEl) {
    scrollBottomBtnEl.addEventListener('click', () => {
        outputEl.scrollTop = outputEl.scrollHeight;
        updateScrollBottomBtn();
    });
}
outputEl.addEventListener('scroll', updateScrollBottomBtn);
closeCreateBtn.addEventListener('click', closeCreateModal);
cancelCreateBtn.addEventListener('click', closeCreateModal);
confirmCreateBtn.addEventListener('click', createSession);
newProjectName.addEventListener('keydown', e => {
    if (e.key === 'Enter') createSession();
});
tabSessionsBtn.addEventListener('click', () => switchDrawerTab('sessions'));
tabFilesBtn.addEventListener('click', () => switchDrawerTab('files'));
document.getElementById('shutdownBtn').addEventListener('click', shutdownMainService);

promptEl.addEventListener('input', () => {
    promptEl.style.height = 'auto';
    promptEl.style.height = Math.min(promptEl.scrollHeight, 120) + 'px';
});

// ===== 初始化 =====
async function initMobileApp() {
    currentSessionId = getStoredSessionId();
    await initMobileStatus();
    await loadSessionList();
    startHeartbeat();
    startStatusPolling();
}

initMobileApp().catch(e => {
    const el = document.getElementById('output');
    if (el) {
        el.innerHTML = '<div class="msg error">初始化失败：' + e.message +
        '<br><br>' + (e.stack || '') + '</div>';
    }
});