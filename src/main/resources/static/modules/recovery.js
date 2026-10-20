// @anchor: recovery_intro
// 任务异常恢复：顶栏红色感叹号按钮 + 弹层显示错误信息与继续/忽略
// 错误信息通过 localStorage 持久化，刷新页面后仍可见

// @anchor: recovery_storage
// pendingError 存于 localStorage，跨刷新保留
const RECOVERY_STORAGE_KEY = 'recovery_pending';

function savePendingError(err) {
    try {
        localStorage.setItem(RECOVERY_STORAGE_KEY, JSON.stringify(err));
    } catch (e) {
        console.warn('[recovery] 存储失败', e);
    }
}

function loadPendingError() {
    try {
        const raw = localStorage.getItem(RECOVERY_STORAGE_KEY);
        if (!raw) return null;
        const obj = JSON.parse(raw);
        if (obj && obj.sessionId) return obj;
    } catch (e) {}
    return null;
}

function clearPendingError() {
    try {
        localStorage.removeItem(RECOVERY_STORAGE_KEY);
    } catch (e) {}
}

// @anchor: recovery_refreshWidget
// 根据当前会话与 localStorage 的一致性，决定是否显示红色感叹号
function refreshRecoveryWidget() {
    const widget = document.getElementById('recoveryWidget');
    if (!widget) return;
    const err = loadPendingError();
    if (!err || err.sessionId !== getCurrentSessionId()) {
        widget.style.display = 'none';
        return;
    }
    widget.style.display = '';
}

// @anchor: recovery_hideWidget
// 隐藏按钮、关闭弹层、清掉持久化状态
function hideRecoveryWidget() {
    const widget = document.getElementById('recoveryWidget');
    if (widget) widget.style.display = 'none';
    const popover = document.getElementById('recoveryPopover');
    if (popover) popover.style.display = 'none';
    const resumeBtn = document.getElementById('recoveryResumeBtn');
    if (resumeBtn) {
        resumeBtn.disabled = false;
        resumeBtn.textContent = '继续上次任务';
    }
    clearPendingError();
}

// @anchor: recovery_bindControls
// 绑定按钮与弹层的交互
function bindRecoveryControls() {
    const widget = document.getElementById('recoveryWidget');
    const popover = document.getElementById('recoveryPopover');
    const closeBtn = document.getElementById('recoveryPopoverClose');
    const resumeBtn = document.getElementById('recoveryResumeBtn');
    const dismissBtn = document.getElementById('recoveryDismissBtn');

    if (widget && popover) {
        widget.addEventListener('click', (e) => {
            e.stopPropagation();
            // 互斥：关闭上下文弹层
            const ctxPopover = document.getElementById('contextPopover');
            if (ctxPopover) ctxPopover.style.display = 'none';

            const err = loadPendingError();
            if (!err) return;

            const visible = popover.style.display === 'block';
            if (visible) {
                popover.style.display = 'none';
            } else {
                renderRecoveryPopover(err);
                popover.style.display = 'block';
            }
        });
    }

    if (closeBtn && popover) {
        closeBtn.addEventListener('click', () => { popover.style.display = 'none'; });
    }

    if (dismissBtn) {
        dismissBtn.addEventListener('click', () => hideRecoveryWidget());
    }

    if (resumeBtn) {
        resumeBtn.addEventListener('click', () => {
            const err = loadPendingError();
            if (!err) return;
            resumeBtn.disabled = true;
            resumeBtn.textContent = '⏳ 恢复中…';
            doResume(err.sessionId);
        });
    }

    // 点外部关闭弹层
    document.addEventListener('click', (e) => {
        if (!popover) return;
        if (popover.style.display !== 'block') return;
        if (popover.contains(e.target)) return;
        if (widget && widget.contains(e.target)) return;
        popover.style.display = 'none';
    });
}

