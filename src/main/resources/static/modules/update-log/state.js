// @anchor: ulState_intro
// 更新档案馆的状态容器与通用工具（无依赖，必须最先加载）
//
// 全局状态全部挂在 `UL` 上，其他模块只读写该对象，不再各自持有副本。
// 工具函数统一以 `ul` 前缀导出到全局，供后续脚本直接调用。

// @anchor: ulState_object
// 全局状态：项目、线程数据、选中项、检索词、加载与错误态
const UL = {
    project: '',            // 站点相对路径，如 sandbox/archive-page
    threads: [],            // GET /update-log/data 的 threads 原始数组
    selectedThreadId: null, // 右栏当前展示的线程
    search: '',             // 左侧检索关键词
    loading: false,
    remoteReloading: false, // 静默刷新中（不显示骨架）
    error: null,
    theme: 'gh-dark'
};

// @anchor: ulState_dom
// DOM 引用集中获取：脚本在 body 末尾加载，首次取用即可安全查询
const ULDom = {
    _cache: {},
    get(id) {
        if (!this._cache[id]) {
            this._cache[id] = document.getElementById(id);
        }
        return this._cache[id];
    },
    reset() {
        this._cache = {};
    }
};

// @anchor: ulState_escape
// HTML/属性转义，所有拼接到 innerHTML 的动态文本都必须经过这里
function ulEscapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function ulEscapeRegExp(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// @anchor: ulState_misc
// 杂项工具：防抖、唯一 id、空值归一
function ulDebounce(fn, delay) {
    let timer = null;
    return function debounced(...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}

let _ulUidSeed = 0;
function ulUid(prefix) {
    _ulUidSeed += 1;
    return (prefix || 'ul') + '-' + _ulUidSeed;
}

function ulText(value) {
    return value === null || value === undefined ? '' : String(value);
}

// @anchor: ulState_time
// 时间格式化：仅展示到分钟；相对时间用于 title 提示
function ulFormatTimestamp(raw) {
    const text = ulText(raw).trim();
    if (!text) return '未知时间';
    return text.length > 16 ? text.slice(0, 16) : text;
}

function ulRelativeTime(raw) {
    const text = ulText(raw).trim();
    if (!text) return '';
    const parsed = new Date(text.replace(' ', 'T'));
    if (Number.isNaN(parsed.getTime())) return text;
    const diff = Date.now() - parsed.getTime();
    const minute = 60 * 1000;
    const hour = 60 * minute;
    const day = 24 * hour;
    if (diff < minute) return '刚刚';
    if (diff < hour) return Math.floor(diff / minute) + ' 分钟前';
    if (diff < day) return Math.floor(diff / hour) + ' 小时前';
    if (diff < 30 * day) return Math.floor(diff / day) + ' 天前';
    return ulFormatTimestamp(text);
}

// @anchor: ulState_threadStats
// 线程派生信息：记录数、最后更新时间、是否命中检索
function ulUpdateCount(thread) {
    return Array.isArray(thread && thread.updates) ? thread.updates.length : 0;
}

function ulLastUpdated(thread) {
    const updates = (thread && thread.updates) || [];
    return updates.length ? ulText(updates[0].timestamp) : '';
}

function ulThreadHaystack(thread) {
    const parts = [ulText(thread.title), ulText(thread.thread_id), ulText(thread.status)];
    (thread.updates || []).forEach((update) => {
        parts.push(ulText(update.summary), ulText(update.details));
        if (Array.isArray(update.files)) parts.push(update.files.join(' '));
        parts.push(ulText(update.timestamp));
    });
    return parts.join('\n').toLowerCase();
}

// @anchor: ulState_sortFilter
// 线程排序（最后更新倒序，空线程沉底）与关键词过滤
function ulSortThreads(threads) {
    return threads.slice().sort((a, b) => {
        const ta = ulLastUpdated(a);
        const tb = ulLastUpdated(b);
        if (ta && !tb) return -1;
        if (!ta && tb) return 1;
        if (ta !== tb) return ta < tb ? 1 : -1;
        return ulText(a.thread_id).localeCompare(ulText(b.thread_id));
    });
}

function ulFilterThreads(threads, keyword) {
    const query = ulText(keyword).trim().toLowerCase();
    if (!query) return threads;
    const tokens = query.split(/\s+/).filter(Boolean);
    return threads.filter((thread) => {
        const haystack = ulThreadHaystack(thread);
        return tokens.every((token) => haystack.indexOf(token) >= 0);
    });
}

function ulFindThread(threadId) {
    return UL.threads.find((thread) => thread.thread_id === threadId) || null;
}

// @anchor: ulState_highlight
// 检索高亮：先转义再包裹，杜绝注入
function ulHighlight(value, keyword) {
    const text = ulText(value);
    const query = ulText(keyword).trim();
    if (!query) return ulEscapeHtml(text);
    const pattern = new RegExp('(' + ulEscapeRegExp(query) + ')', 'gi');
    return ulEscapeHtml(text).replace(pattern, '<mark>$1</mark>');
}

// @anchor: ulState_markdown
// Markdown 渲染：marked 优先，输出经白名单清洗（去脚本、内联事件、javascript: 链接）
const UL_BLOCKED_TAGS = ['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'LINK', 'META', 'FORM', 'INPUT', 'TEXTAREA'];

function ulSanitizeHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = ulText(html);
    const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_ELEMENT);
    const doomed = [];
    while (walker.nextNode()) {
        const el = walker.currentNode;
        if (UL_BLOCKED_TAGS.indexOf(el.tagName) >= 0) {
            doomed.push(el);
            continue;
        }
        Array.from(el.attributes).forEach((attr) => {
            const name = attr.name.toLowerCase();
            const isUrlAttr = name === 'href' || name === 'src' || name === 'xlink:href';
            if (name.indexOf('on') === 0 || (isUrlAttr && /^\s*(javascript|data):/i.test(attr.value))) {
                el.removeAttribute(attr.name);
            }
        });
        if (el.tagName === 'A') {
            el.setAttribute('target', '_blank');
            el.setAttribute('rel', 'noopener noreferrer');
        }
    }
    doomed.forEach((el) => el.remove());
    return template.innerHTML;
}

function ulRenderMarkdown(source) {
    const text = ulText(source);
    if (!text.trim()) return '';
    if (window.marked && typeof window.marked.parse === 'function') {
        try {
            const html = window.marked.parse(text, { gfm: true, breaks: true });
            return ulSanitizeHtml(html);
        } catch (err) {
            // 落到纯文本降级路径
        }
    }
    return ulEscapeHtml(text).replace(/\n/g, '<br>');
}

// @anchor: ulState_fileLink
// 文件标签跳转地址：剥离前缀，并根据扩展名路由到对应的预览页（md / html / 图片 / 代码）
function ulFileHref(fileName) {
    let path = ulText(fileName).trim().replace(/^\.\//, '').replace(/^\/+/, '');
    let proj = ulText(UL.project).trim().replace(/^\.\//, '').replace(/^\/+/, '');
    
    // 1. 剥离 UL.project 可能带的 "sandbox/" 前缀
    if (proj.startsWith('sandbox/')) {
        proj = proj.substring('sandbox/'.length);
    }

    // 2. 剥离 fileName 可能带的 "sandbox/" 前缀
    if (path.startsWith('sandbox/')) {
        path = path.substring('sandbox/'.length);
    }

    // 3. 剥离 fileName 可能带的纯项目名前缀
    if (path.startsWith(proj + '/')) {
        path = path.substring(proj.length + 1);
    }

    // 4. 统一拼装最终的站点相对路径
    const finalPath = 'sandbox/' + proj + '/' + path;
    const encodedPath = encodeURIComponent(finalPath);

    // 5. 根据扩展名路由（与 tree-render.js 的逻辑保持一致）
    const ext = path.split('.').pop().toLowerCase();
    
    if (ext === 'md') {
        return '/md.html?path=' + encodedPath;
    }
    if (ext === 'html' || ext === 'htm') {
        // 对于 html 文件，树里是直接在新窗口打开运行，这里保持一致
        return '/' + finalPath;
    }
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'svg'].includes(ext)) {
        return '/' + finalPath;
    }

    // 默认走代码预览
    return '/code.html?path=' + encodedPath;
}

function ulParseFiles(raw) {
    return ulText(raw)
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean);
}
