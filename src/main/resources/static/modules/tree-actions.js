// @anchor: modules_tree_actions
// 文件树操作层：节点按钮注入、更多菜单、上传对话框

// @anchor: modules_tree_addActionButtons
// 节点操作按钮注入：合并为单个 ⋯ 菜单
function addActionButtons(label, path, isRoot) {
    const parts = path.split('/');
    const isSandboxProject = parts.length === 2 && parts[0] === 'sandbox';
    const isTestProjectsProject = parts.length === 3 && parts[0] === 'TestProjects';

    const items = [];

    if (isRoot && path === 'sandbox') {
        items.push({
            icon: '📂', label: '在文件管理器中打开',
            onClick: async () => { await openFolder('sandbox'); }
        });
        items.push({
            icon: '➕', label: '创建新项目',
            onClick: async () => {
                const projectName = prompt('请输入新项目名称（仅允许字母、数字、- 和 _）：');
                if (projectName && projectName.trim()) {
                    await createProject(projectName.trim());
                }
            }
        });
    } else if (isRoot && path === 'TestProjects') {
        items.push({
            icon: '📂', label: '在文件管理器中打开',
            onClick: async () => { await openFolder('TestProjects'); }
        });
    } else if (isSandboxProject) {
        const projectName = parts[1];
        items.push({
            icon: '▶', label: '运行 ' + projectName,
            onClick: async () => {
                const meta = await fetchProjectMeta('sandbox/' + projectName);
                if (!meta) { appendLog('[系统] ❌ 该项目未注册运行入口'); return; }
                if (typeof runRegisteredProject === 'function') {
                    await runRegisteredProject(projectName, meta.filename, meta.mode, 'TestProjects');
                } else {
                    appendLog('[系统] ❌ runner.js 未加载');
                }
            }
        });
        items.push({
            icon: '📦', label: '归档到 TestProjects',
            onClick: async () => { await archiveProject(projectName); }
        });
        items.push({
            icon: '📤', label: '上传文件到项目',
            onClick: async () => { await uploadFilesDialog(projectName); }
        });
        items.push({ separator: true });
        items.push({
            icon: '🔄', label: '重建锚点索引',
            onClick: async () => { await callTool('build_anchor_index', {project_path: projectName}); }
        });
        items.push({
            icon: '📊', label: '扫描大文件',
            onClick: () => {
                window.open('/scan.html?path=' + encodeURIComponent('sandbox/' + projectName), '_blank');
            }
        });
    } else if (isTestProjectsProject) {
        const projectName = parts[2];
        items.push({
            icon: '▶', label: '运行 ' + projectName,
            onClick: async () => {
                const meta = await fetchProjectMeta(path);
                if (!meta) { appendLog('[系统] ❌ 该项目未注册运行入口'); return; }
                if (typeof runRegisteredProject === 'function') {
                    await runRegisteredProject(projectName, meta.filename, meta.mode, path);
                }
            }
        });
    }

    if (items.length > 0) {
        label.appendChild(createMoreBtn(items));
    }
}

// @anchor: modules_tree_uploadDialog
// 弹出文件选择框并上传到指定项目
async function uploadFilesDialog(projectName) {
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
}

// @anchor: modules_tree_moreBtn
// 创建 ⋯ 按钮，点击时弹出操作菜单
function createMoreBtn(items) {
    const btn = document.createElement('button');
    btn.className = 'tree-more-btn';
    btn.textContent = '⋯';
    btn.title = '更多操作';
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        e.preventDefault();
        showMoreMenu(btn, items);
    });
    return btn;
}

// ===== 更多菜单 =====

// @anchor: modules_tree_moreMenu
// 全局单例菜单元素与显示/隐藏逻辑
let _moreMenuEl = null;

function getMoreMenu() {
    if (_moreMenuEl) return _moreMenuEl;

    _moreMenuEl = document.createElement('div');
    _moreMenuEl.className = 'tree-more-menu';
    _moreMenuEl.style.display = 'none';
    document.body.appendChild(_moreMenuEl);

    document.addEventListener('click', (e) => {
        if (!_moreMenuEl.contains(e.target)) hideMoreMenu();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') hideMoreMenu();
    });
    window.addEventListener('resize', hideMoreMenu);
    window.addEventListener('scroll', hideMoreMenu, true);

    return _moreMenuEl;
}

function showMoreMenu(anchorBtn, items) {
    const menu = getMoreMenu();
    menu.innerHTML = '';

    items.forEach(it => {
        if (it.separator) {
            const sep = document.createElement('div');
            sep.className = 'tree-more-sep';
            menu.appendChild(sep);
            return;
        }
        const el = document.createElement('div');
        el.className = 'tree-more-item';
        el.textContent = it.icon + '  ' + it.label;
        el.addEventListener('click', async (e) => {
            e.stopPropagation();
            hideMoreMenu();
            try {
                await it.onClick(e);
            } catch (err) {
                appendLog('[系统] ❌ 操作失败: ' + err.message);
            }
        });
        menu.appendChild(el);
    });

    menu.style.display = 'block';
    menu.style.left = '-9999px';
    menu.style.top = '-9999px';

    requestAnimationFrame(() => {
        const rect = anchorBtn.getBoundingClientRect();
        const menuRect = menu.getBoundingClientRect();
        let left = rect.right + 4;
        let top = rect.top;

        if (left + menuRect.width > window.innerWidth - 8) {
            left = Math.max(8, rect.left - menuRect.width - 4);
        }
        if (top + menuRect.height > window.innerHeight - 8) {
            top = Math.max(8, window.innerHeight - menuRect.height - 8);
        }

        menu.style.left = left + 'px';
        menu.style.top = top + 'px';
    });
}

function hideMoreMenu() {
    if (_moreMenuEl) _moreMenuEl.style.display = 'none';
}