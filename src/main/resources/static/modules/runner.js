// @anchor: runner_intro
// 项目运行模块：按注册表信息请求后端编译并运行项目

// @anchor: runner_runRegisteredProject
// 运行已注册项目并把编译运行结果回显到日志
async function runRegisteredProject(projectName, filename, mode) {
    await runProjectWithPath(projectName, filename, mode);
}
// @anchor: runner_runProjectWithPath
// 运行项目并把编译运行结果回显到日志
async function runProjectWithPath(projectName, filename, mode) {
    const logPrefix = `[系统] ▶ 正在运行项目 ${projectName} (${mode}模式)...`;
    appendLog(logPrefix);

    const settings = getEffectiveSettings ? getEffectiveSettings() : {};
    const config = buildRunConfig(settings);

    try {
        const res = await fetch('/runProject', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                filename: filename,
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
