// @anchor: treeRender_intro
// 文件树渲染层：节点构建、目录渲染、文件点击

// ===== 节点构建 =====

// @anchor: treeRender_buildFileNode
// 构建文件节点 DOM（图标 + 文件名 + 点击事件）
function buildFileNode(filePath, fileName) {
    const fileDiv = document.createElement('div');
    fileDiv.className = 'tree-item';
    fileDiv.dataset.path = filePath;

    const ext = fileName.split('.').pop().toLowerCase();
    const iconMap = {
        'html': '🌐 ', 'htm': '🌐 ',
        'css': '🎨 ',
        'js': '⚡ ',
        'java': '☕ ',
        'json': '📋 ',
        'md': '📝 ',
        'png': '🖼️ ', 'jpg': '🖼️ ', 'jpeg': '🖼️ ', 'svg': '🖼️ ', 'gif': '🖼️ ', 'ico': '🖼️ ',
    };
    const icon = document.createElement('span');
    icon.textContent = iconMap[ext] || '📄 ';
    fileDiv.appendChild(icon);

    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = fileName;
    nameSpan.title = fileName;
    fileDiv.appendChild(nameSpan);

    fileDiv.addEventListener('click', (e) => {
        e.stopPropagation();
        handleFileClick(filePath);
    });

    return fileDiv;
}

// @anchor: treeRender_buildNodeShell
// 构建目录节点壳子：label + icon + name + badge + 操作按钮 + childrenContainer
// 不绑定点击事件（由调用方按场景绑定）
function buildNodeShell(path, opts) {
    const { isRoot = false, isTestProjects = false, hasIndexHtml = false } = opts || {};

    const wrapper = document.createElement('div');
    wrapper.className = 'tree-node';
    wrapper.dataset.path = path;

    const displayName = path.split('/').pop() || path;

    const label = document.createElement('div');
    label.className = 'tree-item';
    if (isRoot) {
        label.style.fontWeight = 'bold';
        label.style.color = '#9cdcfe';
    }

    const icon = document.createElement('span');
    icon.textContent = '📁 ';
    if (!isRoot) {
        icon.className = 'folder-icon-btn';
        icon.title = '在文件管理器中打开';
        icon.addEventListener('click', (e) => {
            e.stopPropagation();
            openFolder(path);
        });
    }
    label.appendChild(icon);

    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = displayName;
    nameSpan.title = displayName;
    label.appendChild(nameSpan);

    if (!isRoot && isTestProjects && hasIndexHtml) {
        const badge = document.createElement('span');
        badge.textContent = ' 🚀';
        badge.style.color = '#4ec9b0';
        badge.style.fontSize = '12px';
        label.appendChild(badge);
    }

    addActionButtons(label, path, isRoot);
    wrapper.appendChild(label);

    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'tree-children';
    childrenContainer.style.display = 'none';
    wrapper.appendChild(childrenContainer);

    return { wrapper, label, childrenContainer };
}

// ===== 渲染主逻辑 =====

// @anchor: treeRender_renderTreeNode
// 渲染一个目录节点。
// cache/targetPaths 传入则优先从缓存读取数据（同步路径）；
// 不传则点击时按需 fetch（异步懒加载路径）。
function renderTreeNode(path, container, isRoot = false, hasIndexHtml = false,
isTestProjects = false, cache = null, targetPaths = null) {
    const { wrapper, label, childrenContainer } = buildNodeShell(path, {
        isRoot, isTestProjects, hasIndexHtml
    });

    label.addEventListener('click', async (e) => {
        if (childrenContainer.dataset.loading === 'true') return;
        e.stopPropagation();

        if (childrenContainer.style.display === 'none') {
            if (!childrenContainer.dataset.loaded) {
                childrenContainer.dataset.loading = 'true';
                try {
                    const data = (cache && cache.has(path)) ? cache.get(path) : await fetchDir(path);
                    renderEntries(childrenContainer, path,
                        data ? data.entries : null, cache, targetPaths, false);
                    childrenContainer.dataset.loaded = 'true';
                } catch (err) {
                    showErrorMessage(childrenContainer, err.message);
                } finally {
                    childrenContainer.dataset.loading = 'false';
                }
            }
            childrenContainer.style.display = 'block';
        } else {
            childrenContainer.style.display = 'none';
        }
    });

    container.appendChild(wrapper);
}

// @anchor: treeRender_renderEntries
// 遍历 entries 渲染到容器。
// expand=true 时对 targetPaths 中命中的子目录同步展开（用于刷新保持展开态）。
// entries 为空 / null 时展示空目录消息。
function renderEntries(container, parentPath, entries, cache, targetPaths, expand) {
    if (!entries || entries.length === 0) {
        showEmptyMessage(container);
        return;
    }

    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
        const childPath = parentPath + '/' + entry.name;
        if (entry.type === 'dir') {
            const wrapper = document.createElement('div');
            renderTreeNode(childPath, wrapper, false,
                entry.hasIndexHtml || false,
                childPath.startsWith('TestProjects/'),
                cache, targetPaths);

            if (expand && targetPaths && targetPaths.has(childPath)) {
                const cc = wrapper.querySelector('.tree-children');
                const data = cache.get(childPath);
                if (cc && data && data.entries) {
                    cc.dataset.loaded = 'true';
                    cc.style.display = 'block';
                    renderEntries(cc, childPath, data.entries, cache, targetPaths, true);
                }
            }
            fragment.appendChild(wrapper);
        } else {
            fragment.appendChild(buildFileNode(childPath, entry.name));
        }
    }
    container.appendChild(fragment);
}

// ===== 文件点击 =====

// @anchor: treeRender_handleFileClick
// 处理文件点击：html 新窗口打开；md 跳转预览页
function handleFileClick(filePath) {
    appendLog(`[系统] 点击文件: ${filePath}`);

    if (filePath.endsWith('.html') || filePath.endsWith('.htm')) {
        if (filePath.startsWith('TestProjects/') || filePath.startsWith('sandbox/')) {
            const url = '/' + filePath;
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else {
            appendLog('[系统] 无法预览此文件');
        }
    } else if (filePath.endsWith('.md')) {
        window.open('/md.html?path=' + encodeURIComponent(filePath), '_blank');
    }
}

// ===== 辅助 =====

function showEmptyMessage(container) {
    const emptyMsg = document.createElement('div');
    emptyMsg.className = 'tree-item';
    emptyMsg.style.color = '#666';
    emptyMsg.style.fontStyle = 'italic';
    emptyMsg.textContent = '📭 空目录';
    container.appendChild(emptyMsg);
}

function showErrorMessage(container, msg) {
    const errMsg = document.createElement('div');
    errMsg.className = 'tree-item';
    errMsg.style.color = '#f44747';
    errMsg.textContent = '⚠️ 加载失败: ' + msg;
    container.appendChild(errMsg);
}