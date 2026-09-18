// @anchor: modules_usageStore
const USAGE_KEY_PREFIX = 'usage_';

function getSessionUsage(sessionId) {
    if (!sessionId) return { promptTokens: 0, cachedTokens: 0, completionTokens: 0, apiCalls: 0, cost: 0 };
    try {
        const raw = localStorage.getItem(USAGE_KEY_PREFIX + sessionId);
        return raw ? JSON.parse(raw) : { promptTokens: 0, cachedTokens: 0, completionTokens: 0, apiCalls: 0, cost: 0 };
    } catch (e) {
        return { promptTokens: 0, cachedTokens: 0, completionTokens: 0, apiCalls: 0, cost: 0 };
    }
}

function addSessionUsage(sessionId, delta) {
    if (!sessionId) return;
    const cur = getSessionUsage(sessionId);
    const next = {
        promptTokens: cur.promptTokens + (delta.promptTokens || 0),
        cachedTokens: cur.cachedTokens + (delta.cachedTokens || 0),
        completionTokens: cur.completionTokens + (delta.completionTokens || 0),
        apiCalls: cur.apiCalls + (delta.apiCalls || 0),
        cost: cur.cost + (delta.cost || 0)
    };
    localStorage.setItem(USAGE_KEY_PREFIX + sessionId, JSON.stringify(next));
    return next;
}

function clearSessionUsage(sessionId) {
    if (!sessionId) return;
    localStorage.removeItem(USAGE_KEY_PREFIX + sessionId);
}

/**
 * 渲染当前会话的消耗到 #usagePanel。
 */
function renderUsagePanel() {
    const panel = document.getElementById('usagePanel');
    if (!panel) return;

    const sid = getCurrentSessionId();
    if (!sid) {
        panel.textContent = '';
        return;
    }

    const u = getSessionUsage(sid);
    const hitRate = u.promptTokens > 0 ? (u.cachedTokens / u.promptTokens * 100).toFixed(1) : '0.0';
    panel.innerHTML =
    `<span title="输入/输出 Token">📥 ${u.promptTokens} / 📤 ${u.completionTokens}</span>` +
    `<span title="缓存命中率">♾️ ${hitRate}%</span>` +
    `<span title="累计成本">💵 ¥${u.cost.toFixed(4)}</span>`;
}