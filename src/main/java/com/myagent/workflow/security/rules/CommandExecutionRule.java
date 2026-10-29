// @anchor: commandExecutionRule_intro
// 命令执行规则：检测 Runtime.exec/ProcessBuilder/os.system/subprocess 等系统命令调用
package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;
import java.util.regex.Pattern;

// @anchor: commandExecutionRule_class
// 命令执行规则：匹配多语言的系统命令调用并在命中时给出拦截建议
public class CommandExecutionRule implements Rule {
    // @anchor: commandExecutionRule_pattern
    // 覆盖 Java/Python/Node/C/Shell 常见系统命令调用的正则
    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|" +
                    "os\\.system|subprocess\\.(Popen|call|check_call|run)|" +
                    "child_process\\.(exec|spawn|execSync)|" +
                    "\\bsystem\\s*\\(|\\bpopen\\s*\\(|" +
                    "powershell\\s+-|cmd\\s+/c|" +
                    "\\b(sh|bash|zsh|fish|python|perl|ruby)\\s+-c\\b|" +
                    "\\bexec\\s*\\()"
    );

    // @anchor: commandExecutionRule_getId
    @Override
    public String getId() { return "COMMAND_EXECUTION"; }

    // @anchor: commandExecutionRule_getPattern
    @Override
    public Pattern getPattern() { return PATTERN; }

    // @anchor: commandExecutionRule_getSuggestion
    @Override
    public String getSuggestion() {
        return "禁止在代码中执行操作系统命令。如需调用外部工具，请通过 Agent 工具参数传递。";
    }

    // @anchor: commandExecutionRule_isEnabled
    @Override
    public boolean isEnabled() { return true; }

    // @anchor: commandExecutionRule_getSeverity
    @Override
    public Severity getSeverity() { return Severity.ERROR; }
}