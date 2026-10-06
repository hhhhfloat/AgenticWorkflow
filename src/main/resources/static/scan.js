// @anchor: scan_intro
// 大文件扫描页面：加载、分档筛选、勾选、导出

// @anchor: scan_buckets
// 档位定义：每档含 min（含）、max（不含）、label、className
const SCAN_BUCKETS = [
    { key: 'all',   min: 0,     max: Infinity, label: '全部',       cls: '' },
    { key: '0-5',   min: 0,     max: 5000,     label: '0-5k',       cls: 'b-normal' },
    { key: '5-10',  min: 5000,  max: 10000,    label: '5-10k',      cls: 'b-large' },
    { key: '10-15', min: 10000, max: 15000,    label: '10-15k',     cls: 'b-attention' },
    { key: '15-20', min: 15000, max: 20000,    label: '15-20k',     cls: 'b-warn' },
    { key: '20-25', min: 20000, max: 25000,    label: '20-25k',     cls: 'b-warn' },
    { key: '25-30', min: 25000, max: 30000,    label: '25-30k',     cls: 'b-hot' },
    { key: '30+',   min: 30000, max: Infinity, label: '30k+',       cls: 'b-danger' },
];

let allFiles = [];
let currentBucket = 'all';
let selectedPaths = new Set();

// @anchor: scan_bucketOf
// 返回文件所属档位对象
function bucketOf(chars) {
    if (chars < 0) return SCAN_BUCKETS[SCAN_BUCKETS.length - 1];  // 超大标记为最高档
    for (let i = 1; i < SCAN_BUCKETS.length; i++) {
        const b = SCAN_BUCKETS[i];
        if (chars >= b.min && chars < b.max) return b;
    }
    return SCAN_BUCKETS[1];
}

// @anchor: scan_init
// 页面初始化：读取路径参数、拉数据、渲染
async function initScan() {
    const params = new URLSearchParams(location.search);
    const projectPath = params.get('path') || 'sandbox';
    document.getElementById('scanTitle').textContent = '📊 大文件扫描 · ' + projectPath;

    try {
        const res = await fetch('/scan-files?path=' + encodeURIComponent(projectPath));
        const data = await res.json();
        if (data.status !== 'ok') {
            document.getElementById('scanBody').innerHTML =
            '<div class="scan-empty">加载失败：' + (data.message || '未知错误') + '</div>';
            return;
        }
        allFiles = data.files || [];
        renderSummary(data);
        renderTabs();
        renderTable();
    } catch (err) {
        document.getElementById('scanBody').innerHTML =
        '<div class="scan-empty">请求失败：' + err.message + '</div>';
    }
}

// @anchor: scan_summary
// 顶部汇总：总文件数 + 各档计数
function renderSummary(data) {
    const counts = {};
    SCAN_BUCKETS.forEach(b => counts[b.key] = 0);
    allFiles.forEach(f => {
        const b = bucketOf(f.chars);
        counts[b.key]++;
    });

    const parts = [`共 ${data.total} 个文本文件`];
    SCAN_BUCKETS.slice(1).forEach(b => {
        if (counts[b.key] > 0) parts.push(`${b.label}: ${counts[b.key]}`);
    });
    document.getElementById('scanSummary').textContent = parts.join(' · ');
}

// @anchor: scan_tabs
// 渲染档位 tab
function renderTabs() {
    const tabs = document.getElementById('scanTabs');
    tabs.innerHTML = '';
    SCAN_BUCKETS.forEach(b => {
        const count = b.key === 'all'
            ? allFiles.length
            : allFiles.filter(f => bucketOf(f.chars).key === b.key).length;
        const btn = document.createElement('button');
        btn.className = 'scan-tab' + (b.key === currentBucket ? ' active' : '');
        btn.textContent = b.label + ' (' + count + ')';
        btn.addEventListener('click', () => {
            currentBucket = b.key;
            renderTabs();
            renderTable();
        });
        tabs.appendChild(btn);
    });
}

