// @anchor: modules_history
// ===== 历史记录模块 =====
function getHistory() {
    try {
        const data = localStorage.getItem(STORAGE_KEY);
        return data ? JSON.parse(data) : [];
    } catch (e) {
        return [];
    }
}

function saveHistory(history) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(history));
    } catch (e) {
        // 存储空间不足时忽略
    }
}

function addToHistory(prompt) {
    if (!prompt || !prompt.trim()) return;
    const history = getHistory();
    const filtered = history.filter(item => item !== prompt);
    filtered.unshift(prompt);
    if (filtered.length > MAX_HISTORY) {
        filtered.length = MAX_HISTORY;
    }
    saveHistory(filtered);
    return filtered;
}

function renderHistory() {
    const history = getHistory();
    let container = document.getElementById('historyContainer');
    let dropdown = document.getElementById('historyDropdown');

    if (!container) {
        container = document.createElement('div');
        container.id = 'historyContainer';
        const textarea = document.getElementById('prompt');
        textarea.parentNode.insertBefore(container, textarea.nextSibling);
    }

    container.innerHTML = '';

    dropdown = document.createElement('select');
    dropdown.id = 'historyDropdown';
    dropdown.style.cssText =
        'width:100%; margin-top:4px; background:#2d2d2d; color:#fff; border:1px solid #444; padding:6px; font-size:13px;';
    container.appendChild(dropdown);

    dropdown.innerHTML = '<option value="">📜 历史记录</option>';
    if (history.length === 0) {
        dropdown.innerHTML += '<option value="" disabled>（暂无记录）</option>';
    } else {
        history.forEach(item => {
            const option = document.createElement('option');
            option.value = item;
            const display = item.length > 60 ? item.substring(0, 60) + '…' : item;
            option.textContent = display;
            dropdown.appendChild(option);
        });
        const clearOption = document.createElement('option');
        clearOption.value = '__clear__';
        clearOption.textContent = '🗑 清空所有历史';
        clearOption.style.color = '#f44747';
        dropdown.appendChild(clearOption);
    }

    dropdown.addEventListener('change', function () {
        if (this.value === '__clear__') {
            if (confirm('确认清空所有历史记录吗？')) {
                localStorage.removeItem(STORAGE_KEY);
                renderHistory();
                appendLog('[系统] 已清空所有历史记录');
            }
            this.value = '';
            return;
        }
        if (this.value) {
            document.getElementById('prompt').value = this.value;
            this.value = '';
        }
    });
}
