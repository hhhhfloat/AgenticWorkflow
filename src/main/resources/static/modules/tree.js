// @anchor: modules_tree
// ===== 文件树核心逻辑 =====

/**
 * 调用后端 /browse 接口获取目录内容
 */
async function fetchDir(path) {
    const url = `/browse?path=${encodeURIComponent(path)}`;
    const res = await fetch(url);
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
        e.stopPropagation();

        const isHidden = childrenContainer.style.display === 'none';

        if (isHidden) {
            if (!childrenContainer.dataset.loaded) {
                try {
                    const data = await fetchDir(path);
                    childrenContainer.dataset.loaded = 'true';

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
                } catch (err) {
                    console.error('加载目录失败:', err);
                    showErrorMessage(childrenContainer, err.message);
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
