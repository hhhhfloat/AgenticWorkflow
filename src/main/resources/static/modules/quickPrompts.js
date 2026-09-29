// @anchor: modules_quickPrompts
// 常用话术快捷入口：支持自定义、持久化到 localStorage

// @anchor: modules_quickPrompts_defaults
// 默认话术：首次使用时写入 localStorage，之后以本地数据为准
const DEFAULT_QUICK_PROMPTS = [
    { label: '继续',   text: '继续完成 NEXT_STEP 中的任务' },
    { label: '初见',   text: '用build_anchor_index重建索引，并大致了解当前项目整体架构' },
    { label: '解释',   text: '简洁地解释你刚才的改动，说明为什么这样改，不要复述代码' },
    { label: '审计',   text: '对当前项目的代码进行分析与审计，找出潜在的问题，最后输出到一个md文档' },
    { label: '文档过长',   text: '以下文件过长需要重构拆分：' },
    { label: '总结+文档', text: '综合以上的所有修改，进行总结，并简化相关PROJECT.md部分，不留多余修改过程信息' },
    { label: '爆次数/系统异常', text: '上一轮工作异常中断了。核验上一轮的工作是否完成' },
    { label: '澄清', text: '如果有需要澄清的点，先行提出，不作修改' },
    { label: '先定方案',   text: '先理解当前需求，制作TODO.md，不具体执行' },
    { label: '没修好',   text: '刚才的问题并没有解决。重新寻找根因并尝试解决' },
    { label: '连续工具使用',   text: '记得工具可以一次调用多个，系统会顺序执行' },
];

const QUICK_PROMPTS_KEY = 'quickPrompts';

// @anchor: modules_quickPrompts_store
// localStorage 读写：空则回落到默认值
function loadQuickPrompts() {
    try {
        const raw = localStorage.getItem(QUICK_PROMPTS_KEY);
        if (raw) {
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) return parsed;
        }
    } catch (e) {}
    return DEFAULT_QUICK_PROMPTS.slice();
}

let quickPrompts = loadQuickPrompts();

function saveQuickPrompts() {
    try {
        localStorage.setItem(QUICK_PROMPTS_KEY, JSON.stringify(quickPrompts));
    } catch (e) {}
}

// @anchor: modules_quickPrompts_init
// 渲染话术 chips 到 #quickPromptBar
function initQuickPrompts() {
    renderQuickPrompts();
}

// @anchor: modules_quickPrompts_render
// 渲染 chips 列表，末尾附一个"+"按钮
function renderQuickPrompts() {
    const bar = document.getElementById('quickPromptBar');
    if (!bar) return;
    bar.innerHTML = '';

    quickPrompts.forEach((p, idx) => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'quick-prompt-chip';
        chip.textContent = p.label;
        chip.title = p.text + '\n\n（Shift+点击替换当前输入 · 右键删除）';
        chip.addEventListener('click', (e) => appendQuickPrompt(p.text, e.shiftKey));
        chip.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            removeQuickPrompt(idx);
        });
        bar.appendChild(chip);
    });

    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'quick-prompt-chip quick-prompt-add';
    addBtn.textContent = '＋';
    addBtn.title = '添加自定义话术（右键恢复默认）';
    addBtn.addEventListener('click', addQuickPrompt);
    addBtn.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        resetQuickPrompts();
    });
    bar.appendChild(addBtn);
}

// @anchor: modules_quickPrompts_add
// 添加一条自定义话术
function addQuickPrompt() {
    const label = prompt('话术名称（显示在按钮上，建议 2-4 字）:');
    if (!label || !label.trim()) return;

    const text = prompt('话术内容（点击后追加到输入框的文本）:');
    if (!text || !text.trim()) return;

    quickPrompts.push({ label: label.trim(), text: text.trim() });
    saveQuickPrompts();
    renderQuickPrompts();
}

// @anchor: modules_quickPrompts_remove
// 删除指定索引的话术（confirm 后生效）
function removeQuickPrompt(idx) {
    const target = quickPrompts[idx];
    if (!target) return;
    if (!confirm(`删除话术"${target.label}"？`)) return;

    quickPrompts.splice(idx, 1);
    saveQuickPrompts();
    renderQuickPrompts();
}

// @anchor: modules_quickPrompts_reset
// 恢复默认话术列表
function resetQuickPrompts() {
    if (!confirm('恢复默认话术列表？当前自定义内容将丢失。')) return;
    quickPrompts = DEFAULT_QUICK_PROMPTS.slice();
    saveQuickPrompts();
    renderQuickPrompts();
}

// @anchor: modules_quickPrompts_append
// 把话术追加到输入框末尾（Shift 点击则替换），触发 autoResize 并聚焦
function appendQuickPrompt(text, replace) {
    const promptEl = document.getElementById('prompt');
    if (!promptEl) return;

    if (replace) {
        promptEl.value = text;
    } else {
        const cur = promptEl.value;
        if (!cur) {
            promptEl.value = text;
        } else if (cur.endsWith('\n')) {
            promptEl.value = cur + text;
        } else {
            promptEl.value = cur + '\n' + text;
        }
    }

    promptEl.dispatchEvent(new Event('input'));
    promptEl.focus();
    promptEl.setSelectionRange(promptEl.value.length, promptEl.value.length);
}