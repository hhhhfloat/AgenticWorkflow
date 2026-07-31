// @anchor: modules_log_utils
// ===== 日志输出工具函数 =====
function isMarkdown(text) {
    return /(\|\s*[-:]+[\s|]+\||^#{1,6}\s|\n```|\n\s*[-*]\s|\n>\s|^---\s*$|\n---\s*$)/m.test(text);
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// @anchor: modules_log_append
// ===== 日志输出模块（整合迭代提示） =====
function appendLog(msg) {
    let color = 'log-info';
    if (msg.startsWith('[错误]')) color = 'log-error';
    else if (msg.startsWith('[完成]') || msg.includes('✅')) color = 'log-success';
    else if (msg.startsWith('[系统]')) color = 'log-system';

    let renderedContent;

    if (msg.startsWith('[系统]')) {
        renderedContent = escapeHtml(msg);
    } else if (msg.startsWith('📁 ') && (msg.includes('项') || msg.includes('内容'))) {
        renderedContent = `<pre style="margin:0; font-family:inherit; white-space:pre-wrap;">${escapeHtml(msg)}</pre>`;
    } else {
        try {
            let cleanMsg = msg;
            if (msg.startsWith('[完成] ')) {
                cleanMsg = msg.substring(4);
            }
            renderedContent = marked.parse(cleanMsg, { gfm: true, breaks: true });
        } catch (e) {
            renderedContent = escapeHtml(msg);
        }
    }

    // ⭐ 核心修复 1：使用 insertAdjacentHTML 替代 innerHTML +=
    const line = `<div class="log-entry ${color}">${renderedContent}</div>`;
    output.insertAdjacentHTML('beforeend', line);

    // ⭐ 核心修复 2：自动截断（根据你的块状场景，保留 1000 条足够）
    const MAX_LOGS = 1000;
    while (output.children.length > MAX_LOGS) {
        output.removeChild(output.firstChild);
    }

    output.scrollTop = output.scrollHeight;

    // 迭代提示（不变）
    if (msg.includes('--- 第') && msg.includes('次迭代 ---')) {
        const tip = getRandomQuote();
        if (tip) {
            const tipLine = `<div class="log-entry log-tip">> ${escapeHtml(tip)}</div>`;
            output.insertAdjacentHTML('beforeend', tipLine);
            while (output.children.length > MAX_LOGS) {
                output.removeChild(output.firstChild);
            }
            output.scrollTop = output.scrollHeight;
        }
    }
}
