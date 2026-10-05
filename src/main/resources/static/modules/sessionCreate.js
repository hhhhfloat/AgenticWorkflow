// @anchor: sessionCreate_intro
// 新建会话弹窗：选择工作项目后创建会话并切换

// @anchor: sessionCreate_open
// 打开新建会话弹窗：拉取沙箱项目列表并渲染
async function openSessionCreateModal() {
    const listEl = document.getElementById('projectList');
    const modal = document.getElementById('sessionCreateModal');
    if (!listEl || !modal) return;

    listEl.innerHTML = '<div class="empty">加载中...</div>';
    modal.style.display = 'flex';

    try {
        const res = await fetch(BASE_URL + '/browse?path=sandbox');
        const data = await res.json();
        const projects = (data.entries || [])
            .filter(e => e.type === 'dir')
            .map(e => e.name);

        listEl.innerHTML = '';
        if (projects.length === 0) {
            listEl.innerHTML = '<div class="empty">沙箱下暂无项目，请先在左侧创建</div>';
            return;
        }

        projects.forEach(p => {
            const btn = document.createElement('button');
            btn.className = 'project-item';
            btn.textContent = '📁 ' + p;
            btn.addEventListener('click', () => createSessionWithProject(p));
            listEl.appendChild(btn);
        });
    } catch (err) {
        listEl.innerHTML = '<div class="empty">加载失败：' + err.message + '</div>';
    }
}

// @anchor: sessionCreate_create
// 用指定项目创建会话，成功后切换并刷新列表
async function createSessionWithProject(project) {
    try {
        const res = await fetch(BASE_URL + '/session/create', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({project})
        });
        const data = await res.json();
        if (data.status !== 'ok') {
            alert('创建失败：' + (data.message || '未知错误'));
            return;
        }

        closeSessionCreateModal();

        setCurrentSessionId(data.sessionId);
        await loadSessionHistory(data.sessionId);
        highlightCurrentSession();
        renderUsagePanel();
        await loadSessionList();
        updateProjectSelector(project);
    } catch (err) {
        alert('创建失败：' + err.message);
    }
}

// @anchor: sessionCreate_close
// 关闭新建会话弹窗
function closeSessionCreateModal() {
    const modal = document.getElementById('sessionCreateModal');
    if (modal) modal.style.display = 'none';
}

// @anchor: sessionCreate_updateSelector
// 更新顶部工作项目显示
function updateProjectSelector(project) {
    const el = document.getElementById('currentProjectName');
    if (!el) return;
    el.textContent = project || '未选择';
}

// @anchor: sessionCreate_bindEvents
// 绑定弹窗的关闭事件与遮罩点击
document.addEventListener('DOMContentLoaded', () => {
    const closeBtn = document.getElementById('closeSessionCreateBtn');
    if (closeBtn) closeBtn.addEventListener('click', closeSessionCreateModal);

    const modal = document.getElementById('sessionCreateModal');
    if (modal) {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) closeSessionCreateModal();
        });
    }
});