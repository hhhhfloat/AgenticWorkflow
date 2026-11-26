// @anchor: ulUpdateActions_intro
// 记录级操作：删除、移动到其他主题、手动追加（Markdown 左写右预览）
//
// 所有写操作都经过 UpdateArchive.lock() 串行化，并在提交前做「视图新鲜度」校验，
// 这样即使另一个 tab 刚改过数据，也不会误删/误移。

// @anchor: ulUpdateActions_guard
// 提交前守卫：同一时刻只允许一个写操作在途
function ulGuardBusy() {
    if (!UpdateArchive.isBusy()) return false;
    ulToast('上一个操作还没结束，请稍候', 'warn', { key: 'ul-busy' });
    return true;
}

function ulUpdateSignature(update) {
    return { timestamp: ulText(update && update.timestamp), summary: ulText(update && update.summary) };
}

// @anchor: ulUpdateActions_delete
// 删除记录：二次确认 → 新鲜度校验 → POST delete
async function ulDeleteUpdate(threadId, index) {
    const thread = ulFindThread(threadId);
    const update = thread && thread.updates[index];
    if (!thread || !update) {
        ulToast('记录已不存在，正在刷新', 'warn', { key: 'ul-stale' });
        await ulLoadData({ silent: true });
        return;
    }
    if (ulGuardBusy()) return;

    const confirmed = await ulConfirmModal({
        title: '删除记录',
        message: '将删除 [' + ulFormatTimestamp(update.timestamp) + '] ' + (update.summary || '（无摘要）')
            + '\n该操作不可撤销。',
        confirmText: '删除',
        cancelText: '保留'
    });
    if (!confirmed) return;

    const fresh = await ulEnsureFresh(threadId, index, ulUpdateSignature(update));
    if (!fresh.ok && fresh.reason === 'fetch-failed') {
        ulHandleActionError({ message: fresh.message });
        return;
    }
    if (!fresh.ok) {
        ulToast('数据已被其他窗口修改，已刷新列表，请重新确认', 'warn', { key: 'ul-stale', duration: 4000 });
        return;
    }

    try {
        await ulPostAction({ action: 'delete', threadId: threadId, updateIndex: index });
        ulToast('已删除记录', 'success');
        await ulLoadData({ silent: true });
    } catch (err) {
        ulHandleActionError(err);
    }
}

// @anchor: ulUpdateActions_move
// 移动记录：选择目标线程 → 新鲜度校验 → POST move
async function ulMoveUpdate(threadId, index) {
    const thread = ulFindThread(threadId);
    const update = thread && thread.updates[index];
    if (!thread || !update) {
        ulToast('记录已不存在，正在刷新', 'warn', { key: 'ul-stale' });
        await ulLoadData({ silent: true });
        return;
    }
    if (ulGuardBusy()) return;

    const targets = UL.threads.filter((item) => item.thread_id !== threadId);
    if (!targets.length) {
        ulToast('没有其他主题可以接收这条记录', 'warn');
        return;
    }
    const values = await ulFormModal({
        title: '移动记录',
        description: '把「' + (update.summary || '（无摘要）') + '」移动到另一个主题。',
        submitText: '移动',
        fields: [{
            name: 'toThread',
            label: '目标主题',
            type: 'select',
            wide: true,
            value: targets[0].thread_id,
            options: targets.map((item) => ({
                value: item.thread_id,
                label: item.title + '（' + item.thread_id + ' · ' + ulUpdateCount(item) + ' 条）'
            }))
        }]
    });
    if (!values) return;

    const fresh = await ulEnsureFresh(threadId, index, ulUpdateSignature(update));
    if (!fresh.ok && fresh.reason === 'fetch-failed') {
        ulHandleActionError({ message: fresh.message });
        return;
    }
    if (!fresh.ok) {
        ulToast('数据已被其他窗口修改，已刷新列表，请重新确认', 'warn', { key: 'ul-stale', duration: 4000 });
        return;
    }

    try {
        await ulPostAction({
            action: 'move',
            fromThread: threadId,
            toThread: values.toThread,
            updateIndex: index
        });
        ulToast('记录已移动', 'success');
        await ulLoadData({ silent: true });
    } catch (err) {
        ulHandleActionError(err);
    }
}

