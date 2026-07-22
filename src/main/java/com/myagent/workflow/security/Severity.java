package com.myagent.workflow.security;

public enum Severity {
    ERROR,      // 必须拦截
    WARNING,    // 记录但不拦截（预留）
    INFO        // 仅信息
}