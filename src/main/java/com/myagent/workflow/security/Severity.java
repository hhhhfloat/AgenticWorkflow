// @anchor: severity_tot_desc
// 违规严重级别枚举：定义 ERROR/WARNING/INFO 三档处置语义
package com.myagent.workflow.security;

// @anchor: severity_class
// 严重级别枚举：ERROR 必须拦截、WARNING 仅记录（预留）、INFO 仅提示
public enum Severity {
    ERROR,      // 必须拦截
    WARNING,    // 记录但不拦截（预留）
    INFO        // 仅信息
}
