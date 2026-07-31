// @anchor: modules_tree
// ===== 文件树核心逻辑 =====

/**
 * 调用后端 /browse 接口获取目录内容
 */
async function fetchDir(path) {
    const url = `/browse?path=${encodeURIComponent(path)}`;
    const res = await fetch(url, {cache : 'no-cache'});
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    }
    return await res.json();
}

/**
 * 渲染一个树节点
 * @param {string} path           - 相对路径
 * @param {HTMLElement} container - 父容器
 * @param {boolean} isRoot        - 是否为根节点
 * @param {boolean} hasIndexHtml  - 该目录是否包含 index.html（TestProjects 专用）
 * @param {boolean} isTestProjects - 是否在 TestProjects 下
 */
function renderTreeNode(path, container, isRoot = false, hasIndexHtml = false, isTestProjects = false) {
    const wrapper = document.createElement('div');
    wrapper.className = 'tree-node';
    wrapper.dataset.path = path;

    const displayName = path.split('/').pop() || path;

    // ---- 创建节点标签 ----
    const label = document.createElement('div');
    label.className = 'tree-item';
    if (isRoot) {
        label.style.fontWeight = 'bold';
        label.style.color = '#9cdcfe';
    }

    // 图标
    const iconSpan = document.createElement('span');
    iconSpan.textContent = '📁 ';
    label.appendChild(iconSpan);

    // 名称
    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = displayName;
    nameSpan.title = displayName;
    label.appendChild(nameSpan);

    // ---- 如果是 sandbox 根节点，添加操作按钮 ----
    if (isRoot && path === 'sandbox') {
        // 1. 打开文件夹按钮
        const openBtn = document.createElement('button');
        openBtn.className = 'tree-action-btn';
        openBtn.title = '📂 在文件管理器中打开 sandbox 文件夹';
        openBtn.textContent = '📂';
        openBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await openFolder('sandbox');
        });
        label.appendChild(openBtn);

        // 2. 新建项目按钮
        const createBtn = document.createElement('button');
        createBtn.className = 'tree-action-btn';
        createBtn.title = '➕ 在 sandbox 下创建新项目文件夹';
        createBtn.textContent = '➕';
        createBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const projectName = prompt('请输入新项目名称（仅允许字母、数字、- 和 _）：');
            if (projectName && projectName.trim()) {
                await createProject(projectName.trim());
            }
        });
        label.appendChild(createBtn);
    }

    // ---- 如果是 TestProjects 下的项目入口，添加 🚀 标记 ----
    if (!isRoot && isTestProjects && hasIndexHtml) {
        const badge = document.createElement('span');
        badge.textContent = ' 🚀';
        badge.style.color = '#4ec9b0';
        badge.style.fontSize = '12px';
        label.appendChild(badge);
    }

    // ---- 如果是 sandbox 下的一级子目录，添加归档、上传和运行按钮 ----
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    if (isSandboxProject) {
        const projectName = parts[1];

        // ===== 1. 查询注册表，决定是否显示运行按钮 =====
        // 使用一个立即执行的异步函数，不影响渲染
        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=sandbox/${projectName}`);
                const meta = await metaRes.json();

                // 如果存在注册信息，添加运行按钮
                if (meta.exists !== false) {
                    const runBtn = document.createElement('button');
                    runBtn.className = 'tree-action-btn';
                    runBtn.title = `▶ 运行 ${projectName}`;
                    runBtn.textContent = '▶';
                    runBtn.dataset.project = projectName;
                    runBtn.dataset.entryFile = meta.filename;
                    runBtn.dataset.mode = meta.mode;
                    runBtn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        // 调用 runner.js 中的函数
                        if (typeof runRegisteredProject === 'function') {
                            await runRegisteredProject(projectName, meta.filename, meta.mode, 'TestProjects');
                        } else {
                            appendLog('[系统] ❌ runner.js 未加载，无法运行项目');
                        }
                    });
                    label.appendChild(runBtn);
                }
            } catch (err) {
                // 静默失败，不显示运行按钮
                console.warn('查询项目注册信息失败:', err);
            }
        })();

        // ===== 2. 归档按钮（原有） =====
        const archiveBtn = document.createElement('button');
        archiveBtn.className = 'tree-action-btn';
        archiveBtn.title = '📦 归档此项目到 TestProjects';
        archiveBtn.textContent = '📦';
        archiveBtn.dataset.project = projectName;
        archiveBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await archiveProject(projectName);
        });
        label.appendChild(archiveBtn);

        // ===== 3. 上传按钮（原有） =====
        const uploadBtn = document.createElement('button');
        uploadBtn.className = 'tree-action-btn';
        uploadBtn.title = '📤 上传文件到此项目';
        uploadBtn.textContent = '📤';
        uploadBtn.dataset.project = projectName;
        uploadBtn.addEventListener('click', async (e) => {
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
        });
        label.appendChild(uploadBtn);
    }

    // ---- 如果是 TestProjects 根节点，添加打开文件夹按钮 ----
    if (isRoot && path === 'TestProjects') {
        const openBtn = document.createElement('button');
        openBtn.className = 'tree-action-btn';
        openBtn.title = '📂 在文件管理器中打开 TestProjects 文件夹';
        openBtn.textContent = '📂';
        openBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await openFolder('TestProjects');
        });
        label.appendChild(openBtn);
    }

    wrapper.appendChild(label);

    // ---- 子节点容器（初始隐藏） ----
    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'tree-children';
    childrenContainer.style.display = 'none';
    wrapper.appendChild(childrenContainer);

    // ---- 如果是 TestProjects 下的一级子目录，添加运行按钮 ----
    const isTestProjectsProject = parts.length === 3 && parts[0] === 'TestProjects';
    if (isTestProjectsProject) {
        const projectName = parts[2]; // 项目名

        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=${encodeURIComponent(path)}`);
                const meta = await metaRes.json();
                if (meta.exists !== false) {
                    const runBtn = document.createElement('button');
                    runBtn.className = 'tree-action-btn';
                    runBtn.title = `▶ 运行 ${projectName}`;
                    runBtn.textContent = '▶';
                    runBtn.dataset.project = projectName;
                    runBtn.dataset.entryFile = meta.filename;
                    runBtn.dataset.mode = meta.mode;
                    runBtn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        if (typeof runRegisteredProject === 'function') {
                            // 传入完整的路径作为 displayPath
                            await runRegisteredProject(projectName, meta.filename, meta.mode, path);
                        } else {
                            appendLog('[系统] ❌ runner.js 未加载，无法运行项目');
                        }
                    });
                    label.appendChild(runBtn);
                }
            } catch (err) {
                console.warn('查询 TestProjects 项目注册信息失败:', err);
            }
        })();
    }


    label.addEventListener('click', async (e) => {
        // 在 label.addEventListener 内部开头添加
        if (childrenContainer.dataset.loading === 'true') {
            return; // 正在加载中，直接忽略本次点击
        }
        e.stopPropagation();

        const isHidden = childrenContainer.style.display === 'none';

        if (isHidden) {
            if (!childrenContainer.dataset.loaded) {
                try {
                    const data = await fetchDir(path);
                    if (data.entries && data.entries.length > 0) {
                        for (const entry of data.entries) {
                            const childPath = path + '/' + entry.name;
                            if (entry.type === 'dir') {
                                const childIsTestProjects = childPath.startsWith('TestProjects/');
                                renderTreeNode(
                                    childPath,
                                    childrenContainer,
                                    false,
                                    entry.hasIndexHtml || false,
                                    childIsTestProjects
                                );
                            } else {
                                renderFileNode(childPath, entry.name, childrenContainer);
                            }
                        }
                    } else {
                        showEmptyMessage(childrenContainer);
                    }
                    childrenContainer.dataset.loaded = 'true';
                } catch (err) {
                    console.error('加载目录失败:', err);
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

/**
 * 渲染文件节点
 */
function renderFileNode(filePath, fileName, container) {
    const fileDiv = document.createElement('div');
    fileDiv.className = 'tree-item';
    fileDiv.dataset.path = filePath;

    const fileIcon = document.createElement('span');
    const ext = fileName.split('.').pop().toLowerCase();
    if (ext === 'html' || ext === 'htm') fileIcon.textContent = '🌐 ';
    else if (ext === 'css') fileIcon.textContent = '🎨 ';
    else if (ext === 'js') fileIcon.textContent = '⚡ ';
    else if (ext === 'java') fileIcon.textContent = '☕ ';
    else if (ext === 'json') fileIcon.textContent = '📋 ';
    else if (ext === 'md') fileIcon.textContent = '📝 ';
    else if (['png', 'jpg', 'jpeg', 'svg', 'gif', 'ico'].includes(ext)) fileIcon.textContent = '🖼️ ';
    else fileIcon.textContent = '📄 ';
    fileDiv.appendChild(fileIcon);

    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = fileName;
    nameSpan.title = fileName;
    fileDiv.appendChild(nameSpan);

    fileDiv.addEventListener('click', (e) => {
        e.stopPropagation();
        handleFileClick(filePath);
    });

    container.appendChild(fileDiv);
}


/**
 * 处理文件点击
 */
function handleFileClick(filePath) {
    appendLog(`[系统] 点击文件: ${filePath}`);

    if (filePath.endsWith('.html') || filePath.endsWith('.htm')) {
        if (filePath.startsWith('TestProjects/')) {
            const url = '/' + filePath;
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else if (filePath.startsWith('sandbox/')) {
            const url = '/' + filePath;
            appendLog(`[系统] 在浏览器中打开: ${url}`);
            window.open(url, '_blank');
        } else {
            appendLog('[系统] 无法预览此文件');
        }
    }
}

/**
 * 辅助：显示空目录
 */
function showEmptyMessage(container) {
    const emptyMsg = document.createElement('div');
    emptyMsg.className = 'tree-item';
    emptyMsg.style.color = '#666';
    emptyMsg.style.fontStyle = 'italic';
    emptyMsg.textContent = '📭 空目录';
    container.appendChild(emptyMsg);
}

/**
 * 辅助：显示错误信息
 */
function showErrorMessage(container, msg) {
    const errMsg = document.createElement('div');
    errMsg.className = 'tree-item';
    errMsg.style.color = '#f44747';
    errMsg.textContent = '⚠️ 加载失败: ' + msg;
    container.appendChild(errMsg);
}

// @anchor: modules_loadConfig
// ===== 从后端加载配置 =====
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
// ===== 缓存清除工具 =====
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
// ===== 树刷新状态保持辅助函数 =====

/**
 * 收集容器中所有已展开且可见的目录路径
 * 只记录那些所有父级也都展开的节点
 */
function getExpandedPaths(container) {
    const paths = [];
    const nodes = container.querySelectorAll('.tree-node');
    nodes.forEach(node => {
        const cc = node.querySelector('.tree-children');
        if (cc && cc.style.display === 'block') {
            // 检查该节点的所有父级是否都处于展开状态
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

/**
 * 异步逐级展开指定路径的树节点
 * 例如传入 "sandbox/project1/src"，会依次展开 sandbox、sandbox/project1、sandbox/project1/src
 * @param {string} path - 要展开的完整路径
 * @param {HTMLElement} container - 查找的根容器
 * @returns {Promise<boolean>} 是否成功展开
 */
async function expandPath(path, container) {
    const parts = path.split('/');
    let currentPath = '';

    for (const part of parts) {
        currentPath = currentPath ? currentPath + '/' + part : part;

        const node = container.querySelector(`.tree-node[data-path="${currentPath}"]`);
        if (!node) return false;

        const childrenContainer = node.querySelector('.tree-children');
        if (!childrenContainer) continue; // 文件节点，无子节点，跳过

        // 已经展开则跳过点击
        if (childrenContainer.style.display === 'block') continue;

        const label = node.querySelector('.tree-item');
        if (!label) return false;

        label.click();

        // 等待异步加载完成（最多等待 5 秒）
        for (let i = 0; i < 50; i++) {
            await new Promise(r => setTimeout(r, 100));
            if (childrenContainer.dataset.loaded === 'true') break;
        }
    }
    return true;
}

/**
 * 后台递归加载路径及其子节点数据（不操作 DOM）
 * @param {string} path - 要加载的路径
 * @param {Set<string>} targetPaths - 需要展开的目标路径集合
 * @param {Map<string, any>} cache - 缓存加载结果
 * @returns {Promise<Map<string, any>>} 加载完成后的缓存
 */
async function preloadPaths(path, targetPaths, cache) {
    // 如果已经加载过，跳过
    if (cache.has(path)) return cache;

    try {
        const data = await fetchDir(path);
        cache.set(path, data);

        // 找到所有需要展开的子路径
        const children = data.entries || [];
        const subPaths = children
            .filter(e => e.type === 'dir')
            .map(e => path + '/' + e.name);

        // 递归加载所有需要展开的子路径
        for (const subPath of subPaths) {
            // 检查这个子路径是否在目标展开集合中（或其子路径在目标集合中）
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
// ===== 同步渲染版本（用于预加载一次性渲染） =====

/**
 * 同步渲染树节点（不包含异步加载逻辑，直接从缓存读取）
 */
// ============================================================
// 2. 重写 renderTreeNodeSync（补全按钮 + 补全点击展开事件）
// ============================================================
function renderTreeNodeSync(path, wrapper, isRoot = false, hasIndexHtml = false, isTestProjects = false, cache = null, targetPaths = null) {
    wrapper.className = 'tree-node';
    wrapper.dataset.path = path;

    const displayName = path.split('/').pop() || path;

    // ---- 节点标签 ----
    const label = document.createElement('div');
    label.className = 'tree-item';
    if (isRoot) {
        label.style.fontWeight = 'bold';
        label.style.color = '#9cdcfe';
    }

    const iconSpan = document.createElement('span');
    iconSpan.textContent = '📁 ';
    label.appendChild(iconSpan);

    const nameSpan = document.createElement('span');
    nameSpan.className = 'node-label';
    nameSpan.textContent = displayName;
    nameSpan.title = displayName;
    label.appendChild(nameSpan);

    // TestProjects 项目标记
    if (!isRoot && isTestProjects && hasIndexHtml) {
        const badge = document.createElement('span');
        badge.textContent = ' 🚀';
        badge.style.color = '#4ec9b0';
        badge.style.fontSize = '12px';
        label.appendChild(badge);
    }

    // ⭐ 关键修复：调用公共方法添加操作按钮
    addActionButtons(label, path, isRoot);

    wrapper.appendChild(label);

    // ---- 子节点容器 ----
    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'tree-children';
    childrenContainer.style.display = 'none';
    wrapper.appendChild(childrenContainer);

    // ⭐ 关键修复：补全点击事件（支持展开/折叠，优先使用缓存，否则回退 fetch）
    label.addEventListener('click', async (e) => {
        // 在 label.addEventListener 内部开头添加
        if (childrenContainer.dataset.loading === 'true') {
            return; // 正在加载中，直接忽略本次点击
        }

        e.stopPropagation();
        const isHidden = childrenContainer.style.display === 'none';

        if (isHidden) {
            // 如果还没有加载数据，尝试从传入的 cache 读取，否则走异步 fetch
            if (!childrenContainer.dataset.loaded) {
                // 优先使用预加载缓存
                if (cache && cache.has(path)) {
                    // 直接从缓存渲染子节点
                    const data = cache.get(path);
                    if (data && data.entries) {
                        const fragment = document.createDocumentFragment();
                        for (const entry of data.entries) {
                            const childPath = path + '/' + entry.name;
                            if (entry.type === 'dir') {
                                const childWrapper = document.createElement('div');
                                // 递归调用自身，但这次传入 cache 以便后续展开也能用
                                renderTreeNodeSync(
                                    childPath, childWrapper, false,
                                    entry.hasIndexHtml || false,
                                    childPath.startsWith('TestProjects/'),
                                    cache, targetPaths
                                );
                                fragment.appendChild(childWrapper);
                            } else {
                                const fileNode = createFileNode(childPath, entry.name);
                                fragment.appendChild(fileNode);
                            }
                        }
                        childrenContainer.appendChild(fragment);
                        childrenContainer.dataset.loaded = 'true';
                    }
                } else {
                    // 没有缓存，走原始的异步加载逻辑（复制自 renderTreeNode）
                    try {
                        const data = await fetchDir(path);
                        childrenContainer.dataset.loaded = 'true';
                        if (data.entries && data.entries.length > 0) {
                            for (const entry of data.entries) {
                                const childPath = path + '/' + entry.name;
                                if (entry.type === 'dir') {
                                    const childIsTestProjects = childPath.startsWith('TestProjects/');
                                    renderTreeNode(
                                        childPath, childrenContainer, false,
                                        entry.hasIndexHtml || false,
                                        childIsTestProjects
                                    );
                                } else {
                                    renderFileNode(childPath, entry.name, childrenContainer);
                                }
                            }
                        } else {
                            showEmptyMessage(childrenContainer);
                        }
                    } catch (err) {
                        showErrorMessage(childrenContainer, err.message);
                    }
                }
            }
            childrenContainer.style.display = 'block';
        } else {
            childrenContainer.style.display = 'none';
        }
    });
}

/**
 * 创建文件节点（同步版本）
 */
function createFileNode(filePath, fileName) {
    const fileDiv = document.createElement('div');
    fileDiv.className = 'tree-item';
    fileDiv.dataset.path = filePath;

    const fileIcon = document.createElement('span');
    const ext = fileName.split('.').pop().toLowerCase();
    // ... 与 renderFileNode 逻辑相同
    const iconMap = {
        'html': '🌐 ', 'htm': '🌐 ',
        'css': '🎨 ',
        'js': '⚡ ',
        'java': '☕ ',
        'json': '📋 ',
        'md': '📝 ',
    };
    fileIcon.textContent = iconMap[ext] || '📄 ';
    fileDiv.appendChild(fileIcon);

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

// ============================================================
// 1. 抽离公共方法：为节点添加操作按钮（供异步和同步渲染共用）
// ============================================================
function addActionButtons(label, path, isRoot) {
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    const isTestProjectsProject = parts.length === 3 && parts[0] === 'TestProjects';

    // ---- 如果是 sandbox 根节点 ----
    if (isRoot && path === 'sandbox') {
        const openBtn = createBtn('📂', '📂 在文件管理器中打开 sandbox 文件夹', async (e) => {
            e.stopPropagation();
            await openFolder('sandbox');
        });
        label.appendChild(openBtn);

        const createBtn = createBtn('➕', '➕ 在 sandbox 下创建新项目文件夹', async (e) => {
            e.stopPropagation();
            const projectName = prompt('请输入新项目名称（仅允许字母、数字、- 和 _）：');
            if (projectName && projectName.trim()) {
                await createProject(projectName.trim());
            }
        });
        label.appendChild(createBtn);
        return;
    }

    // ---- 如果是 TestProjects 根节点 ----
    if (isRoot && path === 'TestProjects') {
        const openBtn = createBtn('📂', '📂 在文件管理器中打开 TestProjects 文件夹', async (e) => {
            e.stopPropagation();
            await openFolder('TestProjects');
        });
        label.appendChild(openBtn);
        return;
    }

    // ---- sandbox 下的一级子目录（项目） ----
    if (isSandboxProject) {
        const projectName = parts[1];
        // 异步查询注册表显示运行按钮（保持原有逻辑）
        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=sandbox/${projectName}`);
                const meta = await metaRes.json();
                if (meta.exists !== false) {
                    const runBtn = createBtn('▶', `▶ 运行 ${projectName}`, async (e) => {
                        e.stopPropagation();
                        if (typeof runRegisteredProject === 'function') {
                            await runRegisteredProject(projectName, meta.filename, meta.mode, 'TestProjects');
                        } else {
                            appendLog('[系统] ❌ runner.js 未加载');
                        }
                    });
                    label.appendChild(runBtn);
                }
            } catch (err) { /* 静默失败 */ }
        })();

        // 归档
        const archiveBtn = createBtn('📦', '📦 归档此项目到 TestProjects', async (e) => {
            e.stopPropagation();
            await archiveProject(projectName);
        });
        // ⭐ 加一行：添加额外类名，让 archive.js 能选中它
        archiveBtn.classList.add('archive-btn');
        label.appendChild(archiveBtn);

        // 上传
        const uploadBtn = createBtn('📤', '📤 上传文件到此项目', async (e) => {
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
        });
        label.appendChild(uploadBtn);
        return;
    }

    // ---- TestProjects 下的一级子目录（项目）- 仅显示运行按钮 ----
    if (isTestProjectsProject) {
        const projectName = parts[2];
        (async () => {
            try {
                const metaRes = await fetch(`/project-meta?path=${encodeURIComponent(path)}`);
                const meta = await metaRes.json();
                if (meta.exists !== false) {
                    const runBtn = createBtn('▶', `▶ 运行 ${projectName}`, async (e) => {
                        e.stopPropagation();
                        if (typeof runRegisteredProject === 'function') {
                            await runRegisteredProject(projectName, meta.filename, meta.mode, path);
                        }
                    });
                    label.appendChild(runBtn);
                }
            } catch (err) { /* 静默失败 */ }
        })();
    }
}

// 辅助：快速创建按钮
function createBtn(text, title, onClick) {
    const btn = document.createElement('button');
    btn.className = 'tree-action-btn';
    btn.title = title;
    btn.textContent = text;
    btn.addEventListener('click', onClick);
    return btn;
}