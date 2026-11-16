// @anchor: ulThreadList_intro
// 左栏线程列表：状态点、记录数、检索过滤与选中联动

// @anchor: ulThreadList_render
// 渲染列表主体：过滤 → 逐项构建 → 空状态
function ulRenderThreadList() {
    const container = ULDom.get('ulThreadList');
    if (!container) return;
    if (UL.loading) {
        container.innerHTML = '<div class="ul-placeholder">加载中…</div>';
        ulRenderSidebarFooter([]);
        return;
    }
    if (UL.error) {
        container.innerHTML = '';
        ulRenderErrorBlock(container, UL.error, () => ulLoadData());
        ulRenderSidebarFooter([]);
        return;
    }
    const visible = ulFilterThreads(UL.threads, UL.search);
    ulRenderSidebarFooter(visible);

    if (!UL.threads.length) {
        container.innerHTML = '<div class="ul-placeholder">该项目的更新档案还是空的。<br>点击右上角「＋ 新建主题」开始记录吧。</div>';
        return;
    }
    if (!visible.length) {
        container.innerHTML = '<div class="ul-placeholder">没有匹配「' + ulEscapeHtml(UL.search) + '」的线程。</div>';
        return;
    }

    const fragment = document.createDocumentFragment();
    visible.forEach((thread) => fragment.appendChild(ulBuildThreadItem(thread)));
    container.innerHTML = '';
    container.appendChild(fragment);
}

// @anchor: ulThreadList_item
// 单条线程项：状态点 + 标题 + 元信息 + 记录数 + 状态切换按钮
function ulBuildThreadItem(thread) {
    const item = document.createElement('div');
    item.className = 'ul-thread-item' + (thread.thread_id === UL.selectedThreadId ? ' active' : '');
    item.dataset.threadId = thread.thread_id;
    item.setAttribute('role', 'button');
    item.tabIndex = 0;

    const dot = document.createElement('span');
    dot.className = 'ul-status-dot ' + (thread.status === 'closed' ? 'closed' : 'active');
    dot.title = thread.status === 'closed' ? '已关闭' : '活跃';
    item.appendChild(dot);

    const main = document.createElement('span');
    main.className = 'ul-thread-main';
    const title = document.createElement('span');
    title.className = 'ul-thread-title';
    title.innerHTML = ulHighlight(thread.title, UL.search);
    main.appendChild(title);
    const meta = document.createElement('span');
    meta.className = 'ul-thread-meta';
    const updated = ulLastUpdated(thread);
    meta.innerHTML = '<code>' + ulHighlight(thread.thread_id, UL.search) + '</code>'
        + (updated ? ' · ' + ulEscapeHtml(ulRelativeTime(updated)) : ' · 暂无记录');
    main.appendChild(meta);
    item.appendChild(main);

    const count = document.createElement('span');
    count.className = 'ul-thread-count';
    count.textContent = String(ulUpdateCount(thread));
    count.title = '共 ' + ulUpdateCount(thread) + ' 条记录';
    item.appendChild(count);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'ul-thread-toggle' + (thread.status === 'closed' ? ' is-closed' : '');
    toggle.dataset.role = 'toggle-status';
    toggle.dataset.threadId = thread.thread_id;
    toggle.dataset.ulWrite = '';
    toggle.textContent = thread.status === 'closed' ? '重开' : '关闭';
    toggle.title = thread.status === 'closed' ? '重新激活该线程' : '关闭该线程（仍可手动追加记录，但会提示）';
    item.appendChild(toggle);
    return item;
}

// @anchor: ulThreadList_footer
// 侧栏底部统计：线程数 / 记录总数 / 活跃线程
function ulRenderSidebarFooter(visible) {
    const footer = ULDom.get('ulSidebarFooter');
    if (!footer) return;
    if (!UL.threads.length) {
        footer.innerHTML = '<span>0 个主题 · 0 条记录</span>';
        return;
    }
    const totalRecords = UL.threads.reduce((sum, thread) => sum + ulUpdateCount(thread), 0);
    const activeCount = UL.threads.filter((thread) => thread.status !== 'closed').length;
    const filteredHint = UL.search.trim() && visible && visible.length !== UL.threads.length
        ? '<span class="ul-footer-hint">筛选 ' + visible.length + '/' + UL.threads.length + '</span>'
        : '';
    footer.innerHTML = '<span>' + UL.threads.length + ' 个主题 · ' + totalRecords + ' 条记录</span>'
        + '<span class="ul-footer-dim">' + activeCount + ' 活跃</span>' + filteredHint;
}

// @anchor: ulThreadList_error
// 通用错误块：文案 + 重试按钮（列表与时间线共用）
function ulRenderErrorBlock(container, message, onRetry) {
    const block = document.createElement('div');
    block.className = 'ul-placeholder ul-placeholder-error';
    block.innerHTML = '<div class="ul-error-title">⚠️ 读取失败</div><div>' + ulEscapeHtml(message) + '</div>';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'ul-btn ul-btn-ghost ul-btn-sm';
    retry.textContent = '重试';
    retry.addEventListener('click', onRetry);
    block.appendChild(retry);
    container.innerHTML = '';
    container.appendChild(block);
}

// @anchor: ulThreadList_bind
// 交互绑定：点击选中、键盘可达、状态切换按钮冒泡拦截
function ulBindThreadList() {
    const container = ULDom.get('ulThreadList');
    if (!container) return;

    container.addEventListener('click', (event) => {
        const toggle = event.target.closest('[data-role="toggle-status"]');
        if (toggle) {
            event.stopPropagation();
            ulToggleThreadStatus(toggle.dataset.threadId);
            return;
        }
        const item = event.target.closest('.ul-thread-item');
        if (item) ulSelectThread(item.dataset.threadId);
    });

    container.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        const item = event.target.closest('.ul-thread-item');
        if (!item) return;
        event.preventDefault();
        ulSelectThread(item.dataset.threadId);
    });
}

// @anchor: ulThreadList_select
// 选中线程：更新状态并联动右栏（移动端额外收起抽屉）
function ulSelectThread(threadId) {
    if (!threadId || threadId === UL.selectedThreadId) {
        ulRenderTimeline();
        return;
    }
    UL.selectedThreadId = threadId;
    ulRenderThreadList();
    ulRenderTimeline();
    document.body.classList.remove('ul-drawer-open');
}
