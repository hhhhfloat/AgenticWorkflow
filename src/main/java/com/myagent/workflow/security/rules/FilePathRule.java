package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

public class FilePathRule implements Rule {
    private static final Pattern PATTERN = Pattern.compile(
            // 匹配 ..\ 或 ../ 或盘符: \
            "[.][.][\\\\/]|[A-Za-z]:[\\\\/]"
    );

    @Override
    public String getId() {
        return "FILE_PATH_TRAVERSAL";
    }

    @Override
    public Pattern getPattern() {
        return PATTERN;
    }

    @Override
    public String getSuggestion() {
        return "请使用相对于项目根目录的相对路径，不要包含 '..' 或盘符（如 C:）";
    }

    @Override
    public boolean isEnabled() {
        return true; // 始终启用
    }

    @Override
    public Severity getSeverity() {
        return Severity.ERROR;
    }
}