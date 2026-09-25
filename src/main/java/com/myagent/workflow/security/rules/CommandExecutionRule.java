// @anchor: commandExecutionRule_tot_desc
// 命令执行规则：检测 Runtime.exec/ProcessBuilder/os.system/subprocess 等系统命令调用
package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

// @anchor: commandExecutionRule_class
// 命令执行规则：匹配多语言的系统命令调用并在命中时给出拦截建议
public class CommandExecutionRule implements Rule {
    // @anchor: commandExecutionRule_pattern
    // 覆盖 Java/Python/Node/C 常见系统命令调用的正则
    // 匹配常见系统命令调用（兼顾多种语言）
    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|" +
                    "os\\.system|subprocess\\.(Popen|call|check_call)|" +
                    "child_process\\.(exec|spawn|execSync)|" +
                    "\\bsystem\\s*\\(|\\bpopen\\s*\\(|" +
                    "powershell\\s+-|cmd\\s+/c)"
    );

    // @anchor: commandExecutionRule_getId
    // 规则 ID：COMMAND_EXECUTION
    @Override
    public String getId() {
        return "COMMAND_EXECUTION";
    }

    // @anchor: commandExecutionRule_getPattern
    // 返回命令执行匹配模式
    @Override
    public Pattern getPattern() {
        return PATTERN;
    }

    // @anchor: commandExecutionRule_getSuggestion
    // 拦截提示：禁止在代码中执行操作系统命令
    @Override
    public String getSuggestion() {
        return "禁止在代码中执行操作系统命令。如需调用外部工具，请通过 Agent 工具参数传递。";
    }

    // @anchor: commandExecutionRule_isEnabled
    // 是否启用：始终启用
    @Override
    public boolean isEnabled() {
        return true;
    }

    // @anchor: commandExecutionRule_getSeverity
    // 严重级别：ERROR（必须拦截）
    @Override
    public Severity getSeverity() {
        return Severity.ERROR;
    }
}
