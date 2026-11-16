// @anchor: ulTimeline_intro
// 右栏：线程头部（状态/合并/追加）与垂直时间线（Markdown 详情 + 文件标签 + 记录操作）

// @anchor: ulTimeline_header
// 线程头部：标题、thread_id、状态徽标、记录数与线程级操作
function ulRenderThreadHeader() {
    const header = ULDom.get('ulThreadHeader');
    if (!header) return;
    const thread = UL.selectedThreadId ? ulFindThread(UL.selectedThreadId) : null;
    if (!thread) {
        header.innerHTML = '<div class="ul-header-empty">未选择主题</div>';
        return;
    }
    const closed = thread.status === 'closed';
    header.innerHTML = ''
        + '<div class="ul-header-main">'
        + '  <h1 class="ul-header-title">' + ulEscapeHtml(thread.title) + '</h1>'
        + '  <div class="ul-header-meta">'
        + '    <code class="ul-chip">' + ulEscapeHtml(thread.thread_id) + '</code>'
        + '    <span class="ul-chip ul-chip-status ' + (closed ? 'is-closed' : 'is-active') + '">'
        + (closed ? '已关闭' : '活跃') + '</span>'
        + '    <span class="ul-chip">' + ulUpdateCount(thread) + ' 条记录</span>'
        + (thread.created_at ? '    <span class="ul-chip ul-chip-dim">创建于 ' + ulEscapeHtml(thread.created_at) + '</span>' : '')
        + '  </div>'
        + '</div>'
        + '<div class="ul-header-actions">'
        + '  <button type="button" class="ul-btn ul-btn-ghost ul-btn-sm" data-ul-write data-role="thread-append">＋ 添加记录</button>'
        + '  <button type="button" class="ul-btn ul-btn-ghost ul-btn-sm" data-ul-write data-role="thread-merge">合并到其他…</button>'
        + '  <button type="button" class="ul-btn ul-btn-ghost ul-btn-sm" data-ul-write data-role="thread-status">'
        + (closed ? '重新激活' : '标记为已关闭') + '</button>'
        + '</div>';
}

// @anchor: ulTimeline_render
// 渲染时间线：从新到旧，一条记录一张卡片
function ulRenderTimeline() {
    const container = ULDom.get('ulTimeline');
    if (!container) return;
    ulRenderThreadHeader();

    if (UL.loading) {
        container.innerHTML = '<div class="ul-placeholder">加载中…</div>';
        return;
    }
    if (UL.error) {
        ulRenderErrorBlock(container, UL.error, () => ulLoadData());
        return;
    }
    if (!UL.threads.length) {
        container.innerHTML = '<div class="ul-placeholder">该项目还没有更新档案。<br>'
            + '点击右上角「＋ 新建主题」创建第一个主题线程。</div>';
        return;
    }
    const thread = UL.selectedThreadId ? ulFindThread(UL.selectedThreadId) : null;
    if (!thread) {
        container.innerHTML = '<div class="ul-placeholder">← 从左侧选择一个主题查看它的更新记录。</div>';
        return;
    }
    if (!thread.updates.length) {
        container.innerHTML = '<div class="ul-placeholder">「' + ulEscapeHtml(thread.title) + '」暂无记录。<br>'
            + '点击右上角「＋ 手动添加记录」写下第一条。</div>';
        return;
    }

    const list = document.createElement('div');
    list.className = 'ul-timeline';
    thread.updates.forEach((update, index) => {
        list.appendChild(ulBuildUpdateCard(thread, update, index));
    });
    container.innerHTML = '';
    container.appendChild(list);
}

// @anchor: ulTimeline_card
// 单条记录卡片：时间戳 / 摘要 / Markdown 详情 / 文件标签 / 右下角操作
function ulBuildUpdateCard(thread, update, index) {
    const article = document.createElement('article');
    article.className = 'ul-update-card';
    article.dataset.index = String(index);

    const node = document.createElement('span');
    node.className = 'ul-update-node';
    article.appendChild(node);

    const body = document.createElement('div');
    body.className = 'ul-update-body';

    const head = document.createElement('div');
    head.className = 'ul-update-head';
    head.innerHTML = '<time class="ul-update-time" title="' + ulEscapeHtml(ulRelativeTime(update.timestamp)) + '">'
        + ulEscapeHtml(ulFormatTimestamp(update.timestamp)) + '</time>'
        + '<span class="ul-update-index">#' + (index + 1) + '</span>';
    body.appendChild(head);

    const summary = document.createElement('h3');
    summary.className = 'ul-update-summary';
    summary.innerHTML = ulHighlight(update.summary || '（无摘要）', UL.search);
    body.appendChild(summary);

    if (update.details && update.details.trim()) {
        const details = document.createElement('div');
        details.className = 'ul-markdown';
        details.innerHTML = ulRenderMarkdown(update.details);
        body.appendChild(details);
    }

    const foot = document.createElement('div');
    foot.className = 'ul-update-foot';

    const files = document.createElement('div');
    files.className = 'ul-update-files';
    (update.files || []).forEach((file) => {
        const link = document.createElement('a');
        link.className = 'ul-file-badge';
        link.href = ulFileHref(file);
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.title = '在代码预览中打开 ' + UL.project + '/' + file;
        link.textContent = file;
        files.appendChild(link);
    });
    if (!files.children.length) {
        files.innerHTML = '<span class="ul-file-empty">未关联文件</span>';
    }
    foot.appendChild(files);

    const actions = document.createElement('div');
    actions.className = 'ul-update-actions';
    actions.innerHTML = ''
        + '<button type="button" class="ul-btn ul-btn-tiny" data-ul-write data-role="move">移动到…</button>'
        + '<button type="button" class="ul-btn ul-btn-tiny ul-btn-danger" data-ul-write data-role="delete">删除</button>';
    actions.dataset.threadId = thread.thread_id;
    actions.dataset.index = String(index);
    foot.appendChild(actions);

    body.appendChild(foot);
    article.appendChild(body);
    return article;
}

// @anchor: ulTimeline_bind
// 交互绑定：卡片删除/移动、头部按钮统一委托
function ulBindTimeline() {
    const timeline = ULDom.get('ulTimeline');
    if (timeline) {
        timeline.addEventListener('click', (event) => {
            const btn = event.target.closest('[data-role]');
            if (!btn) return;
            const holder = btn.closest('[data-index]');
            if (!holder) return;
            const threadId = holder.dataset.threadId || UL.selectedThreadId;
            const index = Number(holder.dataset.index);
            if (btn.dataset.role === 'delete') ulDeleteUpdate(threadId, index);
            if (btn.dataset.role === 'move') ulMoveUpdate(threadId, index);
        });
    }

    const header = ULDom.get('ulThreadHeader');
    if (header) {
        header.addEventListener('click', (event) => {
            const btn = event.target.closest('[data-role]');
            if (!btn) return;
            if (btn.dataset.role === 'thread-append') ulOpenAppendModal({ threadId: UL.selectedThreadId });
            if (btn.dataset.role === 'thread-merge') ulMergeThread(UL.selectedThreadId);
            if (btn.dataset.role === 'thread-status') ulToggleThreadStatus(UL.selectedThreadId);
        });
    }
}
