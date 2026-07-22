package com.myagent.workflow.security.parsers;

import com.myagent.workflow.security.CodeLine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class JavaParser implements CodeParser {
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("//.*$");
    private static final Pattern MULTI_LINE_COMMENT_START = Pattern.compile("/\\*");
    private static final Pattern MULTI_LINE_COMMENT_END = Pattern.compile("\\*/");

    @Override
    public List<CodeLine> extractEffectiveLines(String source) {
        List<CodeLine> effectiveLines = new ArrayList<>();
        String[] lines = source.split("\\n");
        boolean inMultiLineComment = false;
        int lineNum = 0;

        for (String rawLine : lines) {
            lineNum++;
            String line = rawLine;

            // 处理多行注释状态
            if (inMultiLineComment) {
                // 查找结束标记
                int endIdx = line.indexOf("*/");
                if (endIdx != -1) {
                    // 结束多行注释，保留之后的代码
                    line = line.substring(endIdx + 2);
                    inMultiLineComment = false;
                } else {
                    // 整行仍是注释，跳过
                    continue;
                }
            }

            // 处理当前行内的多行注释开始
            int startIdx = line.indexOf("/*");
            if (startIdx != -1) {
                // 可能在同一行结束
                int endIdx = line.indexOf("*/", startIdx + 2);
                if (endIdx != -1) {
                    // 同一行内多行注释，移除中间部分
                    String before = line.substring(0, startIdx);
                    String after = line.substring(endIdx + 2);
                    line = before + after;
                } else {
                    // 多行注释开始但未结束
                    String before = line.substring(0, startIdx);
                    line = before;
                    inMultiLineComment = true;
                }
            }

            // 移除单行注释
            line = SINGLE_LINE_COMMENT.matcher(line).replaceAll("");

            // 去除首尾空白
            line = line.trim();

            if (!line.isEmpty()) {
                effectiveLines.add(new CodeLine(line, lineNum));
            }
        }
        return effectiveLines;
    }
}