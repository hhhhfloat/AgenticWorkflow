// @anchor: modules_config
// 收集常用 DOM 引用并初始化 marked 渲染器（链接新标签页打开）

// ===== DOM 引用 =====
const output = document.getElementById('output');
const runBtn = document.getElementById('runBtn');
const stopBtn = document.getElementById('stopBtn');
const clearBtn = document.getElementById('clearBtn');
const promptInput = document.getElementById('prompt');

// ===== 配置 marked 渲染器：所有链接在新标签页打开 =====
const renderer = new marked.Renderer();
renderer.link = function(href, title, text) {
    return `<a href="${href}" target="_blank" rel="noopener noreferrer"${title ? ` title="${title}"` : ''}>${text}</a>`;
};
marked.use({ renderer });

// @anchor: modules_state
// 声明前端运行态全局变量（运行标志与心跳定时器）

// ===== 全局状态 =====
let isRunning = false;
let heartbeatInterval = null;

// @anchor: modules_constants
// 定义前端常量（历史条数 / 存储键 / 心跳间隔 / 后端地址）

// ===== 常量配置 =====
const MAX_HISTORY = 30;
const STORAGE_KEY = 'promptHistory';
const HEARTBEAT_INTERVAL_MS = 3000;
const BASE_URL = '';

// ===== 统一构建运行配置 =====
function buildRunConfig(settings) {
    return {
        model: settings.model || '',
        autoOpenBrowser: settings.autoOpenBrowser || false,
        mavenCommand: settings.mavenCommand || '',
        javaHome: settings.javaHome || '',
        pythonInterpreter: settings.pythonInterpreter || '',
        nodeInterpreter: settings.nodeInterpreter || '',
        cppCompilerType: settings.cppCompilerType || 'msvc',
        msvcCompiler: settings.msvcCompiler || '',
        msvcInclude: '',
        msvcLib: '',
        mingwCompiler: settings.mingwCompiler || '',
        enableSecurityScan: settings.enableSecurityScan !== false
    };
}