// @anchor: scan_table
// 渲染表格
function renderTable() {
    const tbody = document.getElementById('scanTbody');
    tbody.innerHTML = '';

    const filtered = currentBucket === 'all'
        ? allFiles
        : allFiles.filter(f => bucketOf(f.chars).key === currentBucket);

    if (filtered.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="scan-empty">该档位暂无文件</td></tr>';
        updateSelectedInfo();
        return;
    }

    filtered.forEach(f => {
        const tr = document.createElement('tr');
        const b = bucketOf(f.chars);

        const tdCheck = document.createElement('td');
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = selectedPaths.has(f.path);
        cb.addEventListener('change', () => {
            if (cb.checked) selectedPaths.add(f.path);
            else selectedPaths.delete(f.path);
            updateSelectedInfo();
        });
        tdCheck.appendChild(cb);
        tr.appendChild(tdCheck);

        const tdPath = document.createElement('td');
        tdPath.className = 'scan-path';
        tdPath.textContent = f.path;
        tr.appendChild(tdPath);

        const tdChars = document.createElement('td');
        tdChars.textContent = f.approximate ? '>5MB' : f.chars.toLocaleString();
        tr.appendChild(tdChars);

        const tdLines = document.createElement('td');
        tdLines.textContent = f.approximate ? '-' : f.lines;
        tr.appendChild(tdLines);

        const tdBucket = document.createElement('td');
        const tag = document.createElement('span');
        tag.className = 'scan-bucket ' + b.cls;
        tag.textContent = b.label;
        tdBucket.appendChild(tag);
        tr.appendChild(tdBucket);

        tbody.appendChild(tr);
    });

    updateSelectedInfo();
}

// @anchor: scan_selectedInfo
// 更新"已选 N 个"显示
function updateSelectedInfo() {
    document.getElementById('selectedInfo').textContent =
    '已选 ' + selectedPaths.size + ' 个';
}

// @anchor: scan_export
// 导出选中路径为 txt（每行一个）
function exportSelected() {
    if (selectedPaths.size === 0) {
        alert('没有选中任何文件');
        return;
    }
    downloadTxt(Array.from(selectedPaths), 'selected-files.txt');
}

// @anchor: scan_copy
// 复制选中路径到剪贴板
async function copySelected() {
    if (selectedPaths.size === 0) {
        alert('没有选中任何文件');
        return;
    }
    const text = Array.from(selectedPaths).join('\n');
    const ok = await copyToClipboard(text);
    if (ok) {
        alert('已复制 ' + selectedPaths.size + ' 个路径到剪贴板');
    } else {
        alert('复制失败：浏览器不支持或无权限');
    }
}
// @anchor: scan_exportFiltered
// 导出当前筛选列表
function exportFiltered() {
    const filtered = currentBucket === 'all'
        ? allFiles
        : allFiles.filter(f => bucketOf(f.chars).key === currentBucket);
    if (filtered.length === 0) {
        alert('当前筛选无文件');
        return;
    }
    downloadTxt(filtered.map(f => f.path), 'filtered-' + currentBucket + '.txt');
}

// @anchor: scan_download
// 触发浏览器下载 txt 文件
function downloadTxt(lines, filename) {
    const blob = new Blob([lines.join('\n')], {type: 'text/plain;charset=utf-8'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

// @anchor: scan_bind
// 绑定工具栏按钮
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('selectAll').addEventListener('change', (e) => {
        const filtered = currentBucket === 'all'
            ? allFiles
            : allFiles.filter(f => bucketOf(f.chars).key === currentBucket);
        if (e.target.checked) {
            filtered.forEach(f => selectedPaths.add(f.path));
        } else {
            filtered.forEach(f => selectedPaths.delete(f.path));
        }
        renderTable();
    });
    document.getElementById('exportSelectedBtn').addEventListener('click', exportSelected);
    document.getElementById('copySelectedBtn').addEventListener('click', copySelected);
    document.getElementById('exportFilteredBtn').addEventListener('click', exportFiltered);
    initScan();
});

// @anchor: scan_copyToClipboard
// 复制文本到剪贴板：优先 Clipboard API，回退 execCommand（非安全上下文兼容）
async function copyToClipboard(text) {
    // 优先：Clipboard API（仅在 HTTPS 或 localhost 下可用）
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) {
            // 权限被拒或异常，落回 fallback
        }
    }

    // 回退：execCommand（HTTP 下也可用）
    try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.top = '-9999px';
        ta.style.left = '-9999px';
        ta.setAttribute('readonly', '');
        document.body.appendChild(ta);
        ta.select();
        ta.setSelectionRange(0, ta.value.length);  // iOS 兼容
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
    } catch (e) {
        return false;
    }
}