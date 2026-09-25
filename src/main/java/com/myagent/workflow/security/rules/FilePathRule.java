// @anchor: filePathRule_tot_desc
// 文件路径规则：检测 ..\\ / ../ 或盘符等路径穿越写法
package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

// @anchor: filePathRule_class
// 文件路径规则：匹配路径穿越与绝对盘符写法并给出修复建议
public class FilePathRule implements Rule {
    // @anchor: filePathRule_pattern
    // 匹配 ..\、../ 或盘符（如 C:\）的正则
    private static final Pattern PATTERN = Pattern.compile(
            // 匹配 ..\ 或 ../ 或盘符: \
            "[.][.][\\\\/]|[A-Za-z]:[\\\\/]"
    );

    // @anchor: filePathRule_getId
    // 规则 ID：FILE_PATH_TRAVERSAL
    @Override
    public String getId() {
        return "FILE_PATH_TRAVERSAL";
    }

    // @anchor: filePathRule_getPattern
    // 返回路径穿越匹配模式
    @Override
    public Pattern getPattern() {
        return PATTERN;
    }

    // @anchor: filePathRule_getSuggestion
    // 修复建议：使用相对项目根目录的路径，避免 .. 或盘符
    @Override
    public String getSuggestion() {
        return "请使用相对于项目根目录的相对路径，不要包含 '..' 或盘符（如 C:）";
    }

    // @anchor: filePathRule_isEnabled
    // 是否启用：始终启用
    @Override
    public boolean isEnabled() {
        return true; // 始终启用
    }

    // @anchor: filePathRule_getSeverity
    // 严重级别：ERROR（必须拦截）
    @Override
    public Severity getSeverity() {
        return Severity.ERROR;
    }
}
