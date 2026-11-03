// @anchor: openTerminal_intro
// 打开终端：请求后端在指定目录唤起终端窗口

// @anchor: openTerminal_openTerminal
// 请求 /openTerminal?path=... 唤起终端并定位到目标目录
async function openTerminal(path) {
    try {
        appendLog('[系统] 💻 正在打开终端: ' + path);
        const res = await fetch('/openTerminal?path=' + encodeURIComponent(path));
        const data = await res.json();
        if (data.status === 'success') {
            appendLog('[系统] ✅ ' + data.message);
        } else {
            appendLog('[系统] ❌ ' + (data.message || '打开失败'));
        }
    } catch (e) {
        appendLog('[系统] ❌ 请求失败: ' + e.message);
    }
}