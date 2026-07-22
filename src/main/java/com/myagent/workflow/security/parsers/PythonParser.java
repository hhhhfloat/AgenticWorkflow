package com.myagent.workflow.security.parsers;

import com.myagent.workflow.security.CodeLine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PythonParser implements CodeParser {
    private static final Pattern COMMENT = Pattern.compile("#.*$");

    @Override
    public List<CodeLine> extractEffectiveLines(String source) {
        List<CodeLine> effectiveLines = new ArrayList<>();
        String[] lines = source.split("\\n");
        int lineNum = 0;

        // 简单处理多行字符串（三引号）不作为注释，但 Python 的多行字符串可能包含代码，暂忽略。

        for (String rawLine : lines) {
            lineNum++;
            String line = COMMENT.matcher(rawLine).replaceAll("");
            line = line.trim();
            if (!line.isEmpty()) {
                effectiveLines.add(new CodeLine(line, lineNum));
            }
        }
        return effectiveLines;
    }
}