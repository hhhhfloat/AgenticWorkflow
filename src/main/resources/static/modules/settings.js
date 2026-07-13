// @anchor: modules_settings
// ===== 配置管理模块 =====

const SETTINGS_STORAGE_KEY = 'agentSettings';

// 默认配置（与 ConfigEditor.buildDefault() 保持一致）
const DEFAULT_SETTINGS = {
    model: 'deepseek-v4-pro',
    autoOpenBrowser: false,
    defaultMaxIterations: 30,
    mavenCommand: '',
    javaHome: '',
    pythonInterpreter: '',
    nodeInterpreter: '',
    cppCompilerType: 'msvc',
    msvcCompiler: '',
    msvcInclude: '',
    msvcLib: '',
    mingwCompiler: ''
};

// 加载配置
function loadSettings() {
    try {
        const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
        if (raw) {
            const saved = JSON.parse(raw);
            return { ...DEFAULT_SETTINGS, ...saved };
        }
    } catch (e) {
        console.warn('加载配置失败:', e);
    }
    return { ...DEFAULT_SETTINGS };
}

// 保存配置
function saveSettings(settings) {
    // 过滤掉空字符串的字段，保留默认值
    const cleaned = {};
    for (const [key, value] of Object.entries(settings)) {
        if (value !== '' && value !== null && value !== undefined) {
            cleaned[key] = value;
        }
    }
    try {
        localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(cleaned));
    } catch (e) {
        console.warn('保存配置失败:', e);
    }
}

// 将配置渲染到表单
function applySettingsToForm(settings) {
    document.getElementById('setModel').value = settings.model || 'deepseek-v4-pro';
    document.getElementById('setAutoOpenBrowser').checked = !!settings.autoOpenBrowser;
    document.getElementById('setMavenCommand').value = settings.mavenCommand || '';
    document.getElementById('setJavaHome').value = settings.javaHome || '';
    document.getElementById('setPythonInterpreter').value = settings.pythonInterpreter || '';
    document.getElementById('setNodeInterpreter').value = settings.nodeInterpreter || '';
    document.getElementById('setCppCompilerType').value = settings.cppCompilerType || 'msvc';
    document.getElementById('setMsvcCompiler').value = settings.msvcCompiler || '';
    document.getElementById('setMingwCompiler').value = settings.mingwCompiler || '';
}

// 从表单读取配置
function readSettingsFromForm() {
    return {
        model: document.getElementById('setModel').value.trim(),
        autoOpenBrowser: document.getElementById('setAutoOpenBrowser').checked,
        mavenCommand: document.getElementById('setMavenCommand').value.trim(),
        javaHome: document.getElementById('setJavaHome').value.trim(),
        pythonInterpreter: document.getElementById('setPythonInterpreter').value.trim(),
        nodeInterpreter: document.getElementById('setNodeInterpreter').value.trim(),
        cppCompilerType: document.getElementById('setCppCompilerType').value,
        msvcCompiler: document.getElementById('setMsvcCompiler').value.trim(),
        msvcInclude: '',
        msvcLib: '',
        mingwCompiler: document.getElementById('setMingwCompiler').value.trim()
    };
}

// 获取当前有效配置（用于运行）
function getEffectiveSettings() {
    const settings = loadSettings();
    // 主界面的 maxIterations 独立控制，不从表单读取
    const maxIterations = parseInt(document.getElementById('maxIterations').value) || settings.maxIterations || 20;
    return { ...settings, maxIterations };
}

// 初始化配置弹窗
function initSettingsModal() {
    const modal = document.getElementById('configModal');
    const configBtn = document.getElementById('configBtn');
    const closeBtns = document.querySelectorAll('#closeModalBtn, #closeModalBtn2');

    // 打开弹窗
    configBtn?.addEventListener('click', () => {
        const settings = loadSettings();
        applySettingsToForm(settings);
        modal.style.display = 'flex';
    });

    // 关闭弹窗
    closeBtns.forEach(btn => {
        btn?.addEventListener('click', () => {
            modal.style.display = 'none';
        });
    });

    // 点击外部关闭
    modal?.addEventListener('click', (e) => {
        if (e.target === modal) modal.style.display = 'none';
    });

    // 修改 API 按钮（使用退出码 42）
    document.getElementById('clearApiKeyBtn')?.addEventListener('click', async () => {
        if (!confirm('确认清除 API Key 吗？')) return;
        try {
            await fetch('/clear-api-key', { method: 'POST' });
            closePageAfterRestart('🔑 API Key 已清除，程序将自动重启...');
        } catch (err) {
            appendLog('[系统] ❌ 清除失败: ' + err.message);
        }
    });

    // 保存按钮
    document.getElementById('saveSettingsBtn')?.addEventListener('click', () => {
        const settings = readSettingsFromForm();
        saveSettings(settings);

        const maxIterInput = document.getElementById('maxIterations');
        if (maxIterInput && settings.maxIterations) {
            maxIterInput.value = settings.maxIterations;
        }

        // 发送重启请求
        fetch('/restart', { method: 'POST' }).catch(() => {});
        closePageAfterRestart('✅ 配置已保存，程序将自动重启...');
        modal.style.display = 'none';
    });

    // 恢复默认按钮
    document.getElementById('resetSettingsBtn')?.addEventListener('click', () => {
        if (!confirm('确认恢复默认配置吗？')) return;
        localStorage.removeItem(SETTINGS_STORAGE_KEY);
        applySettingsToForm(DEFAULT_SETTINGS);
        fetch('/restart', { method: 'POST' }).catch(() => {});
        closePageAfterRestart('✅ 已恢复默认配置，程序将自动重启...');
    });

}

// 通用的重启关闭函数
function closePageAfterRestart(message) {
    // 1. 显示提示信息
    appendLog('[系统] ' + message);

    // 2. 弹窗告知用户（可选，让用户知道发生了什么）
    alert(message + '\n\n页面将自动关闭，程序重启后请重新打开。');

    // 3. 尝试关闭页面
    window.close();
}

