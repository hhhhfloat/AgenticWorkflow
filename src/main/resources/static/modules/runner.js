// @anchor: runner_intro
// 项目运行模块：按注册表信息请求后端编译并运行项目

// ===== 项目运行模块 =====

/**
 * 运行已注册的项目
 * @param {string} projectName - 项目名称（如 'calculator'）
 * @param {string} filename - 入口文件名（如 'Main.java'）
 * @param {string} mode - 编译模式（如 'java'、'auto'）
 */
// @anchor: runner_runRegisteredProject
// 运行已注册项目并把编译运行结果回显到日志

// ===== 项目运行模块 =====

// runner.js
 async function runRegisteredProject(projectName, filename, mode, displayPath) {
     // displayPath 是执行时的实际根路径，projectName 是给人看的名字
     const execPath = displayPath || `sandbox/${projectName}`;
     await runProjectWithPath(projectName, execPath, filename, mode);
 }
async function runProjectWithPath(displayName, execPath, filename, mode) {
    const logPrefix = `[系统] ▶ 正在运行项目 ${displayName} (${mode}模式)...`;
    appendLog(logPrefix);

    const settings = getEffectiveSettings ? getEffectiveSettings() : {};
    const config = buildRunConfig(settings);

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
