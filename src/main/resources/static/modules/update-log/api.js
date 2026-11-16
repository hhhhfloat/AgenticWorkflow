// @anchor: ulApi_intro
// 数据层：GET /update-log/data 读取、POST /update-log/action 写入，并统一处理并发与冲突
//
// 并发模型（后端已更新）：
//   1) Agent 在同一项目上运行时后端直接返回 409 → 前端只提示，不重试、不当致命错误；
//   2) 用户双 tab 由后端 UpdateArchive.PROJECT_LOCKS 串行；
//   3) 本 tab 的写操作全部经 UpdateArchive.lock() 排队，避免连点造成的请求交错；
//   4) 写成功后广播变更，其他 tab 静默重载；delete/move 前校验视图新鲜度。

// @anchor: ulApi_lock
// 静态锁：单 tab 内所有写操作串行执行（Promise 队列），并维护 busy 态
const UpdateArchive = {
    _queue: Promise.resolve(),
    _pending: 0,
    _channel: null,
    tabId: 'tab-' + Math.random().toString(36).slice(2, 8),
    busy: false,

    isBusy() {
        return this._pending > 0;
    },

    lock(task) {
        this._pending += 1;
        this._syncBusy();
        const settled = () => {
            this._pending = Math.max(0, this._pending - 1);
            this._syncBusy();
        };
        const run = this._queue.then(() => task());
        this._queue = run.then(settled, settled);
        return run;
    },

    _syncBusy() {
        this.busy = this._pending > 0;
        document.body.classList.toggle('ul-busy', this.busy);
        document.body.setAttribute('aria-busy', this.busy ? 'true' : 'false');
    }
};

// @anchor: ulApi_load
// 读取全量线程数据并归一化（倒序、补默认值），随后交回渲染层
async function ulFetchThreads() {
    const url = '/update-log/data?project=' + encodeURIComponent(UL.project) + '&_t=' + Date.now();
    const response = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' });
    if (!response.ok) {
        throw new Error('读取更新档案失败（HTTP ' + response.status + '）');
    }
    const data = await response.json();
    const threads = Array.isArray(data.threads) ? data.threads : [];
    return threads.map(ulNormalizeThread);
}

function ulNormalizeThread(thread) {
    const updates = (Array.isArray(thread.updates) ? thread.updates : [])
        .map((update) => ({
            timestamp: ulText(update && update.timestamp),
            summary: ulText(update && update.summary),
            details: ulText(update && update.details),
            files: Array.isArray(update && update.files) ? update.files.map(ulText).filter(Boolean) : []
        }))
        .sort((a, b) => (a.timestamp < b.timestamp ? 1 : a.timestamp > b.timestamp ? -1 : 0));
    return {
        thread_id: ulText(thread.thread_id),
        title: ulText(thread.title) || ulText(thread.thread_id),
        status: thread.status === 'closed' ? 'closed' : 'active',
        created_at: ulText(thread.created_at),
        updates: updates
    };
}

// @anchor: ulApi_loadAll
// 首屏 / 手动刷新：维护 loading 与 error，并校正选中线程
async function ulLoadData(options) {
    const opts = options || {};
    if (!UL.project) {
        UL.threads = [];
        UL.error = '缺少 project 参数，无法定位更新档案';
        ulRenderAll();
        return;
    }
    if (opts.silent !== true) {
        UL.loading = true;
        UL.error = null;
        ulRenderAll();
    }
    try {
        const threads = await ulFetchThreads();
        UL.threads = ulSortThreads(threads);
        UL.error = null;
    } catch (err) {
        UL.error = (err && err.message) || '读取更新档案失败';
    } finally {
        UL.loading = false;
        ulReconcileSelection(opts.keepSelection !== false);
        ulRenderAll();
    }
}

