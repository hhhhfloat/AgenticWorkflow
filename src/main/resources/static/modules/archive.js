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
            await refreshSidebar();  // 异步等待
        } else {
            appendLog(`[系统] ❌ 归档失败: ${data.message || '未知错误'}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 归档失败: ${err.message}`);
    } finally {
        if (btn) btn.disabled = false;
    }
}

// ===== 通用的刷新根节点函数（保持展开状态） =====
async function refreshRoot(rootPath) {
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    const rootElement = sidebarContent.querySelector(`[data-path="${rootPath}"]`);
    if (!rootElement) return;

    const treeNode = rootElement.closest('.tree-node');
    if (!treeNode) return;

    const childrenContainer = treeNode.querySelector('.tree-children');
    if (!childrenContainer) return;

    // 1. 保存当前展开的子路径
    const expandedPaths = getExpandedPaths(childrenContainer);

    // 2. 清空并刷新（原有逻辑）
    clearCacheRecursively(childrenContainer);
    childrenContainer.innerHTML = '';
    childrenContainer.dataset.loaded = '';
    childrenContainer.style.display = 'none';

    const label = treeNode.querySelector('.tree-item');
    if (label) {
        label.click();
    }

    // 3. 等待根节点加载完成（最多等待 3 秒）
    await new Promise((resolve) => {
        let attempts = 0;
        const maxAttempts = 30; // 30 * 100ms = 3s
        const check = setInterval(() => {
            if (childrenContainer.dataset.loaded === 'true' || attempts >= maxAttempts) {
                clearInterval(check);
                resolve();
            }
            attempts++;
        }, 100);
    });

    // 4. 恢复展开状态
    for (const path of expandedPaths) {
        await expandPath(path, sidebarContent);
    }
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