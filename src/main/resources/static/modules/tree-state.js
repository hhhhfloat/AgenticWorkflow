// @anchor: treeState_intro
// 文件树状态层：展开路径收集、逐级展开、缓存预加载

// @anchor: treeState_clearCache
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

// @anchor: treeState_getExpandedPaths
// 收集所有"自身展开且所有祖先都展开"的目录路径
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

// @anchor: treeState_expandPath
// 逐级点击展开到目标路径
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

// @anchor: treeState_preloadPaths
// 递归预加载 targetPaths 及其祖先链，返回缓存 Map
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