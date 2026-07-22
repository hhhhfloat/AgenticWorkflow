package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

public interface Rule {
    String getId();
    Pattern getPattern();
    String getSuggestion();
    boolean isEnabled();
    Severity getSeverity();  // 新增：规则级别
}