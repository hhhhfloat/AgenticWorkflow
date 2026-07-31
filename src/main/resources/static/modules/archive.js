// @anchor: modules_archive
// ===== 归档功能 =====

async function archiveProject(projectName) {
    const btn = document.querySelector(`.archive-btn[data-project="${projectName}"]`);
    if (btn) btn.disabled = true;

    try {
        let res = await fetch('/archive', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectName })
        });

        let data = await res.json();

        if (data.status === 'exists') {
            const confirmMsg = `项目 "${projectName}" 已存在于 TestProjects/v0_0/，是否覆盖？`;
            if (!confirm(confirmMsg)) {
                appendLog(`[系统] 已取消归档: ${projectName}`);
                return;
            }

            res = await fetch('/archive?force=true', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ projectName })
            });
            data = await res.json();
        }

        if (data.status === 'success') {
            appendLog(`[系统] ✅ 项目已归档: ${data.path}`);
            // ⭐ 必须同时刷新两个目录：Sandbox 移除项目，TestProjects 添加项目
            await refreshSandbox();
            await refreshSidebar();
        } else {
            appendLog(`[系统] ❌ 归档失败: ${data.message || '未知错误'}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 归档失败: ${err.message}`);
    } finally {
        if (btn) btn.disabled = false;
    }
}

// @anchor: modules_archive
// ===== 归档功能 =====

let refreshLock = false;  // ← 添加全局锁

// ============================================================
// 修复 refreshRoot：如果根节点原本是展开的，即使没有子目录展开，也保持展开并刷新内容
// ============================================================
async function refreshRoot(rootPath) {
    if (refreshLock) {
        console.log(`⏭️ 跳过并发刷新: ${rootPath}`);
        return;
    }
    refreshLock = true;

    try {
        const sidebarContent = document.getElementById('sidebarContent');
        if (!sidebarContent) return;

        const rootElement = sidebarContent.querySelector(`[data-path="${rootPath}"]`);
        if (!rootElement) return;

        const treeNode = rootElement.closest('.tree-node');
        if (!treeNode) return;

        const childrenContainer = treeNode.querySelector('.tree-children');
        if (!childrenContainer) return;

        // ⭐ 关键修复 1：记录当前是否展开（而不是仅看 expandedPaths 是否为空）
        const wasExpanded = childrenContainer.style.display === 'block';

        // 获取当前展开的子路径
        const expandedPaths = getExpandedPaths(childrenContainer);

        // 如果之前没有展开过，直接清空隐藏退出
        if (!wasExpanded && expandedPaths.length === 0) {
            childrenContainer.innerHTML = '';
            childrenContainer.dataset.loaded = '';
            childrenContainer.style.display = 'none';
            return;
        }

        // 构建目标路径集合（包含根路径和所有展开的子路径）
        const targetPaths = new Set(expandedPaths);
        if (wasExpanded) {
            targetPaths.add(rootPath); // 根节点展开也要加载其直接子节点
        }

        // 预加载所有数据（后台一次性加载）
        const cache = new Map();
        await preloadPaths(rootPath, targetPaths, cache);

        // 清空并重新渲染
        childrenContainer.innerHTML = '';
        childrenContainer.dataset.loaded = '';

        // ⭐ 关键修复 2：如果之前是展开的，或者有展开的子节点，设置为 block
        if (wasExpanded || expandedPaths.length > 0) {
            childrenContainer.style.display = 'block';
            childrenContainer.dataset.loaded = 'true';

            // 从缓存渲染根节点下的内容（直接子节点）
            const data = cache.get(rootPath);
            if (data && data.entries) {
                if(data.entries.length === 0){
                    showEmptyMessage(childrenContainer);
                } else {
                    const fragment = document.createDocumentFragment();
                    for (const entry of data.entries) {
                        const childPath = rootPath + '/' + entry.name;
                        if (entry.type === 'dir') {
                            const wrapper = document.createElement('div');
                            // 使用同步渲染，传入 cache 和 targetPaths 以支持二级缓存展开
                            renderTreeNodeSync(
                                childPath, wrapper, false,
                                entry.hasIndexHtml || false,
                                childPath.startsWith('TestProjects/'),
                                cache, targetPaths
                            );
                            fragment.appendChild(wrapper);
                        } else {
                            const fileNode = createFileNode(childPath, entry.name);
                            fragment.appendChild(fileNode);
                        }
                    }
                    childrenContainer.appendChild(fragment);
                }
            } else {
                showEmptyMessage(childrenContainer);
            }
        } else {
            childrenContainer.style.display = 'none';
        }

    } finally {
        refreshLock = false;
    }
}

