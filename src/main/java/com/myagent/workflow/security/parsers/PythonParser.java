// @anchor: securityPythonParser_tot_desc
// Python 注释解析器：剥离 # 注释后提取有效代码行
package com.myagent.workflow.security.parsers;

import com.myagent.workflow.security.CodeLine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// @anchor: securityPythonParser_class
// Python 解析器：按行剥离 # 注释并返回非空代码行及其原始行号
public class PythonParser implements CodeParser {
    // @anchor: securityPythonParser_pattern
    // 匹配 Python 单行 # 注释的正则
    private static final Pattern COMMENT = Pattern.compile("#.*$");

    // @anchor: securityPythonParser_extractEffectiveLines
    // 去除每行的 # 注释并裁剪空白，返回非空代码行
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
