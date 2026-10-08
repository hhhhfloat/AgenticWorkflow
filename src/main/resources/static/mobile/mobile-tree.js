// @anchor: mobileTree_intro
// 手机端文件树：浏览 sandbox / TestProjects，支持目录展开、文件预览、运行项目

const TREE_ROOTS = ['sandbox', 'TestProjects'];

async function fetchDir(path) {
    const url = '/browse?path=' + encodeURIComponent(path);
    const res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json();
}

function fileIcon(name) {
    const ext = (name.split('.').pop() || '').toLowerCase();
    const map = {
        html: '🌐', htm: '🌐', css: '🎨', js: '⚡', java: '☕',
        py: '🐍', cpp: '⚙️', c: '⚙️', h: '📋',
        json: '📋', md: '📝', txt: '📄',
        png: '🖼️', jpg: '🖼️', jpeg: '🖼️', svg: '🖼️', gif: '🖼️'
    };
    return map[ext] || '📄';
}

function buildDirNode(path, displayName, opts) {
    const { isRoot = false } = opts || {};
    const wrapper = document.createElement('div');
    wrapper.className = 'tree-node';
    wrapper.dataset.path = path;

    const label = document.createElement('div');
    label.className = 'tree-item';

    const arrow = document.createElement('span');
    arrow.className = 'arrow';
    arrow.textContent = '▶';
    label.appendChild(arrow);

    const icon = document.createElement('span');
    icon.textContent = isRoot ? '📂' : '📁';
    label.appendChild(icon);

    const nameEl = document.createElement('span');
    nameEl.className = 'node-label';
    nameEl.textContent = displayName;
    label.appendChild(nameEl);

    // 项目级操作按钮（sandbox/项目名 或 TestProjects/项目名）
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    const isTestProject = parts.length === 3 && parts[0] === 'TestProjects';

    if (isRoot && path === 'sandbox') {
        label.appendChild(makeActionBtn('➕', async () => {
            const name = prompt('新项目名称（字母、数字、-、_）：');
            if (!name) return;
            try {
                const res = await fetch('/createProject', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ project: name.trim() })
                });
                const data = await res.json();
                if (res.ok && data.status === 'ok') {
                    await renderMobileTree();
                    appendLog('[系统] 项目已创建: ' + name);
                } else {
                    alert('创建失败：' + (data.message || res.status));
                }
            } catch (e) { alert('创建失败：' + e.message); }
        }));
    } else if (isSandboxProject || isTestProject) {
        const projectName = isSandboxProject ? parts[1] : parts[2];
        label.appendChild(makeActionBtn('🔄', async () => {
            const result = await callToolMobile('build_anchor_index', { project_path: projectName });
            if (result !== null) appendLog('[系统] ✅ 锚点索引已重建: ' + projectName);
        }));
        label.appendChild(makeActionBtn('📊', () => {
            window.open('/scan.html?path=' + encodeURIComponent('sandbox/' + projectName), '_blank');
        }));
    }

    wrapper.appendChild(label);

    const children = document.createElement('div');
    children.className = 'tree-children';
    children.style.display = 'none';
    wrapper.appendChild(children);

    label.addEventListener('click', async (e) => {
        if (e.target.closest('.tree-action-btn')) return;
        const expanded = children.style.display === 'block';
        if (expanded) {
            children.style.display = 'none';
            arrow.textContent = '▶';
            return;
        }
        if (!children.dataset.loaded) {
            arrow.textContent = '⏳';
            try {
                const data = await fetchDir(path);
                renderTreeEntries(children, path, data.entries || []);
                children.dataset.loaded = '1';
            } catch (err) {
                children.innerHTML = '<div class="tree-empty">加载失败</div>';
            }
        }
        children.style.display = 'block';
        arrow.textContent = '▼';
    });

    return wrapper;
}

function makeActionBtn(text, handler) {
    const btn = document.createElement('button');
    btn.className = 'tree-action-btn';
    btn.textContent = text;
    btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        try { await handler(); } catch (err) { appendLog('[系统] 操作失败: ' + err.message); }
    });
    return btn;
}

function renderTreeEntries(container, parentPath, entries) {
    container.innerHTML = '';
    if (!entries.length) {
        container.innerHTML = '<div class="tree-empty">空目录</div>';
        return;
    }
    const frag = document.createDocumentFragment();
    for (const entry of entries) {
        if (entry.name.startsWith('.')) continue;
        const childPath = parentPath + '/' + entry.name;
        if (entry.type === 'dir') {
            frag.appendChild(buildDirNode(childPath, entry.name, {}));
        } else {
            frag.appendChild(buildFileNode(childPath, entry.name));
        }
    }
    container.appendChild(frag);
}

function buildFileNode(filePath, fileName) {
    const div = document.createElement('div');
    div.className = 'tree-item';
    div.dataset.path = filePath;

    const spacer = document.createElement('span');
    spacer.className = 'arrow';
    div.appendChild(spacer);

    const icon = document.createElement('span');
    icon.textContent = fileIcon(fileName);
    div.appendChild(icon);

    const name = document.createElement('span');
    name.className = 'node-label';
    name.textContent = fileName;
    div.appendChild(name);

    div.addEventListener('click', () => handleTreeFileClick(filePath));
    return div;
}

function handleTreeFileClick(path) {
    if (path.endsWith('.html') || path.endsWith('.htm')) {
        window.open('/' + path, '_blank');
    } else if (path.endsWith('.md')) {
        window.open('/md.html?path=' + encodeURIComponent(path), '_blank');
    } else {
        appendLog('[系统] 该文件类型暂不支持预览: ' + path);
    }
}

// 手机端调用后端工具（不依赖 toolApi.js）
async function callToolMobile(tool, args) {
    try {
        const res = await fetch('/tool', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ tool, args: args || {} })
        });
        const data = await res.json();
        if (data.status === 'success') return data.result;
        appendLog(`[工具] ❌ ${tool} 失败: ${data.message || res.status}`);
        return null;
    } catch (e) {
        appendLog(`[工具] ❌ ${tool} 请求失败: ${e.message}`);
        return null;
    }
}

async function renderMobileTree() {
    const rootsEl = document.getElementById('treeRoots');
    if (!rootsEl) return;
    rootsEl.innerHTML = '';
    for (const root of TREE_ROOTS) {
        rootsEl.appendChild(buildDirNode(root, root, { isRoot: true }));
    }
}