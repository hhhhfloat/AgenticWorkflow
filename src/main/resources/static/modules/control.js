// @anchor: control_intro
// 桌面端控制权：拉取服务到电脑端，或在被锁时显示 banner

async function refreshController() {
    try {
        const res = await fetch('/control/status');
        const data = await res.json();
        const controller = data.controller;
        const banner = document.getElementById('mobileLockBanner');
        const bannerText = document.getElementById('lockBannerText');
        const switchBtn = document.getElementById('switchControllerBtn');

        const isMe = controller === 'DESKTOP';

        // banner：仅对方控制时显示
        if (banner) {
            if (controller && !isMe) {
                banner.style.display = 'flex';
                if (bannerText) {
                    bannerText.textContent = controller === 'MOBILE'
                        ? '📱 手机端正在控制中'
                        : '另一端正在控制中';
                }
            } else {
                banner.style.display = 'none';
            }
        }

        // 顶部按钮：固定"接管到电脑端"
        if (switchBtn) {
            if (isMe) {
                switchBtn.textContent = '💻 电脑端控制中';
                switchBtn.disabled = true;
                switchBtn.classList.add('active');
            } else {
                switchBtn.textContent = '💻 接管到电脑端';
                switchBtn.disabled = false;
                switchBtn.classList.remove('active');
            }
        }
    } catch (e) {
        // 静默
    }
}

async function switchController() {
    try {
        const res = await fetch('/control/switch', { method: 'POST' });
        const data = await res.json();
        await refreshController();

        if (data.controller !== 'DESKTOP') return;

        appendLog('[系统] 💻 已拉取控制权到电脑端');

        // 若另一端正在跑任务，跳过去并订阅实时日志
        if (data.runningSessionId) {
            if (data.runningSessionId !== getCurrentSessionId()) {
                setCurrentSessionId(data.runningSessionId);
                await loadSessionHistory(data.runningSessionId);
                highlightCurrentSession();
                renderUsagePanel();
                await loadSessionList();
            }
            if (typeof openStream === 'function' && !isLocalRunActive()) {
                openStream(data.runningSessionId);
            }
        }
    } catch (e) {
        appendLog('[系统] ❌ 拉取失败: ' + e.message);
    }
}

function initControl() {
    const switchBtn = document.getElementById('switchControllerBtn');
    if (switchBtn) switchBtn.addEventListener('click', switchController);

    const bannerSwitch = document.getElementById('lockBannerSwitchBtn');
    if (bannerSwitch) bannerSwitch.addEventListener('click', switchController);

    refreshController();
    setInterval(refreshController, 5000);
}

document.addEventListener('DOMContentLoaded', initControl);