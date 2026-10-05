// @anchor: modules_tree_api
// 文件树数据层：目录浏览、项目元信息、配置读取

// @anchor: modules_tree_fetchDir
async function fetchDir(path) {
    const url = `/browse?path=${encodeURIComponent(path)}`;
    const res = await fetch(url, {cache: 'no-cache'});
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    }
    return await res.json();
}

// @anchor: modules_tree_fetchMeta
// 拉取项目元信息，不存在或异常返回 null
async function fetchProjectMeta(path) {
    try {
        const res = await fetch(`/project-meta?path=${encodeURIComponent(path)}`);
        const meta = await res.json();
        return meta.exists === false ? null : meta;
    } catch (err) {
        return null;
    }
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