/**
 * 从缓存中递归渲染树节点（一次性渲染，不触发网络请求）
 */
function renderFromCache(path, container, cache, targetPaths) {
    const data = cache.get(path);
    if (!data || !data.entries) return;

    const fragment = document.createDocumentFragment();

    for (const entry of data.entries) {
        const childPath = path + '/' + entry.name;
        if (entry.type === 'dir') {
            const childIsTestProjects = childPath.startsWith('TestProjects/');
            // 创建目录节点
            const wrapper = document.createElement('div');
            wrapper.className = 'tree-node';
            wrapper.dataset.path = childPath;

            // ... 创建 label（这部分可以和 renderTreeNode 复用，但需要去掉异步加载逻辑）
            // 这里为了简洁，调用一个改造后的 renderTreeNodeSync 方法
            renderTreeNodeSync(childPath, wrapper, false, entry.hasIndexHtml || false, childIsTestProjects, cache, targetPaths);

            // 如果该目录在目标展开集合中，展开其子节点
            if (targetPaths.has(childPath)) {
                const childContainer = wrapper.querySelector('.tree-children');
                if (childContainer) {
                    childContainer.dataset.loaded = 'true';
                    childContainer.style.display = 'block';
                    // 递归渲染子节点
                    renderFromCache(childPath, childContainer, cache, targetPaths);
                }
            }

            fragment.appendChild(wrapper);
        } else {
            // 文件节点
            const fileDiv = createFileNode(childPath, entry.name);
            fragment.appendChild(fileDiv);
        }
    }

    container.appendChild(fragment);
}
/**
 * 刷新沙箱目录树（保持展开状态）
 */
async function refreshSandbox() {
    await refreshRoot('sandbox');
}

/**
 * 刷新侧边栏：重新加载 TestProjects 目录（保持展开状态）
 */
async function refreshSidebar() {
    await refreshRoot('TestProjects');
}

// @anchor: modules_createProject
// ===== 创建项目 =====
async function createProject(projectName) {
    if (!/^[a-zA-Z0-9\-_]+$/.test(projectName)) {
        alert('项目名仅允许字母、数字、- 和 _');
        return;
    }
    try {
        const res = await fetch('/createProject', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectName })
        });
        const data = await res.json();
        if (data.status === 'success') {
            appendLog(`[系统] ✅ 项目已创建: ${data.path}`);
            await refreshSandbox();
        } else if (data.status === 'exists') {
            alert(`项目 "${projectName}" 已存在，请换一个名称`);
        } else {
            appendLog(`[系统] ❌ 创建失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 创建失败: ${err.message}`);
    }
}

// @anchor: modules_upload
// ===== 文件上传 =====
async function uploadFiles(projectName, fileList) {
    if (!fileList || fileList.length === 0) return;

    const formData = new FormData();
    formData.append('projectName', projectName);
    for (const file of fileList) {
        formData.append('files', file);
    }

    try {
        let res = await fetch('/upload', {
            method: 'POST',
            body: formData
        });
        let data = await res.json();

        if (data.status === 'check' && data.existing && data.existing.length > 0) {
            const confirmMsg = `以下 ${data.existing.length} 个文件已存在：\n${data.existing.join('\n')}\n\n是否覆盖？`;
            if (!confirm(confirmMsg)) {
                appendLog(`[系统] 已取消上传: ${projectName}`);
                return;
            }
            res = await fetch('/upload?force=true', {
                method: 'POST',
                body: formData
            });
            data = await res.json();
        }

        if (data.status === 'success' || data.status === 'partial') {
            const uploaded = data.uploaded || [];
            const failed = data.failed || [];
            let msg = `✅ 上传完成: ${uploaded.length} 个文件`;
            if (failed.length > 0) {
                msg += `，${failed.length} 个失败 (${failed.join(', ')})`;
            }
            appendLog(`[系统] ${msg}`);
            await refreshSandbox();
        } else {
            appendLog(`[系统] ❌ 上传失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 上传失败: ${err.message}`);
    }
}

