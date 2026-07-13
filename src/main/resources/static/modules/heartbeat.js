// @anchor: modules_heartbeat
// ===== 心跳机制 =====
function startHeartbeat() {
    if (heartbeatInterval) return;
    heartbeatInterval = setInterval(() => {
        fetch(BASE_URL + '/heartbeat', { method: 'POST' }).catch(() => { /* 静默失败 */ });
    }, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
}
