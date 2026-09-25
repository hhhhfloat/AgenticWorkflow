// @anchor: securityConfig_tot_desc
// 安全规则开关配置：当前默认全部启用，预留按规则 ID 配置的能力
package com.myagent.workflow.security;

// @anchor: securityConfig_class
// 安全配置类：判定某条规则是否启用（现阶段恒为启用）
public class SecurityConfig {
    // 现阶段所有规则默认启用，后续可扩展为从配置文件加载
    // @anchor: securityConfig_isRuleEnabled
    // 判断指定规则是否启用（当前实现为全部启用）
    public boolean isRuleEnabled(String ruleId) {
        return true; // 默认全部启用
    }
}
