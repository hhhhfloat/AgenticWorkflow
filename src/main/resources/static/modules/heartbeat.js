// @anchor: modules_heartbeat
// ===== 心跳机制（含断联/重连检测） =====

// 新增：心跳失败计数器
let heartbeatFailCount = 0;
let isDisconnectedLogged = false;

function sendHeartbeat() {
    // 如果页面正在卸载（刷新/关闭），不触发断联日志
    if (window._isPageUnloading) {
        return Promise.resolve();
    }

    return fetch(BASE_URL + '/heartbeat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
    })
        .then(res => res.json())
        .then(data => {
        // ===== 重连检测 =====
        if (heartbeatFailCount > 0) {
            // 之前失败了，现在成功 => 重连
            appendLog('[系统] 🟢 与服务器重新建立连接（心跳恢复）');
            heartbeatFailCount = 0;
            isDisconnectedLogged = false;
        }

        if (data.refresh) {
            console.log('🔄 心跳响应要求刷新目录树');
            refreshSandbox();
            refreshSidebar();
        }
        return data;
    })
        .catch(() => {
        // ===== 断联检测 =====
        heartbeatFailCount++;
        // 连续失败 3 次（约 9 秒）判定为断联
        if (heartbeatFailCount >= 3 && !isDisconnectedLogged) {
            isDisconnectedLogged = true;
            appendLog('[系统] 🔴 与服务器断联（心跳连续失败），后端任务将在 120 秒后超时清理');
        }
    });
}

function startHeartbeat() {
    if (heartbeatInterval) return;
    // 启动时立即发送一次心跳（重置计数）
    heartbeatFailCount = 0;
    isDisconnectedLogged = false;
    sendHeartbeat();
    heartbeatInterval = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
        // 停止心跳时重置计数器，避免残留
        heartbeatFailCount = 0;
        isDisconnectedLogged = false;
    }
}