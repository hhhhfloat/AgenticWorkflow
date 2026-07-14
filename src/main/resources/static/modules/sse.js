// @anchor: modules_sse
// ===== SSE 流式请求模块 =====

function runAgent(prompt, maxIterations) {
    output.innerHTML = '连接服务...\n';
    isRunning = true;
    runBtn.disabled = true;
    stopBtn.disabled = false;
    startHeartbeat();

    // 获取完整配置
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

    fetch(BASE_URL + '/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            prompt: prompt,
            maxIterations: maxIterations,  // 独立传递，不放入 config
            config: config
        })
    })
        .then(response => {
            if (!response.ok) {
                return response.text().then(text => {
                    throw new Error(`HTTP ${response.status}: ${text}`);
                });
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';

            function readChunk() {
                reader.read().then(({ done, value }) => {
                    if (done) {
                        finishRun();
                        return;
                    }
                    buffer += decoder.decode(value, { stream: true });
                    const events = buffer.split('\n\n');
                    buffer = events.pop();
                    for (const event of events) {
                        if (event.startsWith('data: ')) {
                            const data = event.substring(6).trim().replace(/\\n/g, '\n');
                            if (data === '[结束]') {
                                finishRun();
                                return;
                            }
                            appendLog(data);
                        }
                    }
                    readChunk();
                }).catch(err => {
                    appendLog('[错误] ' + err.message);
                    finishRun();
                });
            }
            readChunk();
        })
        .catch(err => {
            appendLog('[连接错误] ' + err.message + '\n请确保 HTTP 服务已启动（运行 start.bat）');
            finishRun();
        });
}

// @anchor: modules_finishRun
// ===== 运行结束清理 =====
function finishRun() {
    runBtn.disabled = false;
    stopBtn.disabled = true;
    isRunning = false;
    stopHeartbeat();
    refreshSandbox();
}

// @anchor: modules_stop
// ===== 停止 Agent =====
function stopAgent() {
    if (!isRunning) return;
    stopBtn.disabled = true;
    appendLog('[系统] 正在停止任务...');

    // 立即停止心跳，防止后端时间被重置
    stopHeartbeat();

    fetch(BASE_URL + '/stop', { method: 'POST' })
        .then(response => response.json())
        .then(data => {
        appendLog('[系统] ' + data.message);
        stopBtn.disabled = true;
        runBtn.disabled = false;
        isRunning = false;
        // 强制刷新状态
        finishRun();
    })
        .catch(err => {
        appendLog('[错误] 停止请求失败: ' + err.message);
        stopBtn.disabled = false;
        stopHeartbeat();
    });
}
