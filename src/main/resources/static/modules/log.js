// @anchor: log_intro
// 日志辅助：判断文本是否 Markdown、HTML 转义

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// @anchor: log_sanitizeHtml
// 简易 HTML 消毒：剥离 script/iframe 等标签与 on* 事件属性、javascript:/data: 协议
function sanitizeHtml(html) {
    const tpl = document.createElement('template');
    tpl.innerHTML = html;
    tpl.content.querySelectorAll('script,iframe,object,embed,form,style,link,meta')
        .forEach(el => el.remove());
    tpl.content.querySelectorAll('*').forEach(el => {
        for (const attr of [...el.attributes]) {
            const name = attr.name.toLowerCase();
            if (name.startsWith('on')) {
                el.removeAttribute(attr.name);
                continue;
            }
            if ((name === 'href' || name === 'src' || name === 'xlink:href') &&
            /^\s*(javascript|data):/i.test(attr.value)) {
                el.removeAttribute(attr.name);
            }
        }
    });
    return tpl.innerHTML;
}

function isScrolledToBottom(el) {
    return el.scrollHeight - el.scrollTop - el.clientHeight < 30;
}

function updateScrollBottomBtn() {
    const btn = document.getElementById('scrollBottomBtn');
    if (!btn) return;
    btn.hidden = isScrolledToBottom(output);
}

// @anchor: log_append
// 将消息按类型着色渲染到 #output，支持对话气泡与迭代提示

// ===== 日志输出模块（整合迭代提示） =====
function appendLog(msg) {
    const stick = isScrolledToBottom(output);
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
            cleanMsg = linkifySandboxPaths(cleanMsg);
            renderedContent = sanitizeHtml(marked.parse(cleanMsg, { gfm: true, breaks: true }));
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

    if (stick) output.scrollTop = output.scrollHeight;
    updateScrollBottomBtn();

    // 迭代提示（不变）
    if (msg.includes('--- 第') && msg.includes('次迭代 ---')) {
        const tip = getRandomQuote();
        if (tip) {
            const tipLine = `<div class="log-entry log-tip">> ${escapeHtml(tip)}</div>`;
            output.insertAdjacentHTML('beforeend', tipLine);
            while (output.children.length > MAX_LOGS) {
                output.removeChild(output.firstChild);
            }
            if (stick) output.scrollTop = output.scrollHeight;
            updateScrollBottomBtn();
        }
    }
}
/**
 * 以"对话消息"形式追加到 #output。
 * @param {string} role - 'user' | 'assistant' | 'system'
 * @param {string} content - 消息内容（支持 Markdown）
 */
function appendMessage(role, content) {
    if (!output || !content) return;
    const stick = isScrolledToBottom(output);

    const wrap = document.createElement('div');
    wrap.className = `msg msg-${role}`;

    const body = document.createElement('div');
    body.className = 'msg-body';

    if (role === 'user') {
        body.textContent = content;  // 用户消息通常不需要 Markdown
    } else {
        body.innerHTML = sanitizeHtml(marked.parse(content));
    }

    wrap.appendChild(body);
    output.appendChild(wrap);
    if (stick) output.scrollTop = output.scrollHeight;
    updateScrollBottomBtn();
}

// @anchor: log_linkifySandbox
// 将 /sandbox/xxx.html 形式的相对路径转为 Markdown 链接，供浏览器点击预览
function linkifySandboxPaths(text) {
    return text.replace(
        /(?<![("'\[])(\/sandbox\/[\w\-./]+\.html?)/g,
        (m) => `[${m}](${m})`
    );
}


