// 手机端控制权：查询当前控制端、切换到本端

async function refreshController() {
    try {
        const res = await fetch(BASE_URL + '/control/status');
        const data = await res.json();
        const btn = document.getElementById('switchControllerBtn');
        if (!btn) return;

        const isMe = data.controller === 'MOBILE';
        // 当前是手机端占用 → 按钮提示"切到电脑端"
        // 当前是电脑端占用 → 按钮提示"切到手机端"
        btn.textContent = isMe ? '⇄ 切到电脑端' : '⇄ 切到手机端';
        btn.dataset.target = isMe ? 'DESKTOP' : 'MOBILE';
    } catch (e) {
        // 忽略，下轮重试
    }
}

async function switchController() {
    try {
        const res = await fetch(BASE_URL + '/control/switch', { method: 'POST' });
        const data = await res.json();
        await refreshController();
        if (data.controller === 'MOBILE') {
            appendLog('[系统] 📱 已切到手机端，本端恢复操作');
        } else {
            appendLog('[系统] 💻 已切到电脑端，本端操作已暂停');
        }
    } catch (e) {
        appendLog('[系统] ❌ 切换失败: ' + e.message);
    }
}