// @anchor: ulUpdateActions_append
// 手动追加弹窗：主题选择 + 摘要 + 详情（左写右预览）+ 文件列表
function ulOpenAppendModal(options) {
    const opts = options || {};
    if (!UL.project) {
        ulToast('缺少 project 参数，无法写入', 'error');
        return;
    }
    if (ulGuardBusy()) return;

    const isNewThread = !!opts.newThreadTitle;
    const presetThreadId = opts.threadId || UL.selectedThreadId || '';
    const container = document.createElement('div');
    container.className = 'ul-form ul-append-form';

    // 主题选择 / 新主题提示
    const threadField = document.createElement('div');
    threadField.className = 'ul-field ul-field-wide';
    const threadLabel = document.createElement('span');
    threadLabel.className = 'ul-field-label';
    threadLabel.textContent = '主题线程';
    threadField.appendChild(threadLabel);

    let threadSelect = null;
    if (isNewThread) {
        const staticValue = document.createElement('div');
        staticValue.className = 'ul-static-value';
        staticValue.innerHTML = '<code>' + ulEscapeHtml(presetThreadId) + '</code>'
            + '<span class="ul-field-hint">新主题「' + ulEscapeHtml(opts.newThreadTitle) + '」，将由这条记录落地创建</span>';
        threadField.appendChild(staticValue);
    } else {
        threadSelect = document.createElement('select');
        threadSelect.name = 'threadId';
        const candidates = ulSortThreads(UL.threads);
        candidates.forEach((thread) => {
            const option = document.createElement('option');
            option.value = thread.thread_id;
            option.textContent = thread.title + '（' + thread.thread_id + '）';
            option.selected = thread.thread_id === presetThreadId;
            threadSelect.appendChild(option);
        });
        if (!candidates.length) {
            ulToast('还没有任何主题，请先新建主题', 'warn');
            return;
        }
        threadField.appendChild(threadSelect);
    }
    container.appendChild(threadField);

    // 关闭态提醒
    const closedHint = document.createElement('div');
    closedHint.className = 'ul-form-note';
    closedHint.hidden = true;
    container.appendChild(closedHint);

    const syncClosedHint = () => {
        const tid = isNewThread ? presetThreadId : (threadSelect ? threadSelect.value : '');
        const target = ulFindThread(tid);
        const closed = target && target.status === 'closed';
        closedHint.hidden = !closed;
        closedHint.textContent = closed ? '该主题当前为「已关闭」，保存这条记录后会自动重新激活。' : '';
    };

    // 摘要
    const summaryField = document.createElement('div');
    summaryField.className = 'ul-field ul-field-wide';
    summaryField.innerHTML = '<span class="ul-field-label">摘要<i class="ul-field-star">*</i></span>';
    const summaryInput = document.createElement('input');
    summaryInput.type = 'text';
    summaryInput.name = 'summary';
    summaryInput.placeholder = '一句话说明这次改动（必填）';
    summaryInput.maxLength = 200;
    summaryField.appendChild(summaryInput);
    const summaryError = document.createElement('span');
    summaryError.className = 'ul-field-error';
    summaryError.hidden = true;
    summaryField.appendChild(summaryError);
    container.appendChild(summaryField);

    // 详情：左写右预览
    const detailField = document.createElement('div');
    detailField.className = 'ul-field ul-field-wide';
    const detailLabel = document.createElement('span');
    detailLabel.className = 'ul-field-label';
    detailLabel.innerHTML = '详情<span class="ul-field-hint-inline">支持 Markdown，右侧实时预览</span>';
    detailField.appendChild(detailLabel);
    const split = document.createElement('div');
    split.className = 'ul-split';
    const detailsInput = document.createElement('textarea');
    detailsInput.name = 'details';
    detailsInput.rows = 10;
    detailsInput.className = 'ul-split-editor';
    detailsInput.placeholder = '可选。支持 **粗体**、`行内代码`、列表、链接…';
    const preview = document.createElement('div');
    preview.className = 'ul-split-preview ul-markdown';
    preview.innerHTML = '<span class="ul-preview-empty">预览区</span>';
    split.appendChild(detailsInput);
    split.appendChild(preview);
    detailField.appendChild(split);
    container.appendChild(detailField);

    const renderPreview = ulDebounce(() => {
        const text = detailsInput.value;
        preview.innerHTML = text.trim()
            ? ulRenderMarkdown(text)
            : '<span class="ul-preview-empty">预览区</span>';
    }, 140);
    detailsInput.addEventListener('input', renderPreview);

    // 文件
    const filesField = document.createElement('div');
    filesField.className = 'ul-field ul-field-wide';
    filesField.innerHTML = '<span class="ul-field-label">涉及文件<span class="ul-field-hint-inline">逗号分隔</span></span>';
    const filesInput = document.createElement('input');
    filesInput.type = 'text';
    filesInput.name = 'files';
    filesInput.placeholder = 'style.css, modules/log.js';
    filesField.appendChild(filesInput);
    container.appendChild(filesField);

    if (threadSelect) threadSelect.addEventListener('change', syncClosedHint);
    syncClosedHint();

    ulOpenModal({
        title: isNewThread ? '新建主题并写下第一条记录' : '手动添加记录',
        subtitle: '写入 .update/ 目录，Agent 下次读取时会看到这条记录',
        body: container,
        wide: true,
        actions: [
            { label: '取消', variant: 'ghost' },
            {
                label: '保存记录',
                variant: 'primary',
                onClick: async (api) => {
                    const threadId = isNewThread ? presetThreadId : (threadSelect ? threadSelect.value : '');
                    const summary = summaryInput.value.trim();
                    summaryError.hidden = true;
                    if (!summary) {
                        summaryError.textContent = '摘要不能为空';
                        summaryError.hidden = false;
                        summaryInput.focus();
                        return false;
                    }
                    if (!threadId) {
                        ulToast('请选择目标主题', 'warn');
                        return false;
                    }
                    const payload = {
                        action: 'append',
                        threadId: threadId,
                        summary: summary,
                        details: detailsInput.value.trim(),
                        files: filesInput.value.trim()
                    };
                    if (isNewThread) payload.title = opts.newThreadTitle;
                    api.setBusy(true);
                    try {
                        await ulPostAction(payload);
                    } catch (err) {
                        ulHandleActionError(err);
                        return false; // 保留用户输入，允许改完后重试
                    } finally {
                        api.setBusy(false);
                    }
                    UL.selectedThreadId = threadId;
                    ulToast(isNewThread ? '主题已创建，记录已写入' : '记录已添加', 'success');
                    await ulLoadData({ silent: true });
                    return true;
                }
            }
        ]
    });
}

