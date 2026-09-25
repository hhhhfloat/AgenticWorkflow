// @anchor: securityRule_tot_desc
// 安全规则接口：定义危险代码模式的 ID、匹配正则、级别与修复建议
package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

// @anchor: securityRule_class
// 安全规则接口：描述一条危险代码模式的识别与处置方式
public interface Rule {
    // @anchor: securityRule_getId
    // 规则唯一标识
    String getId();
    // @anchor: securityRule_getPattern
    // 危险代码的匹配正则
    Pattern getPattern();
    // @anchor: securityRule_getSuggestion
    // 命中后的修复建议
    String getSuggestion();
    // @anchor: securityRule_isEnabled
    // 规则是否启用
    boolean isEnabled();
    // @anchor: securityRule_getSeverity
    // 规则严重级别
    Severity getSeverity();  // 新增：规则级别
}
