// @anchor: modules_sessionState
// ===== 当前会话 ID 的本地存储 =====

const CURRENT_SESSION_KEY = 'currentSessionId';

function getCurrentSessionId() {
    try {
        return localStorage.getItem(CURRENT_SESSION_KEY) || null;
    } catch (e) {
        return null;
    }
}

function setCurrentSessionId(sessionId) {
    try {
        if (sessionId) {
            localStorage.setItem(CURRENT_SESSION_KEY, sessionId);
        } else {
            localStorage.removeItem(CURRENT_SESSION_KEY);
        }
    } catch (e) {}
}

function clearCurrentSessionId() {
    setCurrentSessionId(null);
}

/**
 * 是否处于"草稿状态"：没有 currentSessionId。
 */
function isDraftSession() {
    return getCurrentSessionId() === null;
}