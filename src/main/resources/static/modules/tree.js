// @anchor: modules_tree
// 文件树核心：目录浏览、节点渲染与项目操作按钮注入

// ===== 数据获取 =====

async function fetchDir(path) {
    const url = `/browse?path=${encodeURIComponent(path)}`;
    const res = await fetch(url, {cache: 'no-cache'});
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    }
    return await res.json();
}

// ===== 节点构建（无状态，无事件） =====

// @anchor: modules_tree_buildFileNode
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

// @anchor: modules_tree_buildNodeShell
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

// @anchor: modules_tree_renderTreeNode
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
                    if (data && data.entries && data.entries.length > 0) {
                        appendEntries(childrenContainer, path, data.entries, cache, targetPaths);
                    } else {
                        showEmptyMessage(childrenContainer);
                    }
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

// @anchor: modules_tree_appendEntries
// 遍历 entries 渲染到容器（不主动展开子节点）
function appendEntries(container, parentPath, entries, cache, targetPaths) {
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
        const childPath = parentPath + '/' + entry.name;
        if (entry.type === 'dir') {
            const wrapper = document.createElement('div');
            renderTreeNode(childPath, wrapper, false,
                entry.hasIndexHtml || false,
                childPath.startsWith('TestProjects/'),
                cache, targetPaths);
            fragment.appendChild(wrapper);
        } else {
            fragment.appendChild(buildFileNode(childPath, entry.name));
        }
    }
    container.appendChild(fragment);
}