// @anchor: ulEditUpdate
// 编辑单条记录：预填旧值，复用 append 模态框的左右分栏
function ulEditUpdate(threadId, updateIndex) {
    const thread = ulFindThread(threadId);
    if (!thread) return;
    const update = thread.updates[updateIndex];
    if (!update) return;

    // 新鲜度校验：Agent 可能在中间删/加了记录，导致 index 偏移
    UpdateArchive.lock(async () => {
        const fresh = await ulEnsureFresh(threadId, updateIndex, update);
        if (!fresh.ok) {
            ulToast('该记录已变化，请刷新后重试', 'warn');
            return;
        }
        openEditModal(thread, update, updateIndex);
    });
}

function openEditModal(thread, update, updateIndex) {
    const body = `
        <div class="ul-form">
            <div class="ul-field">
                <label class="ul-field-label">摘要 <span class="ul-field-star">*</span></label>
                <input type="text" id="ulEditSummary" maxlength="200" value="${ulEscapeHtml(update.summary || '')}">
            </div>
            <div class="ul-field">
                <label class="ul-field-label">详情（Markdown，左写右预览）</label>
                <div class="ul-split">
                    <textarea id="ulEditDetails" class="ul-split-editor" placeholder="支持 Markdown…">${ulText(update.details || '')}</textarea>
                    <div id="ulEditPreview" class="ul-split-preview ul-markdown"></div>
                </div>
            </div>
            <div class="ul-field">
                <label class="ul-field-label">涉及文件（逗号分隔）</label>
                <input type="text" id="ulEditFiles" value="${ulEscapeHtml((update.files || []).join(', '))}">
            </div>
            <div class="ul-form-note">记录时间保持不变：${ulEscapeHtml(update.timestamp)}</div>
        </div>
    `;

    ulOpenModal({
        title: '编辑记录 #' + (updateIndex + 1),
        body: body,
        wide: true,
        actions: [
            { label: '取消', kind: 'ghost' },
            {
                label: '保存修改', kind: 'primary', onClick: async () => {
                    const summary = document.getElementById('ulEditSummary').value.trim();
                    if (!summary) {
                        ulToast('摘要不能为空', 'error');
                        return false;
                    }
                    try {
                        await ulPostAction({
                            action: 'edit',
                            threadId: thread.thread_id,
                            updateIndex: updateIndex,
                            summary: summary,
                            details: document.getElementById('ulEditDetails').value,
                            files: document.getElementById('ulEditFiles').value
                        });
                        ulToast('记录已更新', 'success');
                        await ulLoadData({ keepSelection: true });
                        return true;
                    } catch (err) {
                        ulHandleActionError(err);
                        return false;
                    }
                }
            }
        ],
        onReady: () => {
            const ta = document.getElementById('ulEditDetails');
            const preview = document.getElementById('ulEditPreview');
            const render = () => {
                const src = ta.value;
                preview.innerHTML = src.trim()
                    ? ulRenderMarkdown(src)
                    : '<span class="ul-preview-empty">预览区</span>';
            };
            render();
            ta.addEventListener('input', ulDebounce(render, 150));
        }
    });
}
