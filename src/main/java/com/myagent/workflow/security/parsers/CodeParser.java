package com.myagent.workflow.security.parsers;

import com.myagent.workflow.security.CodeLine;
import java.util.List;

public interface CodeParser {
    /**
     * 从源代码中提取有效代码行（去除注释和空行）
     * @param source 完整的源码字符串
     * @return 有效代码行列表（包含行号）
     */
    List<CodeLine> extractEffectiveLines(String source);
}