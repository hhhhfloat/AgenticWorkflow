// @anchor: ulThreadActions_intro
// 线程级操作：状态切换、新建主题（拾取 threadId + title 后转入追加弹窗）、合并到其他主题

// @anchor: ulThreadActions_status
// 状态切换：active ⇄ closed，成功后刷新并保持选中
async function ulToggleThreadStatus(threadId) {
    const thread = ulFindThread(threadId);
    if (!thread) return;
    if (ulGuardBusy()) return;
    const nextStatus = thread.status === 'closed' ? 'active' : 'closed';
    try {
        await ulPostAction({ action: 'status', threadId: thread.thread_id, status: nextStatus });
        ulToast(nextStatus === 'closed' ? '已标记为「已关闭」' : '已重新激活', 'success');
        await ulLoadData({ silent: true });
    } catch (err) {
        ulHandleActionError(err);
    }
}

// @anchor: ulThreadActions_create
// 新建主题：后端没有空线程接口，先收集 threadId + title，再由首条记录落地
async function ulCreateThreadFlow() {
    if (ulGuardBusy()) return;
    const values = await ulFormModal({
        title: '新建主题',
        subtitle: '填写后为它写下第一条记录，线程随之创建',
        description: '后端只提供「追加记录」接口：当 thread 不存在且带 title 时自动建线程。',
        submitText: '下一步：写第一条记录',
        fields: [
            {
                name: 'threadId',
                label: 'Thread ID',
                required: true,
                placeholder: '如 ui_optimization',
                hint: '仅允许字母、数字与下划线，创建后不可修改',
                validate: (value) => (/^[A-Za-z0-9_]+$/.test(value) ? '' : '只能包含字母、数字和下划线')
            },
            {
                name: 'title',
                label: '主题标题',
                required: true,
                placeholder: '如 UI 与交互体验优化'
            }
        ],
        validate: (all) => (ulFindThread(all.threadId)
            ? '该 threadId 已存在，请换一个，或直接在已有主题里添加记录'
            : '')
    });
    if (!values) return;
    ulOpenAppendModal({ threadId: values.threadId, newThreadTitle: values.title });
}

// @anchor: ulThreadActions_merge
// 合并到其他主题：可选新标题，二次确认后执行（源线程文件会被删除）
async function ulMergeThread(threadId) {
    const thread = ulFindThread(threadId);
    if (!thread) return;
    if (ulGuardBusy()) return;
    const targets = UL.threads.filter((item) => item.thread_id !== threadId);
    if (!targets.length) {
        ulToast('没有其他主题可以合并', 'warn');
        return;
    }
    const values = await ulFormModal({
        title: '合并到其他主题',
        description: '把「' + thread.title + '」的 ' + ulUpdateCount(thread) + ' 条记录并入目标主题，源线程将被删除。',
        submitText: '下一步',
        fields: [
            {
                name: 'toThread',
                label: '目标主题',
                type: 'select',
                wide: true,
                value: targets[0].thread_id,
                options: targets.map((item) => ({
                    value: item.thread_id,
                    label: item.title + '（' + item.thread_id + ' · ' + ulUpdateCount(item) + ' 条）'
                }))
            },
            {
                name: 'newTitle',
                label: '合并后的新标题',
                wide: true,
                placeholder: '留空则保留目标主题原标题',
                hint: '可选，用于合并后统一命名'
            }
        ]
    });
    if (!values) return;

    const target = ulFindThread(values.toThread);
    const confirmed = await ulConfirmModal({
        title: '确认合并',
        message: '「' + thread.title + '」的记录将并入「' + (target ? target.title : values.toThread) + '」，'
            + '随后源线程会被删除，不可撤销。',
        confirmText: '合并',
        cancelText: '取消'
    });
    if (!confirmed) return;

    try {
        await ulPostAction({
            action: 'merge',
            fromThread: threadId,
            toThread: values.toThread,
            newTitle: values.newTitle || ''
        });
        ulToast('线程已合并', 'success');
        UL.selectedThreadId = values.toThread;
        await ulLoadData({ silent: true });
    } catch (err) {
        ulHandleActionError(err);
    }
}