// @anchor: recovery_doResume
// 实际执行恢复：先查 hasRecovery，再调 /session/resume
async function doResume(sessionId) {
    let hasRecovery = false;
    try {
        const res = await fetch(
            BASE_URL + '/session/hasRecovery?sessionId=' + encodeURIComponent(sessionId));
        if (res.ok) {
            const data = await res.json();
            hasRecovery = !!data.hasRecovery;
        }
    } catch (e) {
        console.warn('[recovery] hasRecovery 检查失败', e);
    }

    if (!hasRecovery) {
        hideRecoveryWidget();
        appendLog('[系统] ⚠️ 没有可恢复的进度（已被清理或压缩）');
        return;
    }

    hideRecoveryWidget();
    await runResumeStream(sessionId);
}

// @anchor: recovery_runResumeStream
// 调用 /session/resume 并消费 SSE 流
async function runResumeStream(sessionId) {
    appendLog('────────── 恢复中 ──────────\n');

    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;

    const myAbort = new AbortController();
    desktopRunAbort = myAbort;

    const maxIterations = parseInt(document.getElementById('maxIterations').value) || 100;

    let response;
    try {
        response = await fetch(BASE_URL + '/session/resume', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Client-Device': 'desktop'
            },
            body: JSON.stringify({
                sessionId: sessionId,
                maxIterations: maxIterations,
                config: buildRunConfig(loadSettings())
            }),
            signal: myAbort.signal
        });
    } catch (err) {
        if (err.name !== 'AbortError' && !window._isPageUnloading) {
            appendLog('[连接错误] ' + err.message);
        }
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
        return;
    }

    if (!response.ok) {
        let msg = 'HTTP ' + response.status;
        try {
            const text = await response.text();
            const obj = JSON.parse(text);
            if (obj.message) msg = obj.message;
        } catch (e) {}
        appendLog('[错误] 恢复失败：' + msg);
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
        return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();
            for (const event of events) {
                if (desktopRunAbort !== myAbort) return;
                const data = parseSseFrame(event);
                if (data === null) continue;
                if (handleSpecialEvent(data)) continue;
                appendLog(data);
            }
        }
    } catch (err) {
        if (err.name !== 'AbortError' && !window._isPageUnloading) {
            appendLog('[错误] ' + err.message);
        }
    } finally {
        if (desktopRunAbort === myAbort) desktopRunAbort = null;
    }
}
// @anchor: recovery_renderPopover
// 把错误信息渲染到弹窗：message 一行 + 可选的可折叠完整堆栈
function renderRecoveryPopover(err) {
    const msgEl = document.getElementById('recoveryErrorMsg');
    if (!msgEl) return;
    msgEl.textContent = err.message || '未知错误';

    // 清理上一次渲染的堆栈（弹窗可能反复打开）
    const oldStack = document.getElementById('recoveryErrorStack');
    if (oldStack) oldStack.remove();

    if (!err.stack) return;

    // 可折叠的完整堆栈：默认收起，避免长堆栈挤满弹窗
    const details = document.createElement('details');
    details.id = 'recoveryErrorStack';
    details.style.marginTop = '8px';
    details.style.fontSize = '0.85em';

    const summary = document.createElement('summary');
    summary.textContent = '▸ 显示完整堆栈';
    summary.style.cursor = 'pointer';
    details.appendChild(summary);

    const pre = document.createElement('pre');
    pre.textContent = err.stack;
    pre.style.maxHeight = '320px';
    pre.style.overflow = 'auto';
    pre.style.fontSize = '0.75em';
    pre.style.background = '#f5f5f5';
    pre.style.padding = '8px';
    pre.style.borderRadius = '4px';
    pre.style.whiteSpace = 'pre-wrap';
    pre.style.wordBreak = 'break-all';
    pre.style.margin = '6px 0 0 0';
    details.appendChild(pre);

    // 插到 message 元素之后
    msgEl.parentNode.insertBefore(details, msgEl.nextSibling);
}