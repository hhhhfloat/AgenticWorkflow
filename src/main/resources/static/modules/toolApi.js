// @anchor: toolApi_intro
// 前端调用后端白名单工具：POST /tool，参数与结果均为 JSON

// @anchor: toolApi_callTool
// 调用后端工具，返回结果字符串；失败返回 null 并写日志
async function callTool(tool, args) {
    try {
        const res = await fetch('/tool', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({tool, args: args || {}})
        });
        const data = await res.json();
        if (data.status === 'success') {
            appendLog(`[工具] ✅ ${tool} 完成`);
            return data.result;
        }
        appendLog(`[工具] ❌ ${tool} 失败: ${data.message}`);
        return null;
    } catch (err) {
        appendLog(`[工具] ❌ ${tool} 请求失败: ${err.message}`);
        return null;
    }
}