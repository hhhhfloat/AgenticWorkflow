// @anchor: modules_quickPrompts
// 常用话术快捷入口：点击把预设 prompt 追加到输入框末尾

// @anchor: modules_quickPrompts_data
// 话术数据：label 为按钮显示文本，text 为追加到输入框的内容
const QUICK_PROMPTS = [
    // ── 实用类 ──
    { label: '继续',   text: '继续完成 NEXT_STEP 中的任务' },
    { label: '初见',   text: '用build_anchor_index重建索引，并大致了解当前项目整体架构' },
    { label: '解释',   text: '简洁地解释你刚才的改动，说明为什么这样改，不要复述代码' },
    { label: '审计',   text: '对当前项目的代码进行分析与审计，找出潜在的问题，最后输出到一个md文档' },
    { label: '文档过长',   text: '以下文件过长需要重构拆分：' },
    { label: '总结', text: '综合以上的所有修改，进行总结，并简化相关PROJECT.md部分，不留' },
    { label: '更新文档', text: '把本轮的改动整合进 UPDATE.md，并同步 PROJECT.md 中受影响的章节' },
    // ── 语气类 ──
    { label: '说人话', text: '用简单直白的语言说明，避免术语堆砌和长篇大论' },
    { label: '别啰嗦', text: '直接给完整实现，跳过中间解释和寒暄' },
    { label: '慢着',   text: '先别改代码，只分析原因和影响范围，等我确认方案' },
    { label: '重来',   text: '上一个方案不对，换个思路重做。先说明新思路再动手' },
];

// @anchor: modules_quickPrompts_init
// 渲染话术 chips 到 #quickPromptBar
function initQuickPrompts() {
    const bar = document.getElementById('quickPromptBar');
    if (!bar) return;

    bar.innerHTML = '';
    QUICK_PROMPTS.forEach(p => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'quick-prompt-chip';
        chip.textContent = p.label;
        chip.title = p.text + '\n\n（Shift+点击 替换当前输入）';
        chip.addEventListener('click', (e) => appendQuickPrompt(p.text, e.shiftKey));
        bar.appendChild(chip);
    });
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