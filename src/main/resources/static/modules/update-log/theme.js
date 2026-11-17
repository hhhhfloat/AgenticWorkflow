// @anchor: ulTheme_intro
// 主题切换：三套配色写在 update-log.css 的 [data-theme] 变量块中，这里只负责挂属性并记忆选择

// @anchor: ulTheme_constants
// 可选主题清单与 localStorage 键名
const UL_THEMES = [
    { id: 'gh-dark',  label: '终端', hint: '深色 · 沿用工作台的 GitHub 暗色' },
    { id: 'midnight', label: '东京', hint: '深色 · Tokyo Night，深邃宁静' },
    { id: 'mint',     label: '薄荷', hint: '浅色 · 温和薄荷绿，日光阅读' }
];
const UL_THEME_KEY = 'ul-theme';

// @anchor: ulTheme_apply
// 应用主题：写 data-theme、同步按钮激活态、持久化选择
function ulApplyTheme(themeId) {
    const known = UL_THEMES.some((theme) => theme.id === themeId);
    const next = known ? themeId : UL_THEMES[0].id;
    UL.theme = next;
    document.documentElement.setAttribute('data-theme', next);
    const container = ULDom.get('ulThemeSwitch');
    if (container) {
        Array.from(container.querySelectorAll('button')).forEach((btn) => {
            const active = btn.dataset.theme === next;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-pressed', active ? 'true' : 'false');
        });
    }
    try {
        localStorage.setItem(UL_THEME_KEY, next);
    } catch (err) {
        // 隐私模式下不可写，忽略即可
    }
}

// @anchor: ulTheme_init
// 初始化：读取记忆主题并绑定切换按钮
function ulInitTheme() {
    let saved = null;
    try {
        saved = localStorage.getItem(UL_THEME_KEY);
    } catch (err) {
        saved = null;
    }
    ulApplyTheme(saved || UL_THEMES[0].id);

    const container = ULDom.get('ulThemeSwitch');
    if (!container) return;
    Array.from(container.querySelectorAll('button')).forEach((btn) => {
        const theme = UL_THEMES.find((item) => item.id === btn.dataset.theme);
        if (theme) btn.title = theme.hint;
    });
    container.addEventListener('click', (event) => {
        const btn = event.target.closest('button[data-theme]');
        if (btn) ulApplyTheme(btn.dataset.theme);
    });
}
