// @anchor: modules_runner
// ===== 项目运行模块 =====

/**
 * 运行已注册的项目
 * @param {string} projectName - 项目名称（如 'calculator'）
 * @param {string} filename - 入口文件名（如 'Main.java'）
 * @param {string} mode - 编译模式（如 'java'、'auto'）
 */
// @anchor: modules_runner
// ===== 项目运行模块 =====

// runner.js
async function runRegisteredProject(projectName, filename, mode, displayPath) {
    // displayPath 可选，默认为 sandbox
    const path = displayPath || `sandbox/${projectName}`;
    await runProjectWithPath(path, filename, mode);
}

async function runProjectWithPath(projectName, filename, mode) {
    const logPrefix = `[系统] ▶ 正在运行项目 ${projectName} (${mode}模式)...`;
    appendLog(logPrefix);

    const settings = getEffectiveSettings ? getEffectiveSettings() : {};
    const config = {
        model: settings.model || 'deepseek-v4-pro',
        autoOpenBrowser: settings.autoOpenBrowser || false,
        mavenCommand: settings.mavenCommand || '',
        javaHome: settings.javaHome || '',
        pythonInterpreter: settings.pythonInterpreter || '',
        nodeInterpreter: settings.nodeInterpreter || '',
        cppCompilerType: settings.cppCompilerType || 'msvc',
        msvcCompiler: settings.msvcCompiler || '',
        msvcInclude: '',
        msvcLib: '',
        mingwCompiler: settings.mingwCompiler || ''
    };

    try {
        const res = await fetch('/runProject', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                filename: filename,   // 直接传注册表里的 filename（相对路径）
                mode: mode,
                config: config
            })
        });

        const data = await res.json();
        if (data.status === 'success') {
            appendLog(data.output);
            appendLog(`[系统] ✅ 项目 ${projectName} 运行完成`);
        } else {
            appendLog(`[系统] ❌ 运行失败: ${data.error || '未知错误'}`);
        }
    } catch (err) {
        appendLog(`[系统] ❌ 运行请求失败: ${err.message}`);
    }
}