// @anchor: ulApi_reconcile
// 选中线程校正：被删除或首次加载时选中第一个非空线程
function ulReconcileSelection(keepSelection) {
    const exists = keepSelection && UL.selectedThreadId
        && UL.threads.some((thread) => thread.thread_id === UL.selectedThreadId);
    if (exists) return;
    const first = UL.threads.find((thread) => ulUpdateCount(thread) > 0) || UL.threads[0];
    UL.selectedThreadId = first ? first.thread_id : null;
}

// @anchor: ulApi_ensureFresh
// 写前新鲜度校验：目标下标的 timestamp+summary 是否仍与界面一致
async function ulEnsureFresh(threadId, updateIndex, expected) {
    let threads;
    try {
        threads = ulSortThreads(await ulFetchThreads());
    } catch (err) {
        return { ok: false, reason: 'fetch-failed', message: (err && err.message) || '无法确认数据版本' };
    }
    const thread = threads.find((item) => item.thread_id === threadId);
    const current = thread && thread.updates[updateIndex];
    const same = current && expected
        && current.timestamp === expected.timestamp
        && current.summary === expected.summary;
    if (!same) {
        UL.threads = threads;
        ulReconcileSelection(true);
        ulRenderAll();
    }
    return { ok: !!same, thread: thread || null };
}

// @anchor: ulApi_post
// 写操作统一入口：加锁 → 发请求 → 解析 409/400 → 成功后广播
function ulPostAction(payload) {
    const body = Object.assign({ project: UL.project }, payload);
    return UpdateArchive.lock(async () => {
        let response;
        try {
            response = await fetch('/update-log/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
        } catch (err) {
            const networkError = new Error('网络异常，无法连接本地服务');
            networkError.code = 0;
            throw networkError;
        }
        let data = null;
        try {
            data = await response.json();
        } catch (err) {
            data = null;
        }
        if (response.status === 409) {
            const conflict = new Error((data && data.message) || 'Agent 正在该项目上运行');
            conflict.code = 409;
            throw conflict;
        }
        if (!response.ok || !data || data.status !== 'success') {
            const failed = new Error((data && data.message) || ('请求失败（HTTP ' + response.status + '）'));
            failed.code = response.status;
            throw failed;
        }
        ulBroadcastChange();
        return data;
    });
}

// @anchor: ulApi_broadcast
// 跨标签页变更通知：BroadcastChannel 为主，localStorage 事件兜底
function ulChangeChannel() {
    if (UpdateArchive._channel) return UpdateArchive._channel;
    if (typeof BroadcastChannel !== 'function') return null;
    try {
        UpdateArchive._channel = new BroadcastChannel('update-log:' + UL.project);
    } catch (err) {
        UpdateArchive._channel = null;
    }
    return UpdateArchive._channel;
}

function ulBroadcastChange() {
    const at = Date.now();
    try {
        localStorage.setItem('ul-change:' + UL.project, String(at));
    } catch (err) {
        // 忽略不可写场景
    }
    const channel = ulChangeChannel();
    if (channel) {
        try {
            channel.postMessage({ type: 'change', at: at, tab: UpdateArchive.tabId });
        } catch (err) {
            // 忽略
        }
    }
}

function ulWatchRemoteChange(onChange) {
    const handler = (at, from) => {
        if (!at || from === UpdateArchive.tabId) return;
        onChange();
    };
    const channel = ulChangeChannel();
    if (channel) {
        channel.onmessage = (event) => {
            const payload = event.data || {};
            if (payload.type === 'change') handler(payload.at, payload.tab);
        };
    }
    window.addEventListener('storage', (event) => {
        if (event.key === 'ul-change:' + UL.project && event.newValue) {
            handler(Number(event.newValue), null);
        }
    });
}

// @anchor: ulApi_errorHandler
// 统一错误出口：409 走「等待 Agent」提示，其余展示后端原文
function ulHandleActionError(err) {
    const code = err && err.code;
    const message = ((err && err.message) || '操作失败').replace(/^❌\s*/, '');
    if (code === 409) {
        ulToast('Agent 正在工作，请稍候', 'warn', { key: 'ul-conflict', duration: 4000 });
        return;
    }
    ulToast(message, 'error', { key: 'ul-action-error' });
}
