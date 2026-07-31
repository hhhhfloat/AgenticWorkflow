// @anchor: modules_status
// ===== 后端状态查询 =====

/**
 * 页面加载时查询后端是否有 Agent 在运行
 * 如果有，恢复前端状态（按钮、运行标志等）
 */
// @anchor: modules_status
// ===== 后端状态查询（含重连检测） =====

async function checkBackendStatus() {
    try {
        const res = await fetch(BASE_URL + '/status');
        const data = await res.json();

        if (data.running) {
            // ⭐ 页面刷新后检测到 Agent 还在运行 => 重连成功
            appendLog('[系统] 🟢 页面刷新成功，已重新连接到正在运行的任务');

            isRunning = true;
            runBtn.disabled = true;
            stopBtn.disabled = false;
            appendLog('[系统] 💡 如需停止任务，请点击"停止"按钮');
        } else {
            isRunning = false;
            runBtn.disabled = false;
            stopBtn.disabled = true;
        }
    } catch (err) {
        console.warn('查询后端状态失败:', err);
    }
}