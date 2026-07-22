package com.myagent.workflow.security.rules;

import com.myagent.workflow.security.Severity;

import java.util.regex.Pattern;

public class CommandExecutionRule implements Rule {
    // 匹配常见系统命令调用（兼顾多种语言）
    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|" +
                    "os\\.system|subprocess\\.(Popen|call|check_call)|" +
                    "child_process\\.(exec|spawn|execSync)|" +
                    "\\bsystem\\s*\\(|\\bpopen\\s*\\(|" +
                    "powershell\\s+-|cmd\\s+/c)"
    );

    @Override
    public String getId() {
        return "COMMAND_EXECUTION";
    }

    @Override
    public Pattern getPattern() {
        return PATTERN;
    }

    @Override
    public String getSuggestion() {
        return "禁止在代码中执行操作系统命令。如需调用外部工具，请通过 Agent 工具参数传递。";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Severity getSeverity() {
        return Severity.ERROR;
    }
}