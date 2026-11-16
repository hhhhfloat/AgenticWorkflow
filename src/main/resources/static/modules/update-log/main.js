// @anchor: ulMain_intro
// 页面入口：解析 ?project=、主题与事件编排、首屏加载、跨标签页静默刷新

// @anchor: ulMain_renderAll
// 统一渲染入口：列表与时间线各自维护自己的区域，避免互相覆盖
function ulRenderAll() {
    ulRenderThreadList();
    ulRenderTimeline();
}

// @anchor: ulMain_project
// 解析项目参数：缺省回退 archive-demo，并把规范化的 URL 写回地址栏
function ulResolveProject() {
    let project = null;
    try {
        project = new URLSearchParams(window.location.search).get('project');
    } catch (err) {
        project = null;
    }
    
    // 清洗路径：去掉可能带有的 sandbox/ 前缀，因为后端 PathUtils.safeResolve 已经默认以 sandbox 为根
    let cleanProject = (project || '').trim();
    if (cleanProject.startsWith('sandbox/')) {
        cleanProject = cleanProject.substring(8);
    } else if (cleanProject.startsWith('/sandbox/')) {
        cleanProject = cleanProject.substring(9);
    }
    
    UL.project = cleanProject || 'archive-demo';

    const tag = ULDom.get('ulProjectTag');
    if (tag) {
        tag.textContent = '📌 ' + UL.project;
        tag.title = UL.project;
    }
    try {
        const url = new URL(window.location.href);
        url.searchParams.set('project', UL.project);
        window.history.replaceState(null, '', url.toString());
    } catch (err) {
        // file:// 等场景忽略
    }
}

// @anchor: ulMain_events
// 顶部工具条与搜索框交互
function ulBindChrome() {
    const refreshBtn = ULDom.get('ulRefreshBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', async () => {
            refreshBtn.classList.add('is-spinning');
            try {
                await ulLoadData();
            } finally {
                refreshBtn.classList.remove('is-spinning');
            }
        });
    }

    const newThreadBtn = ULDom.get('ulNewThreadBtn');
    if (newThreadBtn) newThreadBtn.addEventListener('click', () => ulCreateThreadFlow());

    const appendBtn = ULDom.get('ulAppendBtn');
    if (appendBtn) {
        appendBtn.addEventListener('click', () => ulOpenAppendModal({ threadId: UL.selectedThreadId }));
    }

    const searchInput = ULDom.get('ulSearchInput');
    const searchClear = ULDom.get('ulSearchClear');
    if (searchInput) {
        const applySearch = ulDebounce((value) => {
            UL.search = value;
            ulRenderThreadList();
            ulRenderTimeline();
        }, 120);
        searchInput.addEventListener('input', () => {
            if (searchClear) searchClear.hidden = !searchInput.value;
            applySearch(searchInput.value);
        });
        searchInput.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                searchInput.value = '';
                searchInput.dispatchEvent(new Event('input'));
            }
        });
    }
    if (searchClear) {
        searchClear.hidden = true;
        searchClear.addEventListener('click', () => {
            if (!searchInput) return;
            searchInput.value = '';
            searchInput.dispatchEvent(new Event('input'));
            searchInput.focus();
        });
    }

    const drawerBtn = ULDom.get('ulDrawerBtn');
    if (drawerBtn) {
        drawerBtn.addEventListener('click', () => {
            document.body.classList.toggle('ul-drawer-open');
        });
    }

    const backdrop = ULDom.get('ulDrawerBackdrop');
    if (backdrop) {
        backdrop.addEventListener('click', () => document.body.classList.remove('ul-drawer-open'));
    }

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && ulCloseTopModal()) {
            event.preventDefault();
            return;
        }
        const tag = (event.target.tagName || '').toLowerCase();
        if (event.key === '/' && tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
            event.preventDefault();
            if (searchInput) searchInput.focus();
        }
    });
}

// @anchor: ulMain_remoteSync
// 跨标签页同步：收到变更广播后静默重载（不打断当前输入弹窗）
function ulBindRemoteSync() {
    const reload = ulDebounce(async () => {
        UL.remoteReloading = true;
        try {
            await ulLoadData({ silent: true });
            ulToast('检测到其他窗口的修改，已同步', 'info', { key: 'ul-remote', duration: 1800 });
        } finally {
            UL.remoteReloading = false;
        }
    }, 320);
    ulWatchRemoteChange(reload);
}

// @anchor: ulMain_init
// 初始化：主题 → 项目 → 交互绑定 → 首屏加载
async function ulInit() {
    ulInitTheme();
    ulResolveProject();
    ulBindChrome();
    ulBindThreadList();
    ulBindTimeline();
    ulBindRemoteSync();
    await ulLoadData();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ulInit);
} else {
    ulInit();
}
