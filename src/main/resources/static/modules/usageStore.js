// @anchor: modules_usageStore
// 会话用量面板：从后端拉取累计数据并渲染

function renderUsagePanel() {
    const panel = document.getElementById('usagePanel');
    if (!panel) return;

    const sid = getCurrentSessionId();
    if (!sid) {
        panel.textContent = '';
        return;
    }

    fetch(BASE_URL + '/session/usage?sessionId=' + encodeURIComponent(sid))
        .then(r => r.json())
        .then(u => {
        const hitRate = u.promptTokens > 0
            ? (u.cachedTokens / u.promptTokens * 100).toFixed(1)
            : '0.0';
        panel.innerHTML =
        `<span title="输入/输出 Token">📥 ${u.promptTokens} / 📤 ${u.completionTokens}</span>` +
        `<span title="缓存命中率">♾️ ${hitRate}%</span>` +
        `<span title="累计成本">💵 ¥${(u.cost || 0).toFixed(4)}</span>`;
    })
        .catch(() => {
        panel.textContent = '';
    });
}