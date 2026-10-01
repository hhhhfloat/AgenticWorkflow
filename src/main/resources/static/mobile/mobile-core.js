// 手机端核心：常量、DOM 引用、全局状态、通用渲染工具

const BASE_URL = '';
const HEARTBEAT_MS = 30000;
const SESSION_KEY = 'mobileCurrentSessionId';

let currentSessionId = null;
let isRunning = false;

// ===== DOM 引用 =====
const outputEl = document.getElementById('output');
const promptEl = document.getElementById('prompt');
const runBtn = document.getElementById('runBtn');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const sessionTitleEl = document.getElementById('sessionTitle');
const projectTag = document.getElementById('projectTag');
const usageBar = document.getElementById('usageBar');
const maxIterEl = document.getElementById('maxIterations');
const switchLocalBtn = document.getElementById('switchLocalBtn');

const menuBtn = document.getElementById('menuBtn');
const closeDrawerBtn = document.getElementById('closeDrawerBtn');
const drawer = document.getElementById('drawer');
const sessionListView = document.getElementById('sessionListView');
const newSessionBtn = document.getElementById('newSessionBtn');

const tabSessionsBtn = document.getElementById('tabSessionsBtn');
const tabFilesBtn = document.getElementById('tabFilesBtn');
const drawerSessions = document.getElementById('drawerSessions');
const drawerFiles = document.getElementById('drawerFiles');

const createMask = document.getElementById('createMask');
const newProjectName = document.getElementById('newProjectName');
const closeCreateBtn = document.getElementById('closeCreateBtn');
const cancelCreateBtn = document.getElementById('cancelCreateBtn');
const confirmCreateBtn = document.getElementById('confirmCreateBtn');

// ===== 全局 JS 错误兜底 =====
window.addEventListener('error', e => {
    const el = document.getElementById('output');
    if (el && !el.dataset.errShown) {
        el.dataset.errShown = '1';
        el.innerHTML = '<div class="msg error">JS 错误：' + e.message +
        '<br>行 ' + e.lineno + ':' + e.colno + '</div>';
    }
});

// ===== 渲染工具 =====
function clearOutput() { outputEl.innerHTML = ''; }

function appendMessage(role, text) {
    const div = document.createElement('div');
    div.className = 'msg ' + role;
    div.textContent = text;
    outputEl.appendChild(div);
    outputEl.scrollTop = outputEl.scrollHeight;
}

function appendLog(text) { appendMessage('log', text); }

function setStatus(running) {
    isRunning = running;
    statusDot.className = 'dot ' + (running ? 'running' : 'idle');
    statusText.textContent = running ? '运行中' : '空闲';
    runBtn.disabled = false;
    if (running) {
        runBtn.textContent = '■';
        runBtn.classList.add('stop');
        runBtn.title = '停止';
    } else {
        runBtn.textContent = '▶';
        runBtn.classList.remove('stop');
        runBtn.title = '发送';
    }
}

function renderUsage(u) {
    const input = u.promptTokens || 0;
    const cached = u.cachedTokens || 0;
    const output = u.completionTokens || 0;
    const calls = u.apiCalls || 0;
    const cost = Number(u.cost || 0).toFixed(4);
    const hitRate = input > 0 ? Math.round(cached / input * 100) : 0;
    usageBar.textContent =
      `Ⓜ️ ${input} / ${output}    ♾️ ${hitRate}%    📞 ${calls}    🪙 ¥${cost}`;
}

function setSessionTitle(title) {
    sessionTitleEl.textContent = title || '新对话';
}

function setProject(project) {
    if (project) {
        projectTag.textContent = '📌 ' + project;
        projectTag.hidden = false;
    } else {
        projectTag.textContent = '';
        projectTag.hidden = true;
    }
}