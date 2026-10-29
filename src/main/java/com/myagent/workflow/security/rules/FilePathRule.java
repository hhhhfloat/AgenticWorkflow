// @anchor: filePathRule_intro
// 文件路径规则：检测 ..\ / ../ 或盘符、敏感绝对路径穿越写法
package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;
import java.util.regex.Pattern;

// @anchor: filePathRule_class
// 文件路径规则：匹配路径穿越与绝对盘符/Linux 绝对路径写法并给出修复建议
public class FilePathRule implements Rule {
    // @anchor: filePathRule_pattern
    // 匹配 ..\、../ 或盘符（如 C:\）或 Linux 绝对路径（如 /etc/passwd）
    private static final Pattern PATTERN = Pattern.compile(
            "[.][.][\\\\/]|[A-Za-z]:[\\\\/]|(?:^|[\\s\"'])\\/(etc|proc|sys|root|home|bin|sbin|usr|var|dev)\\/"
    );

    // @anchor: filePathRule_getId
    @Override
    public String getId() { return "FILE_PATH_TRAVERSAL"; }

    // @anchor: filePathRule_getPattern
    @Override
    public Pattern getPattern() { return PATTERN; }

    // @anchor: filePathRule_getSuggestion
    @Override
    public String getSuggestion() {
        return "请使用相对于项目根目录的相对路径，不要包含 '..'、盘符（如 C:）或系统绝对路径（如 /etc）。";
    }

    // @anchor: filePathRule_isEnabled
    @Override
    public boolean isEnabled() { return true; }

    // @anchor: filePathRule_getSeverity
    @Override
    public Severity getSeverity() { return Severity.ERROR; }
}