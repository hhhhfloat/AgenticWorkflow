// @anchor: codePreview_intro
// 代码预览逻辑：拉取文件内容、调用 highlight.js 高亮、加载锚点侧栏、点击跳转

const params = new URLSearchParams(location.search);
const filePath = params.get('path');

const pathLabel = document.getElementById('pathLabel');
const codeBlock = document.getElementById('codeBlock');
const anchorList = document.getElementById('anchorList');

let currentRawCode = '';

if (!filePath) {
    codeBlock.innerHTML = '<div class="empty error">缺少 path 参数</div>';
} else {
    pathLabel.textContent = filePath;
    loadFile(filePath);
}

document.getElementById('reloadBtn').addEventListener('click', () => {
    if (filePath) loadFile(filePath);
});

document.getElementById('copyBtn').addEventListener('click', async () => {
    if (!currentRawCode) return;
    try {
        await navigator.clipboard.writeText(currentRawCode);
        pathLabel.textContent = '✅ 已复制 ' + filePath;
        setTimeout(() => { pathLabel.textContent = filePath; }, 1200);
    } catch (e) {
        pathLabel.textContent = '⚠️ 复制失败';
        setTimeout(() => { pathLabel.textContent = filePath; }, 1200);
    }
});

// @anchor: codePreview_loadFile
// 主入口：拉取文件内容并渲染
async function loadFile(path) {
    codeBlock.innerHTML = '<div class="empty">加载中...</div>';
    anchorList.innerHTML = '';
    try {
        const res = await fetch('/' + path + '?t=' + Date.now());
        if (!res.ok) {
            codeBlock.innerHTML = '<div class="empty error">文件不存在（' + path + '）</div>';
            return;
        }
        const code = await res.text();
        currentRawCode = code;
        if (!code.trim()) {
            codeBlock.innerHTML = '<div class="empty">文件为空</div>';
            return;
        }
        renderCode(code, detectLanguage(path));
        loadAnchors(path);   // 异步，不阻塞代码渲染
    } catch (err) {
        codeBlock.innerHTML = '<div class="empty error">加载失败：' + err.message + '</div>';
    }
}

// @anchor: codePreview_detectLanguage
// 按扩展名判定 highlight.js 语言
function detectLanguage(path) {
    const ext = (path.split('.').pop() || '').toLowerCase();
    const map = {
        java: 'java',
        js: 'javascript', mjs: 'javascript', cjs: 'javascript',
        ts: 'typescript', tsx: 'typescript',
        py: 'python', rb: 'ruby', go: 'go', rs: 'rust',
        cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', h: 'cpp',
        c: 'c', cs: 'csharp',
        html: 'xml', htm: 'xml', xml: 'xml', svg: 'xml',
        css: 'css', scss: 'scss', less: 'less',
        json: 'json', yml: 'yaml', yaml: 'yaml',
        md: 'markdown',
        sh: 'bash', bash: 'bash', bat: 'batch', cmd: 'batch',
        properties: 'properties', ini: 'ini', toml: 'ini',
        sql: 'sql', kt: 'kotlin', swift: 'swift', php: 'php',
        log: 'plaintext', txt: 'plaintext'
    };
    return map[ext] || 'plaintext';
}

// @anchor: codePreview_renderCode
// 高亮 + 按行拆分 + 行号
function renderCode(code, language) {
    let highlighted;
    try {
        if (language === 'plaintext') {
            highlighted = escapeHtml(code);
        } else {
            highlighted = hljs.highlight(code, { language, ignoreIllegals: true }).value;
        }
    } catch (e) {
        highlighted = escapeHtml(code);
    }

    const lines = highlighted.split('\n');
    const width = String(lines.length).length;

    const html = lines.map((line, i) => {
        const num = String(i + 1).padStart(width, ' ');
        return `<div class="code-line" id="L${i + 1}">`
        + `<span class="line-number">${num}</span>`
        + `<span class="line-content">${line}</span>`
        + `</div>`;
    }).join('');

    codeBlock.innerHTML = `<div class="code-container">${html}</div>`;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// @anchor: codePreview_loadAnchors
// 从项目根的 .project_index.json 读当前文件的锚点列表
async function loadAnchors(path) {
    const parts = path.split('/');
    let projectRoot;
    if (parts[0] === 'sandbox' && parts.length >= 2) {
        projectRoot = parts[0] + '/' + parts[1];
    } else if (parts[0] === 'TestProjects' && parts.length >= 3) {
        projectRoot = parts[0] + '/' + parts[1] + '/' + parts[2];
    } else {
        return;
    }
    const relPath = path.substring(projectRoot.length + 1);

    try {
        const res = await fetch('/' + projectRoot + '/.project_index.json?t=' + Date.now());
        if (!res.ok) {
            anchorList.innerHTML = '<div class="anchor-empty">无锚点索引</div>';
            return;
        }
        const data = await res.json();
        const anchors = data[relPath];
        if (!anchors || !anchors.length) {
            anchorList.innerHTML = '<div class="anchor-empty">该文件无锚点</div>';
            return;
        }
        renderAnchors(anchors);
    } catch (e) {
        anchorList.innerHTML = '<div class="anchor-empty">锚点加载失败</div>';
    }
}

// @anchor: codePreview_renderAnchors
// 渲染锚点列表，点击滚动到对应行
function renderAnchors(anchors) {
    anchorList.innerHTML = '';
    for (const a of anchors) {
        const item = document.createElement('div');
        item.className = 'anchor-item';
        item.dataset.line = a.line;

        const idEl = document.createElement('div');
        idEl.className = 'anchor-id';
        idEl.textContent = a.id + ' · L' + a.line;
        item.appendChild(idEl);

        if (a.desc) {
            const descEl = document.createElement('div');
            descEl.className = 'anchor-desc';
            descEl.textContent = a.desc;
            item.appendChild(descEl);
        }

        item.addEventListener('click', () => scrollToLine(a.line));
        anchorList.appendChild(item);
    }
}

// @anchor: codePreview_scrollToLine
// 滚动到指定行并闪烁提示
function scrollToLine(line) {
    const el = document.getElementById('L' + line);
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    el.classList.add('flash');
    setTimeout(() => el.classList.remove('flash'), 1200);

    // 移动端：跳转后收起面板
    const panel = document.getElementById('anchorPanel');
    if (panel && window.innerWidth <= 768) {
        panel.classList.remove('show');
    }
}

// @anchor: codePreview_mobilePanel
// 移动端：打开/关闭全屏锚点面板
(function initMobilePanel() {
    const panel = document.getElementById('anchorPanel');
    const toggleBtn = document.getElementById('tocToggleBtn');
    const closeBtn = document.getElementById('anchorCloseBtn');
    if (!panel || !toggleBtn || !closeBtn) return;

    toggleBtn.addEventListener('click', () => {
        panel.classList.add('show');
    });
    closeBtn.addEventListener('click', () => {
        panel.classList.remove('show');
    });
})();