// @anchor: modules_control
// 控制权模块：查询当前控制端、切换控制权并更新 UI

// @anchor: modules_control_refresh
// 拉取当前控制端，更新 banner 与顶部按钮
async function refreshController() {
    try {
        const res = await fetch('/control/status');
        const data = await res.json();
        const controller = data.controller;   // "MOBILE" | "DESKTOP" | null

        const banner = document.getElementById('mobileLockBanner');
        const bannerText = document.getElementById('lockBannerText');
        const switchBtn = document.getElementById('switchControllerBtn');

        const isMe = controller === 'DESKTOP';

        // banner：非自己控制时显示
        if (banner) {
            if (controller && !isMe) {
                banner.style.display = 'flex';
                if (bannerText) {
                    bannerText.textContent = controller === 'MOBILE'
                        ? '📱 手机端正在控制中，本设备操作已暂停'
                        : '另一端正在控制中，本设备操作已暂停';
                }
            } else {
                banner.style.display = 'none';
            }
        }

        // 顶部按钮：文案随控制端切换
        if (switchBtn) {
            switchBtn.textContent = isMe ? '⇄ 切到手机端' : '⇄ 切到电脑端';
        }
    } catch (e) {
        // 静默，下轮重试
    }
}

// @anchor: modules_control_switch
// 请求切换控制权到本端（桌面端），成功后刷新页面
async function switchController() {
    try {
        await fetch('/control/switch', { method: 'POST' });
    } catch (e) {
        // 忽略
    }
    location.reload();
}

// @anchor: modules_control_init
// 绑定按钮并启动定时刷新
function initControl() {
    const switchBtn = document.getElementById('switchControllerBtn');
    if (switchBtn) switchBtn.addEventListener('click', switchController);

    const bannerSwitch = document.getElementById('lockBannerSwitchBtn');
    if (bannerSwitch) bannerSwitch.addEventListener('click', switchController);

    refreshController();
    setInterval(refreshController, 5000);
}

document.addEventListener('DOMContentLoaded', initControl);