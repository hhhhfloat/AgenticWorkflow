async function refreshController() {
    try {
        const res = await fetch(BASE_URL + '/control/status');
        const data = await res.json();
        const btn = document.getElementById('switchControllerBtn');
        if (!btn) return;

        const controller = data.controller;
        const isMe = controller === 'MOBILE';

        if (isMe) {
            btn.textContent = '📱';
            btn.disabled = true;
            btn.title = '手机端控制中';
            btn.classList.add('active');
        } else {
            btn.textContent = '⇄';
            btn.disabled = false;
            btn.title = '点击接管到手机端';
            btn.classList.remove('active');
        }
    } catch (e) { /* 静默 */ }
}

async function switchController() {
    try {
        const res = await fetch(BASE_URL + '/control/switch', { method: 'POST' });
        const data = await res.json();
        await refreshController();
        if (data.controller === 'MOBILE') {
            appendLog('[系统] 📱 已拉取控制权到手机端');
        }
    } catch (e) {
        appendLog('[系统] ❌ 拉取失败: ' + e.message);
    }
}