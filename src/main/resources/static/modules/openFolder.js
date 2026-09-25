// @anchor: modules_openFolder
// 请求后端在系统文件管理器中打开指定目录

// ===== 打开文件夹 =====
async function openFolder(path) {
    try {
        const res = await fetch(`/openFolder?path=${encodeURIComponent(path)}`);
        const data = await res.json();
        if (data.status === 'success') {
            appendLog(`[系统] 📂 已打开 ${path} 文件夹`);
        } else {
            appendLog(`[系统] ❌ 打开失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 打开失败: ${err.message}`);
    }
}
