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
            refreshSidebar();
        } else {
            appendLog(`[系统] ❌ 归档失败: ${data.message || '未知错误'}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 归档失败: ${err.message}`);
    } finally {
        if (btn) btn.disabled = false;
    }
}

/**
 * 刷新侧边栏：重新加载 TestProjects 目录
 */
function refreshSidebar() {
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    const testRoot = sidebarContent.querySelector('[data-path="TestProjects"]');
    if (!testRoot) return;

    const treeNode = testRoot.closest('.tree-node');
    if (!treeNode) return;

    const childrenContainer = treeNode.querySelector('.tree-children');
    if (!childrenContainer) return;

    childrenContainer.innerHTML = '';
    childrenContainer.dataset.loaded = '';
    childrenContainer.style.display = 'none';

    const label = treeNode.querySelector('.tree-item');
    if (label) {
        label.click();
    }
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
            refreshSandbox();
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
            refreshSandbox();
        } else {
            appendLog(`[系统] ❌ 上传失败: ${data.message}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 上传失败: ${err.message}`);
    }
}

// @anchor: modules_refreshSandbox
// ===== 刷新沙箱目录树 =====
function refreshSandbox() {
    const sidebarContent = document.getElementById('sidebarContent');
    if (!sidebarContent) return;

    const sandboxRoot = sidebarContent.querySelector('[data-path="sandbox"]');
    if (!sandboxRoot) return;

    const treeNode = sandboxRoot.closest('.tree-node');
    if (!treeNode) return;

    const childrenContainer = treeNode.querySelector('.tree-children');
    if (!childrenContainer) return;

    clearCacheRecursively(childrenContainer);

    childrenContainer.innerHTML = '';
    childrenContainer.dataset.loaded = '';
    childrenContainer.style.display = 'none';

    const label = treeNode.querySelector('.tree-item');
    if (label) {
        label.click();
    }
}