// @anchor: modules_tree_renderAndExpand
// 从缓存递归渲染并按 targetPaths 自动展开（用于刷新时保持展开状态）
function renderAndExpand(container, parentPath, entries, cache, targetPaths) {
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
        const childPath = parentPath + '/' + entry.name;
        if (entry.type === 'dir') {
            const wrapper = document.createElement('div');
            renderTreeNode(childPath, wrapper, false,
                entry.hasIndexHtml || false,
                childPath.startsWith('TestProjects/'),
                cache, targetPaths);

            if (targetPaths && targetPaths.has(childPath)) {
                const cc = wrapper.querySelector('.tree-children');
                const data = cache.get(childPath);
                if (cc && data && data.entries) {
                    cc.dataset.loaded = 'true';
                    cc.style.display = 'block';
                    renderAndExpand(cc, childPath, data.entries, cache, targetPaths);
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

// @anchor: modules_tree_handleFileClick
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

// @anchor: modules_loadConfig
// 从后端 /config 读取最大迭代次数并回填输入框
async function loadConfig() {
    try {
        const res = await fetch('/config');
        const config = await res.json();
        if (config.maxIterations) {
            const input = document.getElementById('maxIterations');
            if (input) {
                input.value = config.maxIterations;
            }
        }
    } catch (e) {
        console.warn('配置加载失败，使用默认值');
    }
}

// @anchor: modules_clearCache
// 递归清除文件树各级节点的加载缓存标记
function clearCacheRecursively(container) {
    const nodes = container.querySelectorAll('.tree-node');
    nodes.forEach(node => {
        const childContainer = node.querySelector('.tree-children');
        if (childContainer) {
            childContainer.dataset.loaded = '';
            clearCacheRecursively(childContainer);
        }
    });
}

// @anchor: modules_tree_helpers
// 文件树刷新辅助：收集展开路径、逐级展开、后台预加载缓存

function getExpandedPaths(container) {
    const paths = [];
    const nodes = container.querySelectorAll('.tree-node');
    nodes.forEach(node => {
        const cc = node.querySelector('.tree-children');
        if (cc && cc.style.display === 'block') {
            let parent = node.parentElement;
            let allParentsExpanded = true;
            while (parent && parent !== container && parent.closest) {
                const parentNode = parent.closest('.tree-node');
                if (parentNode) {
                    const parentChildren = parentNode.querySelector('.tree-children');
                    if (parentChildren && parentChildren.style.display !== 'block') {
                        allParentsExpanded = false;
                        break;
                    }
                    parent = parentNode.parentElement;
                } else {
                    break;
                }
            }
            if (allParentsExpanded) {
                paths.push(node.dataset.path);
            }
        }
    });
    return paths;
}

async function expandPath(path, container) {
    const parts = path.split('/');
    let currentPath = '';

    for (const part of parts) {
        currentPath = currentPath ? currentPath + '/' + part : part;

        const node = container.querySelector(`.tree-node[data-path="${currentPath}"]`);
        if (!node) return false;

        const childrenContainer = node.querySelector('.tree-children');
        if (!childrenContainer) continue;

        if (childrenContainer.style.display === 'block') continue;

        const label = node.querySelector('.tree-item');
        if (!label) return false;

        label.click();

        for (let i = 0; i < 50; i++) {
            await new Promise(r => setTimeout(r, 100));
            if (childrenContainer.dataset.loaded === 'true') break;
        }
    }
    return true;
}

async function preloadPaths(path, targetPaths, cache) {
    if (cache.has(path)) return cache;

    try {
        const data = await fetchDir(path);
        cache.set(path, data);

        const children = data.entries || [];
        const subPaths = children
            .filter(e => e.type === 'dir')
            .map(e => path + '/' + e.name);

        for (const subPath of subPaths) {
            const shouldLoad = targetPaths.has(subPath) ||
            Array.from(targetPaths).some(tp => tp.startsWith(subPath + '/'));
            if (shouldLoad) {
                await preloadPaths(subPath, targetPaths, cache);
            }
        }
    } catch (err) {
        console.warn(`预加载失败: ${path}`, err);
    }

    return cache;
}

// @anchor: modules_tree_sync
// 节点操作按钮注入：归档 / 上传 / 运行 / 打开文件夹 / 新建项目

function addActionButtons(label, path, isRoot) {
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    const isTestProjectsProject = parts.length === 3 && parts[0] === 'TestProjects';

    if (isRoot && path === 'sandbox') {
        label.appendChild(createBtn('📂', '📂 在文件管理器中打开 sandbox 文件夹', async (e) => {
            e.stopPropagation();
            await openFolder('sandbox');
        }));
        label.appendChild(createBtn('➕', '➕ 在 sandbox 下创建新项目文件夹', async (e) => {
            e.stopPropagation();
            const projectName = prompt('请输入新项目名称（仅允许字母、数字、- 和 _）：');
            if (projectName && projectName.trim()) {
                await createProject(projectName.trim());
            }
        }));
        return;
    }

    if (isRoot && path === 'TestProjects') {
        label.appendChild(createBtn('📂', '📂 在文件管理器中打开 TestProjects 文件夹', async (e) => {
            e.stopPropagation();
            await openFolder('TestProjects');
        }));
        return;
    }

    if (isSandboxProject) {
        const projectName = parts[1];
        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=sandbox/${projectName}`);
                const meta = await metaRes.json();
                if (meta.exists !== false) {
                    label.appendChild(createBtn('▶', `▶ 运行 ${projectName}`, async (e) => {
                        e.stopPropagation();
                        if (typeof runRegisteredProject === 'function') {
                            await runRegisteredProject(projectName, meta.filename, meta.mode, 'TestProjects');
                        } else {
                            appendLog('[系统] ❌ runner.js 未加载');
                        }
                    }));
                }
            } catch (err) { /* 静默失败 */ }
        })();

        const archiveBtn = createBtn('📦', '📦 归档此项目到 TestProjects', async (e) => {
            e.stopPropagation();
            await archiveProject(projectName);
        });
        archiveBtn.classList.add('archive-btn');
        archiveBtn.dataset.project = projectName;
        label.appendChild(archiveBtn);

        label.appendChild(createBtn('📤', '📤 上传文件到此项目', async (e) => {
            e.stopPropagation();
            const input = document.createElement('input');
            input.type = 'file';
            input.multiple = true;
            input.style.display = 'none';
            document.body.appendChild(input);
            input.addEventListener('change', async () => {
                if (input.files && input.files.length > 0) {
                    await uploadFiles(projectName, input.files);
                }
                document.body.removeChild(input);
            });
            input.click();
        }));
        return;
    }

    if (isTestProjectsProject) {
        const projectName = parts[2];
        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=${encodeURIComponent(path)}`);
                const meta = await metaRes.json();
                if (meta.exists !== false) {
                    label.appendChild(createBtn('▶', `▶ 运行 ${projectName}`, async (e) => {
                        e.stopPropagation();
                        if (typeof runRegisteredProject === 'function') {
                            await runRegisteredProject(projectName, meta.filename, meta.mode, path);
                        }
                    }));
                }
            } catch (err) { /* 静默失败 */ }
        })();
    }
}

function createBtn(text, title, onClick) {
    const btn = document.createElement('button');
    btn.className = 'tree-action-btn';
    btn.title = title;
    btn.textContent = text;
    btn.addEventListener('click', onClick);
    return btn;
}