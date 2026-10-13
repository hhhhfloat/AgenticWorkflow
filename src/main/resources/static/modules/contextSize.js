// @anchor: contextSize_intro
// 上下文占用指示器：拉取 /session/context、渲染圆饼与弹层、触发压缩

// ===== 圆饼周长：2 * π * r = 2 * 3.1416 * 8 ≈ 50.3 =====
const CTX_PIE_CIRCUMFERENCE = 50.3;

// @anchor: contextSize_refresh
// 刷新上下文指示器。会话切换、任务结束、压缩成功后调用。
async function refreshContextIndicator() {
    const sessionId = getCurrentSessionId();
    const pieArc = document.getElementById('contextPieArc');
    const percentLabel = document.getElementById('contextPercentLabel');

    if (!pieArc || !percentLabel) {
        console.warn('[contextSize] 指示器元素缺失');
        return;
    }

    if (!sessionId) {
        pieArc.setAttribute('stroke-dasharray', `0 ${CTX_PIE_CIRCUMFERENCE}`);
        pieArc.setAttribute('stroke', 'rgba(255,255,255,0.15)');
        percentLabel.textContent = '--';
        percentLabel.style.color = '';
        updatePopoverDetail(null);
        return;
    }

    try {
        const res = await fetch(
            BASE_URL + '/session/context?sessionId=' + encodeURIComponent(sessionId));
        if (!res.ok) {
            console.warn('[contextSize] /session/context 返回', res.status);
            updatePopoverDetail({ error: 'HTTP ' + res.status });
            return;
        }
        const data = await res.json();
        renderContextIndicator(data);
    } catch (e) {
        console.error('[contextSize] 拉取失败', e);
        updatePopoverDetail({ error: e.message });
    }
}

// @anchor: contextSize_render
// 根据后端返回渲染圆饼、百分比与弹层。
function renderContextIndicator(data) {
    const pieArc = document.getElementById('contextPieArc');
    const percentLabel = document.getElementById('contextPercentLabel');
    if (!pieArc || !percentLabel) return;

    const window = data.modelWindow || 800000;
    let ratio = 0;
    let numbersText = '--';

    if (data.running && data.tokens) {
        ratio = Math.min(1, data.tokens / window);
        numbersText = formatTokens(data.tokens) + ' / ' + formatTokens(window);
    } else if (data.bytes != null) {
        const estTokens = Math.round(data.bytes / 2.5);
        ratio = Math.min(1, estTokens / window);
        numbersText = formatBytes(data.bytes) + '（约 ' + formatTokens(estTokens) + ' tok）';
    } else {
        numbersText = '暂无上下文';
    }

    const dash = (ratio * CTX_PIE_CIRCUMFERENCE).toFixed(1);
    pieArc.setAttribute('stroke-dasharray', `${dash} ${CTX_PIE_CIRCUMFERENCE}`);
    const color = ratioColor(ratio);
    pieArc.setAttribute('stroke', color);

    const pct = (ratio * 100).toFixed(0) + '%';
    percentLabel.textContent = pct;
    percentLabel.style.color = color;

    updatePopoverDetail(data, ratio, numbersText, pct, color);
}

// @anchor: contextSize_updatePopover
// 更新弹层内容。data 为 null 表示无会话，error 表示请求失败。
function updatePopoverDetail(data, ratio, numbersText, pct, color) {
    const detail = document.getElementById('contextDetail');
    const bar = document.getElementById('contextBar');
    const numbers = document.getElementById('contextNumbers');
    const compactBtn = document.getElementById('compactBtn');

    // 无会话
    if (data === null) {
        if (detail) detail.textContent = '未选择会话';
        if (bar) bar.style.width = '0%';
        if (numbers) numbers.textContent = '--';
        if (compactBtn) compactBtn.disabled = true;
        return;
    }

    // 请求失败
    if (data && data.error) {
        if (detail) detail.textContent = '加载失败：' + data.error;
        if (bar) bar.style.width = '0%';
        if (numbers) numbers.textContent = '--';
        if (compactBtn) compactBtn.disabled = false;
        return;
    }

    // 正常数据
    if (detail) {
        detail.textContent = data.running ? '运行中' : '空闲';
    }
    if (bar) {
        bar.style.width = (ratio * 100).toFixed(1) + '%';
        bar.style.background = color;
    }
    if (numbers) {
        numbers.textContent = numbersText + ' · ' + pct;
    }
    if (compactBtn) compactBtn.disabled = false;
}

/**
 * 占比 → 颜色：绿 / 黄 / 红。
 */
function ratioColor(ratio) {
    if (ratio < 0.5) return '#4caf50';
    if (ratio < 0.8) return '#ff9800';
    return '#f44336';
}

function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' K';
    return (bytes / 1024 / 1024).toFixed(2) + ' M';
}

function formatTokens(tokens) {
    if (tokens < 1000) return tokens + '';
    if (tokens < 1000000) return (tokens / 1000).toFixed(1) + 'K';
    return (tokens / 1000000).toFixed(2) + 'M';
}

/**
 * 绑定圆饼点击、弹层关闭、压缩按钮。
 */
function bindContextControls() {
    const widget = document.getElementById('contextWidget');
    const popover = document.getElementById('contextPopover');
    const closeBtn = document.getElementById('contextPopoverClose');
    const compactBtn = document.getElementById('compactBtn');

    if (widget && popover) {
        widget.addEventListener('click', (e) => {
            e.stopPropagation();
            const visible = popover.style.display === 'block';
            popover.style.display = visible ? 'none' : 'block';
            if (!visible) refreshContextIndicator();
        });
    }
    if (closeBtn && popover) {
        closeBtn.addEventListener('click', () => { popover.style.display = 'none'; });
    }
    // 点外部关闭
    document.addEventListener('click', (e) => {
        if (!popover) return;
        if (popover.style.display !== 'block') return;
        if (popover.contains(e.target)) return;
        if (widget && widget.contains(e.target)) return;
        popover.style.display = 'none';
    });

    if (compactBtn) {
        compactBtn.addEventListener('click', compactCurrentSession);
    }
}

/**
 * 触发压缩。
 */
async function compactCurrentSession() {
    const sessionId = getCurrentSessionId();
    if (!sessionId) { alert('请先选择一个会话'); return; }
    if (!confirm('压缩会把历史合并为摘要，原历史会归档到 history-base/。继续？')) return;

    const btn = document.getElementById('compactBtn');
    if (btn) { btn.disabled = true; btn.textContent = '⏳ 压缩中…'; }

    try {
        const res = await fetch(BASE_URL + '/session/compact', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Client-Device': 'desktop'
            },
            body: JSON.stringify({ sessionId: sessionId, keepRecentPairs: 2 })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            alert('压缩失败：' + (data.message || res.status));
            return;
        }
        let msg = `已压缩：${data.beforeCount} 条 → ${data.afterCount} 条\n归档：${data.archiveFile || '—'}`;
        if (data.truncated) {
            msg += '\n\n⚠️ 摘要达到输出上限被截断，建议检查压缩结果。';
        }
        alert(msg);
        await refreshContextIndicator();
        // 重新加载历史，反映压缩后的基础区
        if (typeof loadSessionHistory === 'function') {
            await loadSessionHistory(sessionId);
        }
    } catch (e) {
        alert('压缩失败：' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = '🗜️ 压缩上下文'; }
    }
}