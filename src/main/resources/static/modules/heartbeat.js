// @anchor: modules_heartbeat
// ===== 心跳机制 =====
function sendHeartbeat() {
    return fetch(BASE_URL + '/heartbeat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
    })
        .then(res => res.json())
        .then(data => {
        if (data.refresh) {
            console.log('🔄 心跳响应要求刷新目录树');
            refreshSandbox();
            refreshSidebar();
        }
        return data;
    })
        .catch(() => { /* 静默失败 */ });
}

function startHeartbeat() {
    if (heartbeatInterval) return;
    heartbeatInterval = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
}