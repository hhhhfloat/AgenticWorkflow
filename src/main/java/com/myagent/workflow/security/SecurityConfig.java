package com.myagent.workflow.security;

public class SecurityConfig {
    // 现阶段所有规则默认启用，后续可扩展为从配置文件加载
    public boolean isRuleEnabled(String ruleId) {
        return true; // 默认全部启用
    }
}